package com.rangandale.appthrottler.vpn.forward

import android.util.Log
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.vpn.flow.DnsMessage
import com.rangandale.appthrottler.vpn.flow.FlowKey
import com.rangandale.appthrottler.vpn.flow.FlowOwners
import com.rangandale.appthrottler.vpn.flow.FlowPolicy
import com.rangandale.appthrottler.vpn.flow.FlowRecord
import com.rangandale.appthrottler.vpn.flow.FlowRegistry
import com.rangandale.appthrottler.vpn.flow.FlowState
import com.rangandale.appthrottler.vpn.flow.HostnameCache
import com.rangandale.appthrottler.vpn.throttle.Shaper
import com.rangandale.appthrottler.vpn.throttle.ShaperSet
import com.rangandale.appthrottler.vpn.throttle.PacketLossFilter
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
 * Replies are policed by the owning app's download bucket and packet-loss setting (UDP has no
 * backpressure, so excess traffic is dropped just like on a congested link).
 *
 * DNS passes through here, which is used twice: queries for blocked domains are answered
 * locally with "no such domain", and replies teach the [HostnameCache] which name an address
 * belongs to.
 */
class UdpForwarder(
    private val protect: (DatagramSocket) -> Boolean,
    private val tun: TunWriter,
    private val shapers: ShaperSet,
    private val policy: FlowPolicy,
    private val owners: FlowOwners,
    private val mtu: Int,
) {
    private class Session(
        val key: UdpKey,
        val channel: DatagramChannel,
        val uid: Int,
        val record: FlowRecord,
        val shaper: Shaper,
    ) {
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
        sessions.values.forEach { session ->
            runCatching { session.channel.close() }
            FlowRegistry.close(session.record)
        }
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
        var session = sessions[key]
        val uid = session?.uid
            ?: owners.resolve(FlowKey(IpPacket.PROTO_UDP, key.srcIp, key.srcPort, key.dstIp, key.dstPort))

        if (policy.blocks(uid)) {
            session?.let { closeSession(it, FlowState.BLOCKED) }
            FlowRegistry.blocked(IpPacket.PROTO_UDP, uid, key.srcPort, key.dstIp, key.dstPort, hostname = null)
            return
        }
        if (key.dstPort == DNS_PORT && refusedDnsQuery(key, uid, packet, dataStart, dataLen)) return

        if (session == null) session = openSession(key, uid) ?: return
        session.lastActivityMs = System.currentTimeMillis()
        session.record.addUp(packet.size)
        try {
            session.channel.write(ByteBuffer.wrap(packet, dataStart, dataLen))
        } catch (_: ClosedChannelException) {
            // session expired or teardown in progress
        } catch (e: IOException) {
            if (running.get()) Log.w(TAG, "UDP forward error", e)
            closeSession(session)
        }
    }

    /** Answers a query for a blocked domain with NXDOMAIN instead of forwarding it. */
    private fun refusedDnsQuery(key: UdpKey, uid: Int, packet: ByteArray, dataStart: Int, dataLen: Int): Boolean {
        val name = DnsMessage.questionName(packet, dataStart, dataLen) ?: return false
        if (!policy.blocksDomain(name)) return false
        FlowRegistry.blocked(IpPacket.PROTO_UDP, uid, key.srcPort, key.dstIp, key.dstPort, hostname = name)
        val reply = DnsMessage.nxDomainFor(packet, dataStart, dataLen)
        IpPacket.buildUdp(key.dstIp, key.dstPort, key.srcIp, key.srcPort, reply, reply.size, mtu)
            .forEach { tun.write(it, uid) }
        return true
    }

    private fun openSession(key: UdpKey, uid: Int): Session? {
        var channel: DatagramChannel? = null
        return try {
            channel = DatagramChannel.open()
            channel.configureBlocking(false)
            if (!protect(channel.socket())) throw IOException("protect() failed")
            channel.connect(InetSocketAddress(IpPacket.addressOf(key.dstIp), key.dstPort))
            val record = FlowRegistry.open(IpPacket.PROTO_UDP, uid, key.srcPort, key.dstIp, key.dstPort)
            val session = Session(key, channel, uid, record, shapers.forUid(uid))
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

    private fun closeSession(session: Session, state: FlowState = FlowState.CLOSED) {
        sessions.remove(session.key, session)
        session.channel.runCatching { close() }
        FlowRegistry.close(session.record, state)
        // The source port may be reused by another app later.
        val key = session.key
        owners.forget(FlowKey(IpPacket.PROTO_UDP, key.srcIp, key.srcPort, key.dstIp, key.dstPort))
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
                closeSession(session, FlowState.FAILED)
                return
            }
            if (read <= 0) return

            session.lastActivityMs = System.currentTimeMillis()
            val key = session.key
            if (key.dstPort == DNS_PORT) learnHostnames(buf.array(), read)
            if (PacketLossFilter.shouldDrop(session.shaper.params.packetLossPercent)) continue
            if (!session.shaper.download.tryConsume(read)) continue

            val packets = IpPacket.buildUdp(
                srcIp = key.dstIp,
                srcPort = key.dstPort,
                dstIp = key.srcIp,
                dstPort = key.srcPort,
                data = buf.array(),
                dataLength = read,
                mtu = mtu,
            )
            for (packet in packets) {
                tun.write(packet, session.uid)
                session.record.addDown(packet.size)
            }
        }
    }

    private fun learnHostnames(data: ByteArray, length: Int) {
        val reply = DnsMessage.parseReply(data, 0, length) ?: return
        for (address in reply.addresses) HostnameCache.put(address, reply.name)
    }

    private fun expireIdleSessions(now: Long) {
        for (session in sessions.values) {
            // DNS exchanges are one-shot; keeping them around only clutters the flow list.
            val timeoutMs = if (session.key.dstPort == DNS_PORT) DNS_SESSION_TIMEOUT_MS else SESSION_TIMEOUT_MS
            if (now - session.lastActivityMs > timeoutMs) closeSession(session)
        }
    }

    companion object {
        private const val TAG = "UdpForwarder"
        private const val MAX_DATAGRAM = 65_535
        private const val SELECT_TIMEOUT_MS = 1_000L
        private const val DNS_PORT = 53
        private const val SESSION_TIMEOUT_MS = 60_000L
        private const val DNS_SESSION_TIMEOUT_MS = 10_000L
        private const val CLEANUP_INTERVAL_MS = 5_000L
        private const val JOIN_TIMEOUT_MS = 500L
    }
}
