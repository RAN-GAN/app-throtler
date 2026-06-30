package com.rangandale.appthrottler.vpn.forward

import android.net.VpnService
import android.util.Log
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.vpn.throttle.TokenBucket
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class UdpForwarder(
    private val vpnService: VpnService,
    private val tunOut: FileOutputStream,
    private val downloadBucket: TokenBucket,
) {
    private val running = AtomicBoolean(false)
    private val selector = Selector.open()
    private val sessions = ConcurrentHashMap<UdpKey, DatagramChannel>()
    private var inboundThread: Thread? = null

    data class UdpKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

    fun start() {
        running.set(true)
        inboundThread = Thread(::runInbound, "throttler-udp-in").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        selector.wakeup()
        inboundThread?.interrupt()
        runCatching { selector.close() }
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    fun forward(packet: ByteArray) {
        val bb = ByteBuffer.wrap(packet)
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val totalLen = bb.getShort(2).toInt() and 0xFFFF
        val srcIp = bb.getInt(12)
        val dstIp = bb.getInt(16)
        val srcPort = bb.getShort(ihl).toInt() and 0xFFFF
        val dstPort = bb.getShort(ihl + 2).toInt() and 0xFFFF
        val dataStart = ihl + 8
        val dataLen = totalLen - dataStart
        if (dataLen <= 0) return

        val key = UdpKey(srcIp, srcPort, dstIp, dstPort)
        val channel = sessions.getOrPut(key) { createChannel(key) ?: return }

        try {
            val data = ByteBuffer.wrap(packet, dataStart, dataLen)
            channel.write(data)
        } catch (e: Exception) {
            Log.w(TAG, "UDP send failed", e)
            sessions.remove(key)
            runCatching { channel.close() }
        }
    }

    private fun createChannel(key: UdpKey): DatagramChannel? {
        return try {
            val ch = DatagramChannel.open()
            ch.configureBlocking(false)
            vpnService.protect(ch.socket())
            val dst = InetAddress.getByAddress(intToBytes(key.dstIp))
            ch.connect(InetSocketAddress(dst, key.dstPort))
            ThrottleLogger.d(TAG, "UDP session ${dst.hostAddress}:${key.dstPort}")
            selector.wakeup()
            ch.register(selector, SelectionKey.OP_READ, key)
            ch
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create UDP channel", e)
            ThrottleLogger.e(TAG, "UDP session failed: ${e.message}")
            null
        }
    }

    private fun runInbound() {
        val buf = ByteBuffer.allocate(MAX_PACKET)
        while (running.get()) {
            try {
                selector.select(100)
                val iter = selector.selectedKeys().iterator()
                while (iter.hasNext()) {
                    val selKey = iter.next()
                    iter.remove()
                    if (!selKey.isValid || !selKey.isReadable) continue
                    val ch = selKey.channel() as DatagramChannel
                    val udpKey = selKey.attachment() as UdpKey
                    buf.clear()
                    val read = try { ch.read(buf) } catch (e: Exception) { -1 }
                    if (read <= 0) continue
                    if (!downloadBucket.tryConsume(read)) continue
                    buf.flip()
                    val data = ByteArray(read).also { buf.get(it) }
                    val response = buildUdpPacket(
                        srcIp = udpKey.dstIp,
                        srcPort = udpKey.dstPort,
                        dstIp = udpKey.srcIp,
                        dstPort = udpKey.srcPort,
                        data = data,
                    )
                    synchronized(tunOut) { tunOut.write(response) }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "UDP inbound error", e)
            }
        }
    }

    private fun buildUdpPacket(srcIp: Int, srcPort: Int, dstIp: Int, dstPort: Int, data: ByteArray): ByteArray {
        val udpLen = 8 + data.size
        val totalLen = 20 + udpLen
        val packet = ByteArray(totalLen)
        val bb = ByteBuffer.wrap(packet)

        bb.put(0x45.toByte())
        bb.put(0)
        bb.putShort(totalLen.toShort())
        bb.putShort(0)
        bb.putShort(0x4000.toShort())
        bb.put(64)
        bb.put(17)
        bb.putShort(0)
        bb.putInt(srcIp)
        bb.putInt(dstIp)

        val ipCs = computeChecksum(packet, 0, 20)
        packet[10] = (ipCs ushr 8).toByte()
        packet[11] = ipCs.toByte()

        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putShort(udpLen.toShort())
        bb.putShort(0)
        bb.put(data)

        return packet
    }

    companion object {
        private const val TAG = "UdpForwarder"
        private const val MAX_PACKET = 32768

        fun intToBytes(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

        fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
            var sum = 0
            var i = offset
            while (i < offset + length - 1) {
                sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                i += 2
            }
            if ((length and 1) != 0) {
                sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
            }
            while (sum ushr 16 != 0) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            return sum.inv() and 0xFFFF
        }
    }
}

