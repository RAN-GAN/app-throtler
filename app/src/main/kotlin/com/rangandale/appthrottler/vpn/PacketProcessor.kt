package com.rangandale.appthrottler.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.rangandale.appthrottler.domain.model.ThrottleConfig
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.vpn.capture.PacketCapture
import com.rangandale.appthrottler.vpn.flow.FlowKey
import com.rangandale.appthrottler.vpn.flow.FlowOwners
import com.rangandale.appthrottler.vpn.flow.FlowPolicy
import com.rangandale.appthrottler.vpn.forward.IpPacket
import com.rangandale.appthrottler.vpn.forward.TcpForwarder
import com.rangandale.appthrottler.vpn.forward.TunWriter
import com.rangandale.appthrottler.vpn.forward.UdpForwarder
import com.rangandale.appthrottler.vpn.throttle.LatencyQueue
import com.rangandale.appthrottler.vpn.throttle.PacketLossFilter
import com.rangandale.appthrottler.vpn.throttle.ShaperSet
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pumps packets between the TUN device and the forwarders.
 *
 * Outbound (app → internet): TUN read → owning app → packet loss → upload bucket → latency queue
 * → forwarder. Inbound (internet → app) is driven by the forwarders, which apply the download
 * bucket. Loss, buckets and latency come from the owning app's [ShaperSet] entry.
 */
class PacketProcessor(
    private val config: ThrottleConfig,
    private val tunFd: ParcelFileDescriptor,
    vpnService: VpnService,
    private val shapers: ShaperSet,
    policy: FlowPolicy,
    private val owners: FlowOwners,
    mtu: Int,
) {
    private val running = AtomicBoolean(false)

    private val tunIn = FileInputStream(tunFd.fileDescriptor)
    private val tunWriter = TunWriter(FileOutputStream(tunFd.fileDescriptor))

    private val latencyQueue = LatencyQueue()

    private val udpForwarder = UdpForwarder({ vpnService.protect(it) }, tunWriter, shapers, policy, owners, mtu)
    private val tcpForwarder = TcpForwarder({ vpnService.protect(it) }, tunWriter, shapers, policy, owners, mtu)

    private val readBuffer = ByteArray(MAX_PACKET_SIZE)

    private var unsupportedPackets = 0L
    private var lastUnsupportedLogMs = 0L
    private var queueDrops = 0L
    private var lastQueueDropLogMs = 0L

    private var outboundThread: Thread? = null
    private var drainThread: Thread? = null

    fun start() {
        ThrottleLogger.i(
            TAG,
            "Starting — down=${config.downloadSpeedKbps}kbps up=${config.uploadSpeedKbps}kbps " +
                "latency=${config.latencyMs}±${config.jitterMs}ms loss=${config.packetLossPercent}%",
        )
        running.set(true)
        udpForwarder.start()
        tcpForwarder.start()
        outboundThread = Thread(::runOutbound, "throttler-outbound").also { it.isDaemon = true; it.start() }
        drainThread = Thread(::runDrain, "throttler-drain").also { it.isDaemon = true; it.start() }
    }

    /**
     * Stops all packet threads. Returns once the TUN reader has exited (or a short timeout
     * passed), so the caller can close the TUN descriptor without racing a read on it.
     */
    fun stop() {
        if (!running.getAndSet(false)) return
        latencyQueue.clear()
        outboundThread?.interrupt()
        drainThread?.interrupt()
        runCatching { outboundThread?.join(JOIN_TIMEOUT_MS) }
        runCatching { drainThread?.join(JOIN_TIMEOUT_MS) }
        udpForwarder.stop()
        tcpForwarder.stop()
        tunWriter.stop()
        ThrottleLogger.d(TAG, "Stopped")
    }

    /** Resets all open flows while the tunnel stays up. */
    fun resetConnections() {
        tcpForwarder.resetAll()
        udpForwarder.resetAll()
    }

    private fun runOutbound() {
        val pollFd = StructPollfd().apply {
            fd = tunFd.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        val pollFds = arrayOf(pollFd)
        val closedMask = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL

        try {
            while (running.get()) {
                // Poll with a timeout instead of blocking in read() so stop() is noticed
                // promptly even when the apps are silent.
                pollFd.revents = 0
                val ready = try {
                    Os.poll(pollFds, POLL_TIMEOUT_MS)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw IOException(e)
                }
                if (ready <= 0) continue
                val revents = pollFd.revents.toInt()
                if (revents and OsConstants.POLLIN == 0) {
                    if (revents and closedMask != 0) break
                    continue
                }

                val len = tunIn.read(readBuffer)
                if (len < 0) break
                if (len == 0) continue

                if (!isForwardable(readBuffer, len)) {
                    noteUnsupported(readBuffer, len)
                    continue
                }
                val uid = ownerOf(readBuffer)
                PacketCapture.offer(readBuffer, len, uid)

                val shaper = shapers.forUid(uid)
                val params = shaper.params
                if (PacketLossFilter.shouldDrop(params.packetLossPercent)) continue
                // Rate limiting is a delay, not a blocking wait, so one app's slow profile
                // cannot hold up another app's packets. A full queue drops, like a real link.
                val waitMs = shaper.upload.reserve(len, MAX_QUEUE_DELAY_MS)
                if (waitMs < 0) {
                    noteQueueDrop()
                    continue
                }
                // Counted once accepted, so the upload figures show what is actually sent on.
                TrafficCounters.addOut(len)
                latencyQueue.enqueue(readBuffer.copyOf(len), shaper.upLatencyMs, shaper.upJitterMs, shaper.lane, waitMs)
            }
        } catch (e: IOException) {
            if (running.get()) {
                Log.e(TAG, "TUN read failed", e)
                ThrottleLogger.e(TAG, "TUN read failed: ${e.message}")
            }
        }
    }

    private fun runDrain() {
        while (running.get()) {
            val packet = try {
                latencyQueue.poll(DRAIN_POLL_MS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            try {
                when (IpPacket.protocolOf(packet)) {
                    IpPacket.PROTO_UDP -> udpForwarder.forward(packet)
                    IpPacket.PROTO_TCP -> tcpForwarder.forward(packet)
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "Packet forward error", e)
                    ThrottleLogger.e(TAG, "Forward error: ${e.message}")
                }
            }
        }
    }

    /** Only unfragmented TCP/UDP, without IPv6 extension headers, can be relayed through plain sockets. */
    private fun isForwardable(buffer: ByteArray, length: Int): Boolean {
        if (length < IpPacket.IP_HEADER_LEN) return false
        val protocol = IpPacket.protocolOf(buffer) ?: return false
        val ihl = IpPacket.headerLength(buffer)
        val transportHeader = if (protocol == IpPacket.PROTO_TCP) IpPacket.TCP_HEADER_LEN else IpPacket.UDP_HEADER_LEN
        if (ihl < IpPacket.IP_HEADER_LEN || length < ihl + transportHeader) return false
        return !IpPacket.isFragment(buffer)
    }

    /** The app that sent the (already validated) TCP/UDP packet in [packet]. */
    private fun ownerOf(packet: ByteArray): Int {
        val ihl = IpPacket.headerLength(packet)
        val protocol = IpPacket.protocolOf(packet) ?: return FlowOwners.UNKNOWN_UID
        val key = FlowKey(
            protocol = protocol,
            srcIp = IpPacket.sourceOf(packet),
            srcPort = shortAt(packet, ihl),
            dstIp = IpPacket.destinationOf(packet),
            dstPort = shortAt(packet, ihl + 2),
        )
        // A SYN starts a new connection, possibly on a port another app used before.
        val isSyn = protocol == IpPacket.PROTO_TCP && packet[ihl + 13].toInt() and IpPacket.TCP_SYN != 0
        return owners.resolve(key, refresh = isSyn)
    }

    private fun shortAt(data: ByteArray, i: Int): Int =
        ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)

    private fun noteQueueDrop() {
        queueDrops++
        val now = System.currentTimeMillis()
        if (now - lastQueueDropLogMs > UNSUPPORTED_LOG_INTERVAL_MS) {
            lastQueueDropLogMs = now
            ThrottleLogger.d(TAG, "Upload queue full — dropped $queueDrops packet(s) so far")
        }
    }

    private fun noteUnsupported(buffer: ByteArray, length: Int) {
        unsupportedPackets++
        val now = System.currentTimeMillis()
        if (now - lastUnsupportedLogMs > UNSUPPORTED_LOG_INTERVAL_MS) {
            lastUnsupportedLogMs = now
            ThrottleLogger.d(
                TAG,
                "Dropped $unsupportedPackets unsupported packet(s), latest: ${describe(buffer, length)} " +
                    "— only TCP and UDP are forwarded",
            )
        }
    }

    private fun describe(buffer: ByteArray, length: Int): String {
        if (length < 1) return "empty"
        return when (val version = (buffer[0].toInt() and 0xFF) ushr 4) {
            4 -> when {
                length < IpPacket.IP_HEADER_LEN -> "truncated IPv4"
                IpPacket.isFragment(buffer) -> "IPv4 fragment"
                else -> "IPv4 protocol ${buffer[9].toInt() and 0xFF}"
            }
            6 -> if (length > 6) "IPv6 next-header ${buffer[6].toInt() and 0xFF}" else "truncated IPv6"
            else -> "IP version $version"
        }
    }

    companion object {
        private const val TAG = "PacketProcessor"
        private const val MAX_PACKET_SIZE = 32_768
        private const val POLL_TIMEOUT_MS = 100
        private const val DRAIN_POLL_MS = 100L

        /** Longest a packet may wait for upload bandwidth before it is dropped instead. */
        private const val MAX_QUEUE_DELAY_MS = 5_000L
        private const val JOIN_TIMEOUT_MS = 1_000L
        private const val UNSUPPORTED_LOG_INTERVAL_MS = 5_000L
    }
}
