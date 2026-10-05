package com.rangandale.appthrottler.vpn.forward

import android.util.Log
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.vpn.throttle.PacketLossFilter
import com.rangandale.appthrottler.vpn.throttle.TokenBucket
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Relays UDP datagrams between the TUN device and one [DatagramChannel] per flow, each kept
 * outside the tunnel by [protect].
 * Replies are policed by the download bucket and the packet-loss setting (UDP has no
 * backpressure, so excess traffic is dropped just like on a congested link).
 */
class UdpForwarder(
    private val protect: (DatagramSocket) -> Boolean,
    private val tun: TunWriter,
    private val downloadBucket: TokenBucket,
    private val packetLossPercent: Float,
    private val mtu: Int,
) {
    private class Session(val key: UdpKey, val channel: DatagramChannel) {
        @Volatile var lastActivityMs: Long = System.currentTimeMillis()
    }

    data class UdpKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

    private val running = AtomicBoolean(false)
    private val selector = Selector.open()
    private val sessions = ConcurrentHashMap<UdpKey, Session>()

    // Channels are registered by the selector thread itself: registering from another thread
    // can block for a whole select() timeout on some Android versions.
    private val pendingRegistrations = ConcurrentLinkedQueue<Session>()
    private var selectorThread: Thread? = null

    fun start() {
        running.set(true)
        selectorThread = Thread(::runSelector, "throttler-udp").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        selector.wakeup()
        selectorThread?.let { thread ->
            thread.interrupt()
            runCatching { thread.join(JOIN_TIMEOUT_MS) }
        }
        sessions.values.forEach { runCatching { it.channel.close() } }
        sessions.clear()
        pendingRegistrations.clear()
        runCatching { selector.close() }
    }

    /** Forwards one outbound IPv4/UDP packet read from the TUN device. */
    fun forward(packet: ByteArray) {
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < IpPacket.IP_HEADER_LEN || packet.size < ihl + IpPacket.UDP_HEADER_LEN) return
        val bb = ByteBuffer.wrap(packet)
        val totalLen = minOf(bb.getShort(2).toInt() and 0xFFFF, packet.size)
        val dataStart = ihl + IpPacket.UDP_HEADER_LEN
        val dataLen = totalLen - dataStart
        if (dataLen <= 0) return

        val key = UdpKey(
            srcIp = bb.getInt(12),
            srcPort = bb.getShort(ihl).toInt() and 0xFFFF,
            dstIp = bb.getInt(16),
            dstPort = bb.getShort(ihl + 2).toInt() and 0xFFFF,
        )
        val session = sessions[key] ?: openSession(key) ?: return
        session.lastActivityMs = System.currentTimeMillis()
        try {
            session.channel.write(ByteBuffer.wrap(packet, dataStart, dataLen))
        } catch (_: ClosedChannelException) {
            // session expired or teardown in progress
        } catch (e: IOException) {
            if (running.get()) Log.w(TAG, "UDP forward error", e)
            closeSession(session)
        }
    }

    private fun openSession(key: UdpKey): Session? {
        var channel: DatagramChannel? = null
        return try {
            channel = DatagramChannel.open()
            channel.configureBlocking(false)
            if (!protect(channel.socket())) throw IOException("protect() failed")
            channel.connect(InetSocketAddress(IpPacket.addressOf(key.dstIp), key.dstPort))
            val session = Session(key, channel)
            sessions[key] = session
            pendingRegistrations.add(session)
            selector.wakeup()
            session
        } catch (e: Exception) {
            channel?.runCatching { close() }
            if (running.get()) {
                Log.e(TAG, "Failed to create UDP channel", e)
                ThrottleLogger.e(TAG, "UDP session failed: ${e.message}")
            }
            null
        }
    }

    private fun closeSession(session: Session) {
        sessions.remove(session.key, session)
        session.channel.runCatching { close() }
    }

    private fun runSelector() {
        val buf = ByteBuffer.allocate(MAX_DATAGRAM)
        var lastCleanupMs = System.currentTimeMillis()
        while (running.get()) {
            try {
                registerPending()
                selector.select(SELECT_TIMEOUT_MS)
                if (!running.get()) break

                val iter = selector.selectedKeys().iterator()
                while (iter.hasNext()) {
                    val selKey = iter.next()
                    iter.remove()
                    if (selKey.isValid && selKey.isReadable) onReadable(selKey.attachment() as Session, buf)
                }

                val now = System.currentTimeMillis()
                if (now - lastCleanupMs >= CLEANUP_INTERVAL_MS) {
                    lastCleanupMs = now
                    expireIdleSessions(now)
                }
            } catch (_: ClosedSelectorException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "UDP selector error", e)
            }
        }
    }

    private fun registerPending() {
        while (true) {
            val session = pendingRegistrations.poll() ?: break
            try {
                session.channel.register(selector, SelectionKey.OP_READ, session)
            } catch (_: ClosedChannelException) {
                // closed before it could be registered
            }
        }
    }

    private fun onReadable(session: Session, buf: ByteBuffer) {
        // Drain everything that is queued so one busy flow cannot stall behind select() wakeups.
        while (true) {
            buf.clear()
            val read = try {
                session.channel.read(buf)
            } catch (_: IOException) {
                // e.g. ICMP port unreachable surfaced as PortUnreachableException
                closeSession(session)
                return
            }
            if (read <= 0) return

            session.lastActivityMs = System.currentTimeMillis()
            if (PacketLossFilter.shouldDrop(packetLossPercent)) continue
            if (!downloadBucket.tryConsume(read)) continue

            val key = session.key
            val packets = IpPacket.buildUdp(
                srcIp = key.dstIp,
                srcPort = key.dstPort,
                dstIp = key.srcIp,
                dstPort = key.srcPort,
                data = buf.array(),
                dataLength = read,
                mtu = mtu,
            )
            for (packet in packets) tun.write(packet)
        }
    }

    private fun expireIdleSessions(now: Long) {
        for (session in sessions.values) {
            if (now - session.lastActivityMs > SESSION_TIMEOUT_MS) closeSession(session)
        }
    }

    companion object {
        private const val TAG = "UdpForwarder"
        private const val MAX_DATAGRAM = 65_535
        private const val SELECT_TIMEOUT_MS = 1_000L
        private const val SESSION_TIMEOUT_MS = 60_000L
        private const val CLEANUP_INTERVAL_MS = 15_000L
        private const val JOIN_TIMEOUT_MS = 500L
    }
}
