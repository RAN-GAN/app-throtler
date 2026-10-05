package com.rangandale.appthrottler.vpn.forward

import android.util.Log
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.vpn.forward.IpPacket.TCP_ACK
import com.rangandale.appthrottler.vpn.forward.IpPacket.TCP_FIN
import com.rangandale.appthrottler.vpn.forward.IpPacket.TCP_PSH
import com.rangandale.appthrottler.vpn.forward.IpPacket.TCP_RST
import com.rangandale.appthrottler.vpn.forward.IpPacket.TCP_SYN
import com.rangandale.appthrottler.vpn.flow.FlowKey
import com.rangandale.appthrottler.vpn.flow.FlowOwners
import com.rangandale.appthrottler.vpn.flow.FlowPolicy
import com.rangandale.appthrottler.vpn.flow.FlowRecord
import com.rangandale.appthrottler.vpn.flow.FlowRegistry
import com.rangandale.appthrottler.vpn.flow.FlowState
import com.rangandale.appthrottler.vpn.flow.HostSniffer
import com.rangandale.appthrottler.vpn.throttle.PacketLossFilter
import com.rangandale.appthrottler.vpn.throttle.ShaperSet
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.CancelledKeyException
import java.nio.channels.ClosedSelectorException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Terminates the apps' TCP connections on the TUN side and relays their byte streams over
 * [SocketChannel]s that [protect] keeps outside the tunnel.
 *
 * The TUN side is a minimal TCP endpoint: it accepts only in-order data (the client's kernel
 * retransmits anything dropped by the loss filter), honours the client's receive window, and
 * advertises its own window from the amount of data still waiting to be written to the server.
 * Download throttling is plain backpressure — bytes are only read from the server socket when
 * the owning app's download bucket has tokens. Because data towards the client is never
 * retransmitted, download packet loss is not produced by dropping segments: a "lost" segment
 * instead stalls the connection for about as long as a real retransmission would take.
 *
 * Each connection is attributed to the app that opened it, which decides whether it is refused
 * ([FlowPolicy]) and which limits apply ([ShaperSet]), and is reported to the [FlowRegistry].
 *
 * Client packets arrive on the caller's thread via [forward]; server sockets are serviced by a
 * single selector thread. Per-connection state is guarded by the connection's monitor.
 */
class TcpForwarder(
    private val protect: (Socket) -> Boolean,
    private val tun: TunWriter,
    private val shapers: ShaperSet,
    private val policy: FlowPolicy,
    private val owners: FlowOwners,
    private val mtu: Int,
) {
    data class TcpKey(val srcIp: Long, val srcPort: Int, val dstIp: Long, val dstPort: Int)

    private enum class State {
        /** Waiting for the real server to accept; the client's SYN is not answered yet. */
        CONNECTING,
        ESTABLISHED,

        /** Client sent FIN; the server may still be sending. */
        CLIENT_CLOSED,

        /** Server closed and our FIN was sent; waiting for the client's FIN. */
        SERVER_CLOSED,
    }

    private class Connection(
        val key: TcpKey,
        val channel: SocketChannel,
        val clientIsn: Int,
        val mss: Int,
        var clientWindow: Int,
        val uid: Int,
        val record: FlowRecord,
    ) {
        var state = State.CONNECTING

        /** Whether the first client bytes were already inspected for a host name. */
        var sniffed = false

        /** Until when server data is held back to imitate a lost segment being retransmitted. */
        var lossStallUntilMs = 0L
        var closed = false

        /** Next sequence number expected from the client. */
        var rcvNxt = clientIsn + 1

        var iss = 0
        var sndNxt = 0
        var sndUna = 0

        /** Client data accepted (and ACKed) but not yet written to the server socket. */
        val pending = ArrayDeque<ByteBuffer>()
        var pendingBytes = 0
        var shutdownAfterFlush = false
        var lastAdvertisedWindow = MAX_WINDOW

        var selKey: SelectionKey? = null
        var interestOps = 0
        var lastActivityMs = System.currentTimeMillis()
        var lastProbeMs = 0L
    }

    private val maxSegment = mtu - IpPacket.IP_HEADER_LEN - IpPacket.TCP_HEADER_LEN
    private val running = AtomicBoolean(false)
    private val selector = Selector.open()
    private val connections = ConcurrentHashMap<TcpKey, Connection>()

    // Selection keys are only touched by the selector thread; other threads queue the
    // connection here and wake the selector up.
    private val interestUpdates = ConcurrentLinkedQueue<Connection>()
    private var selectorThread: Thread? = null

    fun start() {
        running.set(true)
        selectorThread = Thread(::runSelector, "throttler-tcp").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        selector.wakeup()
        selectorThread?.let { thread ->
            thread.interrupt()
            runCatching { thread.join(JOIN_TIMEOUT_MS) }
        }
        connections.values.forEach { conn ->
            runCatching { conn.channel.close() }
            FlowRegistry.close(conn.record)
        }
        connections.clear()
        interestUpdates.clear()
        runCatching { selector.close() }
    }

    /** Resets every open connection, as when the device loses its network. */
    fun resetAll() {
        for (conn in connections.values) synchronized(conn) { abort(conn) }
    }

    // ---------------------------------------------------------------------------------------
    // Client → server (caller's thread)
    // ---------------------------------------------------------------------------------------

    /** Handles one outbound TCP packet (over IPv4 or IPv6) read from the TUN device. */
    fun forward(packet: ByteArray) {
        val ihl = IpPacket.headerLength(packet)
        if (ihl < IpPacket.IP_HEADER_LEN || packet.size < ihl + IpPacket.TCP_HEADER_LEN) return
        val bb = ByteBuffer.wrap(packet)
        val totalLen = minOf(IpPacket.totalLength(packet), packet.size)
        val headerLen = ((packet[ihl + 12].toInt() and 0xF0) ushr 4) * 4
        val dataStart = ihl + headerLen
        if (headerLen < IpPacket.TCP_HEADER_LEN || dataStart > totalLen) return

        val key = TcpKey(
            srcIp = IpPacket.sourceOf(packet),
            srcPort = bb.getShort(ihl).toInt() and 0xFFFF,
            dstIp = IpPacket.destinationOf(packet),
            dstPort = bb.getShort(ihl + 2).toInt() and 0xFFFF,
        )
        val seq = bb.getInt(ihl + 4)
        val ack = bb.getInt(ihl + 8)
        val flags = packet[ihl + 13].toInt() and 0xFF
        val window = bb.getShort(ihl + 14).toInt() and 0xFFFF
        val dataLen = totalLen - dataStart

        if (flags and TCP_RST != 0) {
            // Clients routinely abandon connections this way (e.g. a browser leaving a page).
            connections[key]?.let { conn -> synchronized(conn) { close(conn) } }
            return
        }
        if (flags and TCP_SYN != 0 && flags and TCP_ACK == 0) {
            val clientMss = parseMss(packet, ihl + IpPacket.TCP_HEADER_LEN, dataStart)
            // The larger IPv6 header leaves less room for data in each segment.
            handleSyn(key, seq, window, minOf(clientMss, mtu - ihl - IpPacket.TCP_HEADER_LEN))
            return
        }

        val conn = connections[key]
        if (conn == null) {
            // Nothing to say to a stray ACK, but anything else must be reset so the client
            // does not keep retransmitting into a connection we no longer know.
            if (dataLen > 0 || flags and TCP_FIN != 0) rejectUnknown(key, seq, ack, flags, dataLen)
            return
        }
        conn.record.addUp(packet.size)
        synchronized(conn) { handleSegment(conn, seq, ack, flags, window, packet, dataStart, dataLen) }
    }

    private fun handleSyn(key: TcpKey, clientIsn: Int, window: Int, mss: Int) {
        connections[key]?.let { existing ->
            synchronized(existing) {
                if (!existing.closed && existing.clientIsn == clientIsn) {
                    // Retransmitted SYN: answer again if the handshake already completed.
                    if (existing.state != State.CONNECTING) sendSynAck(existing)
                    return
                }
                close(existing)
            }
        }

        val uid = owners.resolve(FlowKey(IpPacket.PROTO_TCP, key.srcIp, key.srcPort, key.dstIp, key.dstPort))
        val encryptedDns = key.dstPort == DNS_OVER_TLS_PORT && policy.refusesEncryptedDns
        if (encryptedDns || policy.blocks(uid)) {
            val label = if (encryptedDns) "encrypted DNS (refused so domain rules can apply)" else null
            FlowRegistry.blocked(IpPacket.PROTO_TCP, uid, key.srcPort, key.dstIp, key.dstPort, hostname = label)
            sendReset(key, seq = 0, ack = clientIsn + 1, flags = TCP_RST or TCP_ACK)
            return
        }

        var channel: SocketChannel? = null
        try {
            channel = SocketChannel.open()
            channel.configureBlocking(false)
            channel.socket().tcpNoDelay = true
            if (!protect(channel.socket())) throw IOException("protect() failed")
            channel.connect(InetSocketAddress(IpPacket.addressOf(key.dstIp), key.dstPort))
        } catch (e: Exception) {
            channel?.runCatching { close() }
            if (running.get()) ThrottleLogger.e(TAG, "TCP connect to ${describe(key)} failed: ${e.message}")
            sendReset(key, seq = 0, ack = clientIsn + 1, flags = TCP_RST or TCP_ACK)
            return
        }

        val record = FlowRegistry.open(IpPacket.PROTO_TCP, uid, key.srcPort, key.dstIp, key.dstPort)
        val conn = Connection(key, channel, clientIsn, mss, window, uid, record)
        connections[key] = conn
        requestInterestUpdate(conn)
    }

    private fun handleSegment(
        conn: Connection,
        seq: Int,
        ack: Int,
        flags: Int,
        window: Int,
        packet: ByteArray,
        dataStart: Int,
        dataLen: Int,
    ) {
        if (conn.closed || conn.state == State.CONNECTING) return
        conn.lastActivityMs = System.currentTimeMillis()

        if (flags and TCP_ACK != 0) {
            // Sequence numbers wrap, so compare through signed differences.
            if (ack - conn.sndUna > 0 && ack - conn.sndNxt <= 0) conn.sndUna = ack
            conn.clientWindow = window
        }

        var replyWithAck = false
        val clientDone = conn.state == State.CLIENT_CLOSED

        if (dataLen > 0) {
            replyWithAck = true
            // Bytes at the start of this segment that were already received. Anything else
            // (a gap, or a pure duplicate) is answered with a duplicate ACK only.
            val alreadyReceived = conn.rcvNxt - seq
            if (!clientDone && alreadyReceived >= 0 && alreadyReceived < dataLen) {
                val accepted = minOf(dataLen - alreadyReceived, MAX_WINDOW - conn.pendingBytes)
                if (accepted > 0) {
                    if (!conn.sniffed) {
                        conn.sniffed = true
                        if (refusedByHostname(conn, packet, dataStart + alreadyReceived, accepted)) return
                    }
                    if (!writeToServer(conn, packet, dataStart + alreadyReceived, accepted)) return
                    conn.rcvNxt += accepted
                }
            }
        }

        if (flags and TCP_FIN != 0) {
            replyWithAck = true
            if (!clientDone && seq + dataLen == conn.rcvNxt) {
                conn.rcvNxt += 1
                if (conn.state == State.SERVER_CLOSED) {
                    sendAck(conn)
                    close(conn)
                    return
                }
                conn.state = State.CLIENT_CLOSED
                if (conn.pendingBytes == 0) shutdownOutput(conn) else conn.shutdownAfterFlush = true
            }
        }

        if (replyWithAck) sendAck(conn)
        if (desiredInterest(conn) != conn.interestOps) requestInterestUpdate(conn)
    }

    /**
     * Labels the flow with the host named in the client's first bytes (TLS SNI or HTTP Host),
     * and resets it if that host is a blocked domain — before anything reaches the server.
     */
    private fun refusedByHostname(conn: Connection, packet: ByteArray, offset: Int, length: Int): Boolean {
        val host = HostSniffer.hostOf(packet, offset, length) ?: return false
        conn.record.hostname = host
        if (!policy.blocksDomain(host)) return false
        abort(conn, FlowState.BLOCKED)
        return true
    }

    /** Writes client data to the server socket, queueing what the socket does not take. */
    private fun writeToServer(conn: Connection, packet: ByteArray, offset: Int, length: Int): Boolean {
        val data = ByteBuffer.wrap(packet, offset, length)
        if (conn.pending.isEmpty()) {
            try {
                conn.channel.write(data)
            } catch (e: IOException) {
                abort(conn)
                return false
            }
        }
        if (data.hasRemaining()) {
            conn.pendingBytes += data.remaining()
            conn.pending.add(data)
        }
        return true
    }

    private fun rejectUnknown(key: TcpKey, seq: Int, ack: Int, flags: Int, dataLen: Int) {
        if (flags and TCP_ACK != 0) {
            sendReset(key, seq = ack, ack = 0, flags = TCP_RST)
        } else {
            val consumed = dataLen + (if (flags and TCP_FIN != 0) 1 else 0)
            sendReset(key, seq = 0, ack = seq + consumed, flags = TCP_RST or TCP_ACK)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Server → client (selector thread)
    // ---------------------------------------------------------------------------------------

    private fun runSelector() {
        val buf = ByteBuffer.allocate(maxSegment * SEGMENTS_PER_READ)
        var lastHousekeepingMs = System.currentTimeMillis()
        while (running.get()) {
            try {
                applyInterestUpdates()
                selector.select(SELECT_TIMEOUT_MS)
                if (!running.get()) break

                var outOfTokens = false
                val iter = selector.selectedKeys().iterator()
                while (iter.hasNext()) {
                    val selKey = iter.next()
                    iter.remove()
                    val conn = selKey.attachment() as Connection
                    synchronized(conn) {
                        try {
                            if (!conn.closed && selKey.isValid) {
                                if (selKey.isConnectable) onConnectable(conn)
                                if (!conn.closed && selKey.isWritable) onWritable(conn)
                                if (!conn.closed && selKey.isReadable && !onReadable(conn, buf)) outOfTokens = true
                                if (!conn.closed) syncInterest(conn)
                            }
                        } catch (_: CancelledKeyException) {
                            // closed concurrently from the client side
                        }
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastHousekeepingMs >= HOUSEKEEPING_INTERVAL_MS) {
                    lastHousekeepingMs = now
                    housekeeping(now)
                }
                // Readable sockets stay selected while the download bucket is empty; pause
                // briefly instead of spinning until tokens are available again.
                if (outOfTokens) Thread.sleep(OUT_OF_TOKENS_SLEEP_MS)
            } catch (_: InterruptedException) {
                break
            } catch (_: ClosedSelectorException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "TCP selector error", e)
            }
        }
    }

    private fun applyInterestUpdates() {
        while (true) {
            val conn = interestUpdates.poll() ?: break
            synchronized(conn) {
                if (!conn.closed) {
                    try {
                        syncInterest(conn)
                    } catch (_: CancelledKeyException) {
                        // closed concurrently
                    }
                }
            }
        }
    }

    private fun onConnectable(conn: Connection) {
        val connected = try {
            conn.channel.finishConnect()
        } catch (e: IOException) {
            if (running.get()) ThrottleLogger.w(TAG, "TCP connect to ${describe(conn.key)} failed: ${e.message}")
            sendReset(conn.key, seq = 0, ack = conn.rcvNxt, flags = TCP_RST or TCP_ACK)
            close(conn, FlowState.FAILED)
            return
        }
        if (connected) establish(conn)
    }

    private fun establish(conn: Connection) {
        conn.iss = Random.nextInt()
        conn.sndNxt = conn.iss + 1
        conn.sndUna = conn.sndNxt
        conn.state = State.ESTABLISHED
        conn.lastActivityMs = System.currentTimeMillis()
        sendSynAck(conn)
        ThrottleLogger.d(TAG, "TCP established → ${describe(conn.key)}")
    }

    /** Flushes queued client data. */
    private fun onWritable(conn: Connection) {
        try {
            while (true) {
                val head = conn.pending.peek() ?: break
                conn.pendingBytes -= conn.channel.write(head)
                if (head.hasRemaining()) break
                conn.pending.poll()
            }
        } catch (e: IOException) {
            abort(conn)
            return
        }
        if (conn.pendingBytes == 0 && conn.shutdownAfterFlush) {
            conn.shutdownAfterFlush = false
            shutdownOutput(conn)
        }
        // Tell the client once a meaningful amount of receive window has opened up again.
        val window = receiveWindow(conn)
        val reopened = conn.lastAdvertisedWindow < conn.mss && window >= conn.mss
        if (reopened || window - conn.lastAdvertisedWindow >= MAX_WINDOW / 2) sendAck(conn)
    }

    /** Relays server data to the client. Returns false if the download bucket ran dry. */
    private fun onReadable(conn: Connection, buf: ByteBuffer): Boolean {
        if (conn.state == State.SERVER_CLOSED) return true
        val room = sendRoom(conn)
        if (room <= 0) return true // syncInterest() pauses reads until the client ACKs

        val shaper = shapers.forUid(conn.uid)
        val now = System.currentTimeMillis()
        // Offline, or waiting out a simulated retransmission: leave the data in the socket.
        if (shaper.isOffline || now < conn.lossStallUntilMs) return false
        val downloadBucket = shaper.download

        // Wait until the bucket covers a full segment: at low rates, reading whatever trickles
        // in would produce tiny segments whose headers outweigh their payload.
        val granted = downloadBucket.consumeUpTo(minOf(room, buf.capacity()), minBytes = conn.mss)
        if (granted <= 0) return false
        // ...and keep to whole segments, leaving the remainder in the bucket for the next one.
        val allowed = if (granted > conn.mss) granted - granted % conn.mss else granted
        downloadBucket.refund(granted - allowed)

        buf.clear()
        buf.limit(allowed)
        val read = try {
            conn.channel.read(buf)
        } catch (e: IOException) {
            downloadBucket.refund(allowed)
            abort(conn)
            return true
        }
        downloadBucket.refund(allowed - read.coerceAtLeast(0))

        if (read < 0) {
            onServerClosed(conn)
        } else if (read > 0) {
            conn.lastActivityMs = System.currentTimeMillis()
            val data = buf.array()
            var offset = 0
            while (offset < read) {
                val len = minOf(conn.mss, read - offset)
                val last = offset + len == read
                send(conn, if (last) TCP_PSH or TCP_ACK else TCP_ACK, data, offset, len)
                conn.sndNxt += len
                offset += len
                if (PacketLossFilter.shouldDrop(shaper.params.packetLossPercent)) {
                    conn.lossStallUntilMs = now + maxOf(MIN_LOSS_STALL_MS, 2L * shaper.params.latencyMs)
                }
            }
        }
        return true
    }

    private fun onServerClosed(conn: Connection) {
        send(conn, TCP_FIN or TCP_ACK)
        conn.sndNxt += 1
        if (conn.state == State.CLIENT_CLOSED) {
            close(conn)
        } else {
            conn.state = State.SERVER_CLOSED
        }
    }

    private fun housekeeping(now: Long) {
        for (conn in connections.values) {
            synchronized(conn) {
                if (conn.closed || conn.state == State.CONNECTING) return@synchronized
                // A rule can start to apply mid-connection (screen turned off, network changed).
                if (policy.blocks(conn.uid)) {
                    abort(conn, FlowState.BLOCKED)
                    return@synchronized
                }
                val idleMs = now - conn.lastActivityMs
                val timeoutMs = if (conn.state == State.ESTABLISHED) IDLE_TIMEOUT_MS else HALF_CLOSED_TIMEOUT_MS
                if (idleMs > timeoutMs) {
                    abort(conn)
                    return@synchronized
                }
                // The ACK that reopens a full client window may have been dropped by the loss
                // filter. Probe with an already-acknowledged sequence number so the client
                // repeats its current ACK and window.
                if (conn.state != State.SERVER_CLOSED && sendRoom(conn) <= 0 && now - conn.lastProbeMs >= PROBE_INTERVAL_MS) {
                    conn.lastProbeMs = now
                    emit(conn, buildPacket(conn, seq = conn.sndNxt - 1, flags = TCP_ACK))
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shared helpers (connection monitor held)
    // ---------------------------------------------------------------------------------------

    /** Bytes the client can still accept beyond what is already in flight. */
    private fun sendRoom(conn: Connection): Int = conn.clientWindow - (conn.sndNxt - conn.sndUna)

    private fun receiveWindow(conn: Connection): Int = (MAX_WINDOW - conn.pendingBytes).coerceAtLeast(0)

    private fun desiredInterest(conn: Connection): Int {
        if (conn.state == State.CONNECTING) return SelectionKey.OP_CONNECT
        var ops = 0
        if (conn.state != State.SERVER_CLOSED && sendRoom(conn) > 0) ops = ops or SelectionKey.OP_READ
        if (conn.pendingBytes > 0) ops = ops or SelectionKey.OP_WRITE
        return ops
    }

    private fun requestInterestUpdate(conn: Connection) {
        interestUpdates.add(conn)
        selector.wakeup()
    }

    /** Selector thread only. */
    private fun syncInterest(conn: Connection) {
        val selKey = conn.selKey
        if (selKey == null) {
            val ops = desiredInterest(conn)
            try {
                conn.selKey = conn.channel.register(selector, ops, conn)
                conn.interestOps = ops
            } catch (e: IOException) {
                abort(conn)
                return
            }
            // connect() can complete immediately (e.g. loopback), in which case OP_CONNECT never fires
            if (conn.state == State.CONNECTING && conn.channel.isConnected) {
                establish(conn)
                syncInterest(conn)
            }
            return
        }
        val ops = desiredInterest(conn)
        if (ops != conn.interestOps && selKey.isValid) {
            selKey.interestOps(ops)
            conn.interestOps = ops
        }
    }

    private fun shutdownOutput(conn: Connection) {
        try {
            conn.channel.socket().shutdownOutput()
        } catch (_: IOException) {
            // server already gone; the read side will notice
        }
    }

    private fun sendSynAck(conn: Connection) {
        val mssOption = byteArrayOf(2, 4, (conn.mss ushr 8).toByte(), conn.mss.toByte())
        emit(conn, buildPacket(conn, seq = conn.iss, flags = TCP_SYN or TCP_ACK, options = mssOption))
    }

    private fun sendAck(conn: Connection) = send(conn, TCP_ACK)

    private fun send(conn: Connection, flags: Int, data: ByteArray = NO_DATA, offset: Int = 0, length: Int = 0) {
        emit(conn, buildPacket(conn, conn.sndNxt, flags, NO_DATA, data, offset, length))
    }

    private fun emit(conn: Connection, packet: ByteArray) {
        tun.deliver(packet, conn.uid, shapers.forUid(conn.uid))
        conn.record.addDown(packet.size)
    }

    private fun buildPacket(
        conn: Connection,
        seq: Int,
        flags: Int,
        options: ByteArray = NO_DATA,
        data: ByteArray = NO_DATA,
        offset: Int = 0,
        length: Int = 0,
    ): ByteArray {
        val window = receiveWindow(conn)
        conn.lastAdvertisedWindow = window
        return IpPacket.buildTcp(
            srcIp = conn.key.dstIp, srcPort = conn.key.dstPort,
            dstIp = conn.key.srcIp, dstPort = conn.key.srcPort,
            seq = seq, ack = conn.rcvNxt,
            flags = flags, window = window,
            options = options,
            data = data, dataOffset = offset, dataLength = length,
        )
    }

    private fun sendReset(key: TcpKey, seq: Int, ack: Int, flags: Int) {
        tun.write(
            IpPacket.buildTcp(
                srcIp = key.dstIp, srcPort = key.dstPort,
                dstIp = key.srcIp, dstPort = key.srcPort,
                seq = seq, ack = ack, flags = flags, window = 0,
            ),
        )
    }

    /** Resets the client side and drops the connection. */
    private fun abort(conn: Connection, state: FlowState = FlowState.FAILED) {
        if (conn.closed) return
        if (conn.state == State.CONNECTING) {
            sendReset(conn.key, seq = 0, ack = conn.rcvNxt, flags = TCP_RST or TCP_ACK)
        } else {
            sendReset(conn.key, seq = conn.sndNxt, ack = conn.rcvNxt, flags = TCP_RST or TCP_ACK)
        }
        close(conn, state)
    }

    private fun close(conn: Connection, state: FlowState = FlowState.CLOSED) {
        if (conn.closed) return
        conn.closed = true
        connections.remove(conn.key, conn)
        FlowRegistry.close(conn.record, state)
        conn.pending.clear()
        conn.pendingBytes = 0
        // Closing the channel also cancels its selection key.
        conn.channel.runCatching { close() }
    }

    private fun describe(key: TcpKey): String =
        "${IpPacket.addressOf(key.dstIp).hostAddress}:${key.dstPort}"

    companion object {
        private const val TAG = "TcpForwarder"

        /** No window scaling is negotiated, so 64 KiB is the most either side can advertise. */
        private const val MAX_WINDOW = 65_535
        private const val DEFAULT_MSS = 536
        private const val DNS_OVER_TLS_PORT = 853
        private const val SEGMENTS_PER_READ = 16
        private const val SELECT_TIMEOUT_MS = 250L
        private const val OUT_OF_TOKENS_SLEEP_MS = 5L

        /** A lost segment costs at least a fast retransmit, even on a zero-latency link. */
        private const val MIN_LOSS_STALL_MS = 200L
        private const val HOUSEKEEPING_INTERVAL_MS = 1_000L
        private const val PROBE_INTERVAL_MS = 1_000L
        private const val IDLE_TIMEOUT_MS = 60 * 60_000L
        private const val HALF_CLOSED_TIMEOUT_MS = 2 * 60_000L
        private const val JOIN_TIMEOUT_MS = 500L
        private val NO_DATA = ByteArray(0)

        /** Reads the MSS option from a SYN's option block, falling back to the TCP default. */
        internal fun parseMss(packet: ByteArray, optionsStart: Int, optionsEnd: Int): Int {
            var i = optionsStart
            while (i < optionsEnd) {
                when (val kind = packet[i].toInt() and 0xFF) {
                    0 -> return DEFAULT_MSS
                    1 -> i += 1
                    else -> {
                        if (i + 1 >= optionsEnd) return DEFAULT_MSS
                        val len = packet[i + 1].toInt() and 0xFF
                        if (len < 2 || i + len > optionsEnd) return DEFAULT_MSS
                        if (kind == 2 && len == 4) {
                            return ((packet[i + 2].toInt() and 0xFF) shl 8) or (packet[i + 3].toInt() and 0xFF)
                        }
                        i += len
                    }
                }
            }
            return DEFAULT_MSS
        }
    }
}
