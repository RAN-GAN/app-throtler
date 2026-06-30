package com.rangandale.appthrottler.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.rangandale.appthrottler.domain.model.ThrottleConfig
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.vpn.forward.TcpForwarder
import com.rangandale.appthrottler.vpn.forward.UdpForwarder
import com.rangandale.appthrottler.vpn.throttle.LatencyQueue
import com.rangandale.appthrottler.vpn.throttle.PacketLossFilter
import com.rangandale.appthrottler.vpn.throttle.TokenBucket
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class PacketProcessor(
    private val config: ThrottleConfig,
    private val tunFd: ParcelFileDescriptor,
    private val vpnService: VpnService,
) {
    private val running = AtomicBoolean(false)

    private val tunIn = FileInputStream(tunFd.fileDescriptor)
    private val tunOut = FileOutputStream(tunFd.fileDescriptor)

    private val uploadBucket = TokenBucket(config.uploadSpeedKbps)
    private val downloadBucket = TokenBucket(config.downloadSpeedKbps)
    private val latencyQueue = LatencyQueue()

    private val udpForwarder = UdpForwarder(vpnService, tunOut, downloadBucket)
    private val tcpForwarder = TcpForwarder(vpnService, tunOut, downloadBucket)

    private val readBuffer = ByteArray(MAX_PACKET_SIZE)

    private var outboundThread: Thread? = null
    private var drainThread: Thread? = null

    fun start() {
        ThrottleLogger.i(TAG, "PacketProcessor starting — dl=${config.downloadSpeedKbps}kbps ul=${config.uploadSpeedKbps}kbps latency=${config.latencyMs}ms loss=${config.packetLossPercent}%")
        running.set(true)
        udpForwarder.start()
        tcpForwarder.start()
        outboundThread = Thread(::runOutbound, "throttler-outbound").also { it.isDaemon = true; it.start() }
        drainThread = Thread(::runDrain, "throttler-drain").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        outboundThread?.interrupt()
        drainThread?.interrupt()
        udpForwarder.stop()
        tcpForwarder.stop()
    }

    fun isRunning(): Boolean = running.get()

    fun currentConfig(): ThrottleConfig = config

    private fun runOutbound() {
        while (running.get()) {
            val len = try {
                tunIn.read(readBuffer)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "TUN read error", e)
                break
            }
            if (len <= 0) continue

            if (PacketLossFilter.shouldDrop(config.packetLossPercent)) continue
            if (!uploadBucket.tryConsume(len)) continue

            latencyQueue.enqueue(readBuffer.copyOf(len), config.latencyMs, config.jitterMs)
        }
    }

    private fun runDrain() {
        while (running.get()) {
            val packet = try {
                latencyQueue.poll(100)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            val protocol = extractProtocol(packet) ?: continue
            try {
                when (protocol) {
                    PROTO_UDP -> udpForwarder.forward(packet)
                    PROTO_TCP -> tcpForwarder.forward(packet)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Packet forward error (proto=$protocol)", e)
                ThrottleLogger.e(TAG, "Forward error proto=$protocol: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "PacketProcessor"
        private const val MAX_PACKET_SIZE = 32768
        const val PROTO_UDP = 17
        const val PROTO_TCP = 6

        fun extractProtocol(packet: ByteArray): Int? {
            if (packet.size < 20) return null
            val version = (packet[0].toInt() and 0xFF) ushr 4
            if (version != 4) return null
            return when (val proto = packet[9].toInt() and 0xFF) {
                PROTO_UDP, PROTO_TCP -> proto
                else -> null
            }
        }
    }
}
