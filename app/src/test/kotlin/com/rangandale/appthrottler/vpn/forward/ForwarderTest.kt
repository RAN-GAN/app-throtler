package com.rangandale.appthrottler.vpn.forward

import com.rangandale.appthrottler.vpn.forward.IpPacket.TCP_ACK
import com.rangandale.appthrottler.vpn.forward.IpPacket.TCP_FIN
import com.rangandale.appthrottler.vpn.forward.IpPacket.TCP_RST
import com.rangandale.appthrottler.vpn.forward.IpPacket.TCP_SYN
import com.rangandale.appthrottler.domain.model.AppRule
import com.rangandale.appthrottler.domain.model.ThrottleParams
import com.rangandale.appthrottler.vpn.flow.FlowOwners
import com.rangandale.appthrottler.vpn.flow.FlowPolicy
import com.rangandale.appthrottler.vpn.flow.FlowRegistry
import com.rangandale.appthrottler.vpn.flow.FlowState
import com.rangandale.appthrottler.vpn.flow.HostnameCache
import com.rangandale.appthrottler.vpn.throttle.ShaperSet
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Drives the forwarders the way the kernel would — hand-built IP packets in, captured IP
 * packets out — against real sockets on the loopback interface.
 */
class ForwarderTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val serverIp = 0x7F000001
    private val clientIp = 0x0A000002
    private val clientPort = 40_000

    private val tunPackets = LinkedBlockingQueue<Segment>()
    private val tun = TunWriter(object : OutputStream() {
        override fun write(b: Int) = throw UnsupportedOperationException()
        override fun write(b: ByteArray, off: Int, len: Int) {
            tunPackets.add(Segment.parse(b.copyOfRange(off, off + len)))
        }
    })

    private val closeables = ArrayList<AutoCloseable>()
    private var tcp: TcpForwarder? = null
    private var udp: UdpForwarder? = null

    private val appUid = 10_123
    private val owners = FlowOwners { appUid }

    @Before
    fun setUp() {
        FlowRegistry.reset()
    }

    @After
    fun tearDown() {
        tcp?.stop()
        udp?.stop()
        closeables.forEach { runCatching { it.close() } }
    }

    /** One parsed packet written to the TUN device. */
    private class Segment(
        val raw: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val seq: Int,
        val ack: Int,
        val flags: Int,
        val window: Int,
        val options: ByteArray,
        val payload: ByteArray,
    ) {
        fun has(flag: Int) = flags and flag != 0

        companion object {
            fun parse(raw: ByteArray): Segment {
                val bb = ByteBuffer.wrap(raw)
                if (raw[9].toInt() == IpPacket.PROTO_UDP) {
                    return Segment(
                        raw, bb.getShort(20).toInt() and 0xFFFF, bb.getShort(22).toInt() and 0xFFFF,
                        0, 0, 0, 0, ByteArray(0), raw.copyOfRange(28, raw.size),
                    )
                }
                val headerLen = ((raw[32].toInt() and 0xF0) ushr 4) * 4
                return Segment(
                    raw = raw,
                    srcPort = bb.getShort(20).toInt() and 0xFFFF,
                    dstPort = bb.getShort(22).toInt() and 0xFFFF,
                    seq = bb.getInt(24),
                    ack = bb.getInt(28),
                    flags = raw[33].toInt() and 0xFF,
                    window = bb.getShort(34).toInt() and 0xFFFF,
                    options = raw.copyOfRange(40, 20 + headerLen),
                    payload = raw.copyOfRange(20 + headerLen, raw.size),
                )
            }
        }
    }

    private fun startTcp(downloadKbps: Int = 0, policy: FlowPolicy = FlowPolicy()): TcpForwarder =
        TcpForwarder({ true }, tun, ShaperSet(ThrottleParams(downloadSpeedKbps = downloadKbps)), policy, owners, mtu = 1500).also {
            tcp = it
            it.start()
        }

    private fun startUdp(params: ThrottleParams = ThrottleParams(), policy: FlowPolicy = FlowPolicy()): UdpForwarder =
        UdpForwarder({ true }, tun, ShaperSet(params), policy, owners, mtu = 1500).also {
            udp = it
            it.start()
        }

    private fun blockAll(vararg domains: String, blockApp: Boolean = false) = FlowPolicy(
        rulesByUid = if (blockApp) mapOf(appUid to AppRule("com.example.app", block = true)) else emptyMap(),
        blockedDomains = domains.toList(),
    ).also { it.onWifi = true }

    /** A DNS message with one question for [name]; with [address] it is a reply carrying one A record. */
    private fun dnsMessage(name: String, address: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, if (address != null) 0x81.toByte() else 0x01, if (address != null) 0x80.toByte() else 0x00))
        out.write(byteArrayOf(0, 1, 0, if (address != null) 1 else 0, 0, 0, 0, 0))
        name.split('.').forEach { label -> out.write(label.length); out.write(label.toByteArray()) }
        out.write(byteArrayOf(0, 0, 1, 0, 1)) // root, type A, class IN
        if (address != null) {
            out.write(byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4)) // pointer to the question name
            out.write(address)
        }
        return out.toByteArray()
    }

    private fun listen(): ServerSocket = ServerSocket(0, 10, loopback).also { closeables.add(it) }

    private fun nextPacket(timeoutMs: Long = 3_000): Segment =
        tunPackets.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw AssertionError("no packet from forwarder")

    private fun TcpForwarder.fromClient(
        serverPort: Int,
        seq: Int,
        ack: Int,
        flags: Int,
        data: ByteArray = ByteArray(0),
        window: Int = 65_535,
        options: ByteArray = ByteArray(0),
    ) = forward(
        IpPacket.buildTcp(
            srcIp = clientIp, srcPort = clientPort, dstIp = serverIp, dstPort = serverPort,
            seq = seq, ack = ack, flags = flags, window = window, options = options, data = data,
        ),
    )

    /** Completes the three-way handshake and returns the forwarder's first data sequence number. */
    private fun TcpForwarder.handshake(server: ServerSocket, clientIsn: Int, window: Int = 65_535): Pair<Socket, Int> {
        val mss = byteArrayOf(2, 4, 0x05, 0xB4.toByte())
        fromClient(server.localPort, seq = clientIsn, ack = 0, flags = TCP_SYN, window = window, options = mss)
        val socket = server.accept().also { closeables.add(it); it.soTimeout = 3_000 }

        val synAck = nextPacket()
        assertEquals(TCP_SYN or TCP_ACK, synAck.flags)
        assertEquals(clientIsn + 1, synAck.ack)
        assertEquals(server.localPort, synAck.srcPort)
        assertEquals(clientPort, synAck.dstPort)
        assertEquals(1460, TcpForwarder.parseMss(synAck.options, 0, synAck.options.size))
        assertEquals(0, IpPacket.checksum(synAck.raw, 0, 20))

        fromClient(server.localPort, seq = clientIsn + 1, ack = synAck.seq + 1, flags = TCP_ACK, window = window)
        return socket to synAck.seq + 1
    }

    private fun Socket.readExactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = getInputStream().read(out, read, count - read)
            if (n < 0) throw AssertionError("stream ended after $read of $count bytes")
            read += n
        }
        return out
    }

    @Test
    fun `tcp relays data both ways and closes cleanly`() {
        val forwarder = startTcp()
        val server = listen()
        val port = server.localPort
        val (socket, serverSeq) = forwarder.handshake(server, clientIsn = 1_000)

        // client → server
        forwarder.fromClient(port, seq = 1_001, ack = serverSeq, flags = TCP_ACK, data = "hello".toByteArray())
        assertEquals(1_006, nextPacket().ack)
        assertEquals("hello", String(socket.readExactly(5)))

        // server → client, larger than one segment
        val download = ByteArray(5_000) { (it % 251).toByte() }
        socket.getOutputStream().write(download)
        val received = ByteArrayOutputStream()
        var expectedSeq = serverSeq
        while (received.size() < download.size) {
            val segment = nextPacket()
            assertTrue("segment exceeds MSS", segment.payload.size in 1..1460)
            assertEquals(expectedSeq, segment.seq)
            assertEquals(1_006, segment.ack)
            received.write(segment.payload)
            expectedSeq += segment.payload.size
            forwarder.fromClient(port, seq = 1_006, ack = expectedSeq, flags = TCP_ACK)
        }
        assertArrayEquals(download, received.toByteArray())

        // client half-closes; the server can still answer before closing itself
        forwarder.fromClient(port, seq = 1_006, ack = expectedSeq, flags = TCP_FIN or TCP_ACK)
        assertEquals(1_007, nextPacket().ack)
        assertEquals(-1, socket.getInputStream().read())

        socket.getOutputStream().write("bye".toByteArray())
        val bye = nextPacket()
        assertEquals("bye", String(bye.payload))
        socket.close()
        val fin = nextPacket()
        assertTrue(fin.has(TCP_FIN))
        assertEquals(expectedSeq + 3, fin.seq)
        assertEquals(1_007, fin.ack)

        // the final ACK goes to a forgotten connection and must not be answered with a reset
        forwarder.fromClient(port, seq = 1_007, ack = fin.seq + 1, flags = TCP_ACK)
        assertNull(tunPackets.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `tcp accepts only in-order client data`() {
        val forwarder = startTcp()
        val server = listen()
        val port = server.localPort
        val (socket, serverSeq) = forwarder.handshake(server, clientIsn = -5) // sequence space wraps

        // a segment that skips ahead (its predecessor was "lost") is not delivered
        forwarder.fromClient(port, seq = 1, ack = serverSeq, flags = TCP_ACK, data = "WORLD".toByteArray())
        assertEquals(-4, nextPacket().ack)

        // the retransmission fills the gap
        forwarder.fromClient(port, seq = -4, ack = serverSeq, flags = TCP_ACK, data = "hello".toByteArray())
        assertEquals(1, nextPacket().ack)

        // overlapping retransmit: only the new tail is delivered
        forwarder.fromClient(port, seq = -4, ack = serverSeq, flags = TCP_ACK, data = "helloWORLD".toByteArray())
        assertEquals(6, nextPacket().ack)

        // pure duplicate
        forwarder.fromClient(port, seq = 1, ack = serverSeq, flags = TCP_ACK, data = "WORLD".toByteArray())
        assertEquals(6, nextPacket().ack)

        forwarder.fromClient(port, seq = 6, ack = serverSeq, flags = TCP_ACK, data = "!".toByteArray())
        assertEquals(7, nextPacket().ack)
        assertEquals("helloWORLD!", String(socket.readExactly(11)))
    }

    @Test
    fun `tcp honours the client's receive window and probes when it stays closed`() {
        val forwarder = startTcp()
        val server = listen()
        val port = server.localPort
        val (socket, serverSeq) = forwarder.handshake(server, clientIsn = 0, window = 2_000)

        socket.getOutputStream().write(ByteArray(10_000))

        var inFlight = 0
        while (inFlight < 2_000) inFlight += nextPacket().payload.size
        assertEquals(2_000, inFlight)

        // Nothing more until the client ACKs — except a probe that re-sends an acknowledged byte position.
        val probe = nextPacket(timeoutMs = 3_000)
        assertEquals(0, probe.payload.size)
        assertEquals(serverSeq + 2_000 - 1, probe.seq)

        // The client opens the window again.
        forwarder.fromClient(port, seq = 1, ack = serverSeq + 2_000, flags = TCP_ACK, window = 3_000)
        var more = 0
        while (more < 3_000) {
            val segment = nextPacket()
            if (segment.payload.isEmpty()) continue // a probe that raced the ACK
            assertEquals(serverSeq + 2_000 + more, segment.seq)
            more += segment.payload.size
        }
        assertEquals(3_000, more)
    }

    @Test
    fun `tcp download is paced by the token bucket`() {
        val forwarder = startTcp(downloadKbps = 160) // 20,000 bytes per second
        val server = listen()
        val port = server.localPort
        val (socket, serverSeq) = forwarder.handshake(server, clientIsn = 0)

        thread(isDaemon = true) { runCatching { socket.getOutputStream().write(ByteArray(200_000)) } }

        var received = 0
        var segments = 0
        val deadline = System.nanoTime() + 2_000_000_000L
        while (true) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) break
            val segment = tunPackets.poll(remainingMs, TimeUnit.MILLISECONDS) ?: break
            received += segment.payload.size
            segments++
            forwarder.fromClient(port, seq = 1, ack = serverSeq + received, flags = TCP_ACK)
        }
        // 2 s at 20 kB/s plus the initial burst; generous bounds keep this stable on slow machines.
        assertTrue("received $received bytes in 2 s", received in 30_000..52_000)
        // Throttling must not degrade into a stream of tiny segments.
        assertTrue("$segments segments for $received bytes", received / segments >= 1_000)
    }

    @Test
    fun `tcp resets the client when the server refuses the connection`() {
        val forwarder = startTcp()
        val closedPort = ServerSocket(0, 1, loopback).use { it.localPort }

        forwarder.fromClient(closedPort, seq = 77, ack = 0, flags = TCP_SYN)
        val reset = nextPacket()
        assertTrue(reset.has(TCP_RST))
        assertEquals(78, reset.ack)
    }

    @Test
    fun `tcp resets data for a connection it does not know`() {
        val forwarder = startTcp()

        forwarder.fromClient(9, seq = 500, ack = 900, flags = TCP_ACK, data = "stale".toByteArray())
        val reset = nextPacket()
        assertTrue(reset.has(TCP_RST))
        assertEquals(900, reset.seq)

        // ...but stays silent for a bare ACK
        forwarder.fromClient(9, seq = 500, ack = 900, flags = TCP_ACK)
        assertNull(tunPackets.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `tcp tells the client when the server resets mid-stream`() {
        val forwarder = startTcp()
        val server = listen()
        val (socket, _) = forwarder.handshake(server, clientIsn = 0)

        socket.setSoLinger(true, 0) // close() now sends RST instead of FIN
        socket.close()
        assertTrue(nextPacket().has(TCP_RST))
    }

    @Test
    fun `tcp flows are recorded with their owner and traffic`() {
        val forwarder = startTcp()
        val server = listen()
        val port = server.localPort
        val (socket, serverSeq) = forwarder.handshake(server, clientIsn = 0)

        forwarder.fromClient(port, seq = 1, ack = serverSeq, flags = TCP_ACK, data = "GET / HTTP/1.1\r\nHost: Example.org\r\n\r\n".toByteArray())
        nextPacket()
        val open = FlowRegistry.snapshot().single()
        assertEquals(FlowState.OPEN, open.state)
        assertEquals(appUid, open.uid)
        assertEquals(port, open.remotePort)
        assertEquals("example.org", open.hostname)
        assertTrue(open.bytesUp > 0 && open.bytesDown > 0)

        socket.setSoLinger(true, 0)
        socket.close()
        assertTrue(nextPacket().has(TCP_RST))
        assertEquals(FlowState.FAILED, FlowRegistry.snapshot().single().state)
        assertEquals(open.bytesUp + open.bytesDown, FlowRegistry.appTotals().single().let { it.bytesUp + it.bytesDown })
    }

    @Test
    fun `tcp refuses a blocked app and counts the attempts`() {
        val forwarder = startTcp(policy = blockAll(blockApp = true))
        val server = listen().also { it.soTimeout = 300 }

        repeat(2) {
            forwarder.fromClient(server.localPort, seq = 10, ack = 0, flags = TCP_SYN)
            val reset = nextPacket()
            assertTrue(reset.has(TCP_RST))
            assertEquals(11, reset.ack)
        }
        assertTrue(runCatching { server.accept() }.isFailure) // the real server was never contacted

        val blocked = FlowRegistry.snapshot().single()
        assertEquals(FlowState.BLOCKED, blocked.state)
        assertEquals(2, blocked.attempts)
        assertEquals(2L, FlowRegistry.totalBlockedAttempts)
    }

    @Test
    fun `tcp block rule depends on network type and screen state`() {
        val policy = FlowPolicy(mapOf(appUid to AppRule("app", block = true, blockOnWifi = false, onlyWhenScreenOff = true)))
        policy.onMobile = true
        assertEquals(false, policy.blocks(appUid)) // screen is on
        policy.screenOn = false
        assertEquals(true, policy.blocks(appUid))
        policy.onMobile = false
        policy.onWifi = true
        assertEquals(false, policy.blocks(appUid)) // Wi-Fi is allowed
        assertEquals(false, policy.blocks(FlowOwners.UNKNOWN_UID))
    }

    @Test
    fun `tcp resets a connection to a blocked domain before any data reaches the server`() {
        val forwarder = startTcp(policy = blockAll("example.org"))
        val server = listen()
        val (socket, serverSeq) = forwarder.handshake(server, clientIsn = 0)

        val request = "GET / HTTP/1.1\r\nHost: ads.example.org\r\n\r\n".toByteArray()
        forwarder.fromClient(server.localPort, seq = 1, ack = serverSeq, flags = TCP_ACK, data = request)
        assertTrue(nextPacket().has(TCP_RST))
        assertEquals(-1, socket.getInputStream().read()) // closed without receiving a byte

        val flow = FlowRegistry.snapshot().single()
        assertEquals(FlowState.BLOCKED, flow.state)
        assertEquals("ads.example.org", flow.hostname)
    }

    @Test
    fun `encrypted dns is refused only while domain rules exist`() {
        val forwarder = startTcp(policy = blockAll("tracker.test"))
        forwarder.fromClient(853, seq = 5, ack = 0, flags = TCP_SYN)
        assertTrue(nextPacket().has(TCP_RST))
        assertEquals(FlowState.BLOCKED, FlowRegistry.snapshot().single().state)
        assertEquals(false, FlowPolicy().refusesEncryptedDns)
    }

    @Test
    fun `dns query for a blocked domain is answered locally with nxdomain`() {
        val server = DatagramSocket(0, loopback).also { closeables.add(it); it.soTimeout = 300 }
        val forwarder = startUdp(policy = blockAll("tracker.test"))

        val query = dnsMessage("cdn.tracker.test")
        // Port 53 is what makes it DNS; nothing listens there on loopback, and nothing must be sent.
        forwarder.forward(IpPacket.buildUdp(clientIp, clientPort, serverIp, 53, query, query.size, mtu = 1500).single())

        val reply = nextPacket()
        assertEquals(53, reply.srcPort)
        assertEquals(clientPort, reply.dstPort)
        assertEquals(0x12, reply.payload[0].toInt())
        assertEquals(0x34, reply.payload[1].toInt())
        assertTrue(reply.payload[2].toInt() and 0x80 != 0) // a response
        assertEquals(3, reply.payload[3].toInt() and 0x0F) // NXDOMAIN
        assertEquals("cdn.tracker.test", FlowRegistry.snapshot().single().hostname)
        assertTrue(runCatching { server.receive(DatagramPacket(ByteArray(8), 8)) }.isFailure)
    }

    @Test
    fun `udp drops datagrams of a blocked app`() {
        val server = DatagramSocket(0, loopback).also { closeables.add(it); it.soTimeout = 300 }
        val forwarder = startUdp(policy = blockAll(blockApp = true))

        val data = "ping".toByteArray()
        forwarder.forward(IpPacket.buildUdp(clientIp, clientPort, serverIp, server.localPort, data, data.size, mtu = 1500).single())
        assertTrue(runCatching { server.receive(DatagramPacket(ByteArray(8), 8)) }.isFailure)
        assertEquals(FlowState.BLOCKED, FlowRegistry.snapshot().single().state)
    }

    @Test
    fun `udp relays a datagram and its reply`() {
        val server = DatagramSocket(0, loopback).also { closeables.add(it); it.soTimeout = 3_000 }
        val forwarder = startUdp()

        val query = "ping".toByteArray()
        forwarder.forward(
            IpPacket.buildUdp(clientIp, clientPort, serverIp, server.localPort, query, query.size, mtu = 1500).single(),
        )
        val request = DatagramPacket(ByteArray(64), 64)
        server.receive(request)
        assertEquals("ping", String(request.data, 0, request.length))

        val answer = ByteArray(3_000) { it.toByte() } // forces fragmentation on the way back
        server.send(DatagramPacket(answer, answer.size, request.socketAddress))

        val reassembled = ByteArray(8 + answer.size)
        var bytes = 0
        while (bytes < reassembled.size) {
            val fragment = nextPacket().raw
            assertNotNull(fragment)
            assertTrue(fragment.size <= 1500)
            val offset = ((ByteBuffer.wrap(fragment).getShort(6).toInt() and 0x1FFF)) * 8
            System.arraycopy(fragment, 20, reassembled, offset, fragment.size - 20)
            bytes += fragment.size - 20
        }
        val header = ByteBuffer.wrap(reassembled)
        assertEquals(server.localPort, header.getShort(0).toInt() and 0xFFFF)
        assertEquals(clientPort, header.getShort(2).toInt() and 0xFFFF)
        assertArrayEquals(answer, reassembled.copyOfRange(8, reassembled.size))
    }

    @Test
    fun `udp with full packet loss delivers nothing back`() {
        val server = DatagramSocket(0, loopback).also { closeables.add(it); it.soTimeout = 3_000 }
        val forwarder = startUdp(ThrottleParams(packetLossPercent = 100f))

        val query = "ping".toByteArray()
        forwarder.forward(
            IpPacket.buildUdp(clientIp, clientPort, serverIp, server.localPort, query, query.size, mtu = 1500).single(),
        )
        val request = DatagramPacket(ByteArray(64), 64)
        server.receive(request)
        server.send(DatagramPacket(query, query.size, request.socketAddress))
        assertNull(tunPackets.poll(500, TimeUnit.MILLISECONDS))
    }
}
