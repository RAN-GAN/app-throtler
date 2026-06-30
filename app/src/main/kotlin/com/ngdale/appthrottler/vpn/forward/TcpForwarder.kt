package com.rangandale.appthrottler.vpn.forward

import android.net.VpnService
import android.util.Log
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.vpn.throttle.TokenBucket
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TcpForwarder(
    private val vpnService: VpnService,
    private val tunOut: FileOutputStream,
    private val downloadBucket: TokenBucket,
) {
    private val running = AtomicBoolean(false)
    private val selector = Selector.open()
    private val sessions = ConcurrentHashMap<TcpKey, TcpConnection>()
    private var inboundThread: Thread? = null

    data class TcpKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

    enum class TcpState { SYN_RCVD, ESTABLISHED, FIN_WAIT }

    class TcpConnection(
        val channel: SocketChannel,
        val key: TcpKey,
        @Volatile var clientSeq: Long,
        @Volatile var serverSeq: Long,
        @Volatile var state: TcpState,
    )

    fun start() {
        running.set(true)
        inboundThread = Thread(::runInbound, "throttler-tcp-in").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        selector.wakeup()
        inboundThread?.interrupt()
        runCatching { selector.close() }
        sessions.values.forEach { runCatching { it.channel.close() } }
        sessions.clear()
    }

    fun forward(packet: ByteArray) {
        val bb = ByteBuffer.wrap(packet)
        val ihl = (packet[0].toInt() and 0x0F) * 4
        val totalLen = bb.getShort(2).toInt() and 0xFFFF
        val srcIp = bb.getInt(12)
        val dstIp = bb.getInt(16)
        val tcpOff = ihl
        val srcPort = bb.getShort(tcpOff).toInt() and 0xFFFF
        val dstPort = bb.getShort(tcpOff + 2).toInt() and 0xFFFF
        val seqNum = bb.getInt(tcpOff + 4).toLong() and 0xFFFFFFFFL
        val dataOffset = (packet[tcpOff + 12].toInt() and 0xF0) ushr 4
        val flags = packet[tcpOff + 13].toInt() and 0xFF
        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0
        val dataStart = ihl + dataOffset * 4
        val dataLen = totalLen - dataStart

        val key = TcpKey(srcIp, srcPort, dstIp, dstPort)

        when {
            isRst -> {
                sessions.remove(key)?.channel?.runCatching { close() }
            }
            isSyn && !isAck -> handleSyn(key, seqNum)
            isFin -> handleFin(key, seqNum, packet, dataStart, dataLen)
            dataLen > 0 -> handleData(key, seqNum, packet, dataStart, dataLen)
        }
    }

    private fun handleSyn(key: TcpKey, clientIsn: Long) {
        sessions.remove(key)?.channel?.runCatching { close() }

        val channel = try {
            SocketChannel.open().also { ch ->
                ch.configureBlocking(false)
                vpnService.protect(ch.socket())
                val dst = InetAddress.getByAddress(UdpForwarder.intToBytes(key.dstIp))
                ch.connect(InetSocketAddress(dst, key.dstPort))
                ThrottleLogger.d(TAG, "TCP SYN → ${dst.hostAddress}:${key.dstPort}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCP connect failed to ${key.dstIp}:${key.dstPort}", e)
            ThrottleLogger.e(TAG, "TCP connect failed: ${e.message}")
            sendRst(key, 0)
            return
        }

        val serverIsn = (System.currentTimeMillis() and 0xFFFFFFFFL)
        val conn = TcpConnection(
            channel = channel,
            key = key,
            clientSeq = (clientIsn + 1) and 0xFFFFFFFFL,
            serverSeq = (serverIsn + 1) and 0xFFFFFFFFL,
            state = TcpState.SYN_RCVD,
        )
        sessions[key] = conn

        // Send SYN+ACK — we optimistically accept the connection
        writeTun(buildTcpPacket(
            srcIp = key.dstIp, srcPort = key.dstPort,
            dstIp = key.srcIp, dstPort = key.srcPort,
            seq = serverIsn,
            ack = conn.clientSeq,
            flags = FLAG_SYN or FLAG_ACK,
            data = ByteArray(0),
        ))

        selector.wakeup()
        val ops = if (channel.isConnectionPending) SelectionKey.OP_CONNECT else SelectionKey.OP_READ
        try { channel.register(selector, ops, conn) } catch (e: Exception) {
            Log.e(TAG, "Register failed", e)
            sessions.remove(key)
            channel.runCatching { close() }
        }
        if (!channel.isConnectionPending) conn.state = TcpState.ESTABLISHED
    }

    private fun handleData(key: TcpKey, seqNum: Long, packet: ByteArray, dataStart: Int, dataLen: Int) {
        val conn = sessions[key] ?: return
        if (conn.state != TcpState.ESTABLISHED) return

        val data = packet.copyOfRange(dataStart, dataStart + dataLen)
        try {
            val buf = ByteBuffer.wrap(data)
            while (buf.hasRemaining()) {
                if (conn.channel.write(buf) == 0) break
            }
            conn.clientSeq = (seqNum + dataLen) and 0xFFFFFFFFL
            writeTun(buildTcpPacket(
                srcIp = key.dstIp, srcPort = key.dstPort,
                dstIp = key.srcIp, dstPort = key.srcPort,
                seq = conn.serverSeq,
                ack = conn.clientSeq,
                flags = FLAG_ACK,
                data = ByteArray(0),
            ))
        } catch (e: Exception) {
            Log.w(TAG, "TCP data forward failed", e)
            sessions.remove(key)?.channel?.runCatching { close() }
        }
    }

    private fun handleFin(key: TcpKey, seqNum: Long, packet: ByteArray, dataStart: Int, dataLen: Int) {
        val conn = sessions[key] ?: return
        if (dataLen > 0) handleData(key, seqNum, packet, dataStart, dataLen)
        conn.clientSeq = (seqNum + dataLen + 1) and 0xFFFFFFFFL
        writeTun(buildTcpPacket(
            srcIp = key.dstIp, srcPort = key.dstPort,
            dstIp = key.srcIp, dstPort = key.srcPort,
            seq = conn.serverSeq,
            ack = conn.clientSeq,
            flags = FLAG_FIN or FLAG_ACK,
            data = ByteArray(0),
        ))
        conn.serverSeq = (conn.serverSeq + 1) and 0xFFFFFFFFL
        conn.state = TcpState.FIN_WAIT
        sessions.remove(key)?.channel?.runCatching { close() }
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
                    if (!selKey.isValid) continue
                    val conn = selKey.attachment() as? TcpConnection ?: continue
                    when {
                        selKey.isConnectable -> onConnect(selKey, conn)
                        selKey.isReadable -> onRead(selKey, conn, buf)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "TCP inbound error", e)
            }
        }
    }

    private fun onConnect(selKey: SelectionKey, conn: TcpConnection) {
        try {
            (selKey.channel() as SocketChannel).finishConnect()
            conn.state = TcpState.ESTABLISHED
            selKey.interestOps(SelectionKey.OP_READ)
            ThrottleLogger.i(TAG, "TCP established → ${InetAddress.getByAddress(UdpForwarder.intToBytes(conn.key.dstIp)).hostAddress}:${conn.key.dstPort}")
        } catch (e: Exception) {
            Log.w(TAG, "TCP finishConnect failed", e)
            ThrottleLogger.e(TAG, "TCP connect failed: ${e.message}")
            sendRst(conn.key, conn.serverSeq)
            sessions.remove(conn.key)
            selKey.cancel()
            selKey.channel().runCatching { close() }
        }
    }

    private fun onRead(selKey: SelectionKey, conn: TcpConnection, buf: ByteBuffer) {
        buf.clear()
        val read = try { (selKey.channel() as SocketChannel).read(buf) } catch (e: Exception) { -1 }
        when {
            read < 0 -> {
                // Server closed connection
                writeTun(buildTcpPacket(
                    srcIp = conn.key.dstIp, srcPort = conn.key.dstPort,
                    dstIp = conn.key.srcIp, dstPort = conn.key.srcPort,
                    seq = conn.serverSeq,
                    ack = conn.clientSeq,
                    flags = FLAG_FIN or FLAG_ACK,
                    data = ByteArray(0),
                ))
                conn.serverSeq = (conn.serverSeq + 1) and 0xFFFFFFFFL
                sessions.remove(conn.key)
                selKey.cancel()
                selKey.channel().runCatching { close() }
            }
            read > 0 -> {
                if (!downloadBucket.tryConsume(read)) return
                buf.flip()
                val data = ByteArray(read).also { buf.get(it) }
                writeTun(buildTcpPacket(
                    srcIp = conn.key.dstIp, srcPort = conn.key.dstPort,
                    dstIp = conn.key.srcIp, dstPort = conn.key.srcPort,
                    seq = conn.serverSeq,
                    ack = conn.clientSeq,
                    flags = FLAG_PSH or FLAG_ACK,
                    data = data,
                ))
                conn.serverSeq = (conn.serverSeq + read) and 0xFFFFFFFFL
            }
        }
    }

    private fun sendRst(key: TcpKey, seq: Long) {
        writeTun(buildTcpPacket(
            srcIp = key.dstIp, srcPort = key.dstPort,
            dstIp = key.srcIp, dstPort = key.srcPort,
            seq = seq, ack = 0, flags = FLAG_RST, data = ByteArray(0),
        ))
    }

    private fun writeTun(packet: ByteArray) {
        synchronized(tunOut) { runCatching { tunOut.write(packet) } }
    }

    companion object {
        private const val TAG = "TcpForwarder"
        private const val MAX_PACKET = 32768
        const val FLAG_FIN = 0x01
        const val FLAG_SYN = 0x02
        const val FLAG_RST = 0x04
        const val FLAG_PSH = 0x08
        const val FLAG_ACK = 0x10

        fun buildTcpPacket(
            srcIp: Int, srcPort: Int,
            dstIp: Int, dstPort: Int,
            seq: Long, ack: Long,
            flags: Int,
            data: ByteArray,
        ): ByteArray {
            val tcpHdrLen = 20
            val ipHdrLen = 20
            val totalLen = ipHdrLen + tcpHdrLen + data.size
            val packet = ByteArray(totalLen)
            val bb = ByteBuffer.wrap(packet)

            // IP header
            bb.put(0x45.toByte())
            bb.put(0)
            bb.putShort(totalLen.toShort())
            bb.putShort(0)
            bb.putShort(0x4000.toShort())
            bb.put(64)
            bb.put(6)
            bb.putShort(0)
            bb.putInt(srcIp)
            bb.putInt(dstIp)
            val ipCs = UdpForwarder.computeChecksum(packet, 0, 20)
            packet[10] = (ipCs ushr 8).toByte()
            packet[11] = ipCs.toByte()

            // TCP header
            bb.putShort(srcPort.toShort())
            bb.putShort(dstPort.toShort())
            bb.putInt(seq.toInt())
            bb.putInt(ack.toInt())
            bb.put(((tcpHdrLen / 4) shl 4).toByte())
            bb.put(flags.toByte())
            bb.putShort(65535.toShort())
            bb.putShort(0)
            bb.putShort(0)
            bb.put(data)

            // TCP checksum via pseudo-header
            val tcpLen = tcpHdrLen + data.size
            val pseudo = ByteArray(12 + tcpLen)
            val ph = ByteBuffer.wrap(pseudo)
            ph.putInt(srcIp)
            ph.putInt(dstIp)
            ph.put(0)
            ph.put(6)
            ph.putShort(tcpLen.toShort())
            System.arraycopy(packet, ipHdrLen, pseudo, 12, tcpLen)
            val tcpCs = UdpForwarder.computeChecksum(pseudo, 0, pseudo.size)
            packet[ipHdrLen + 16] = (tcpCs ushr 8).toByte()
            packet[ipHdrLen + 17] = tcpCs.toByte()

            return packet
        }
    }
}
