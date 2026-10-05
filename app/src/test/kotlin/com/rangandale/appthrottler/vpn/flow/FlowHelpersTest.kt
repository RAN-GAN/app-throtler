package com.rangandale.appthrottler.vpn.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class FlowHelpersTest {

    /** A minimal TLS 1.2 ClientHello carrying [serverName] in its SNI extension. */
    private fun clientHello(serverName: String): ByteArray {
        val name = serverName.toByteArray()
        val sni = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0)) // extension type: server_name
            writeU16(name.size + 5)
            writeU16(name.size + 3)
            write(0) // host_name
            writeU16(name.size)
            write(name)
        }.toByteArray()
        val otherExtension = byteArrayOf(0, 0x0B, 0, 2, 1, 0) // ec_point_formats, placed before SNI

        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(3, 3)) // client version
            write(ByteArray(32)) // random
            write(4); write(ByteArray(4)) // session id
            writeU16(2); write(byteArrayOf(0x13, 0x01)) // cipher suites
            write(1); write(0) // compression methods
            writeU16(otherExtension.size + sni.size)
            write(otherExtension)
            write(sni)
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(0x16); write(byteArrayOf(3, 1)); writeU16(body.size + 4) // record header
            write(1); write(0); writeU16(body.size) // handshake header
            write(body)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value ushr 8)
        write(value)
    }

    @Test
    fun `sni is read from a tls client hello`() {
        val hello = clientHello("Api.Example.com")
        assertEquals("api.example.com", HostSniffer.hostOf(hello, 0, hello.size))

        // also when the hello sits at an offset inside a larger buffer
        val padded = ByteArray(7) + hello + ByteArray(3)
        assertEquals("api.example.com", HostSniffer.hostOf(padded, 7, hello.size))
    }

    @Test
    fun `truncated or foreign data yields no host`() {
        val hello = clientHello("example.com")
        for (cut in listOf(1, 20, 44, 60, hello.size - 3)) {
            assertNull("cut at $cut", HostSniffer.hostOf(hello, 0, cut))
        }
        assertNull(HostSniffer.hostOf(ByteArray(200) { 0x7F }, 0, 200))
        assertNull(HostSniffer.hostOf("SSH-2.0-OpenSSH_9.6 some banner".toByteArray(), 0, 31))
    }

    @Test
    fun `host header is read from plain http`() {
        val request = "POST /x HTTP/1.1\r\nUser-Agent: t\r\nhost: Example.org:8080\r\n\r\nbody".toByteArray()
        assertEquals("example.org", HostSniffer.hostOf(request, 0, request.size))
        val noHost = "GET / HTTP/1.0\r\nAccept: */*\r\n\r\n".toByteArray()
        assertNull(HostSniffer.hostOf(noHost, 0, noHost.size))
    }

    @Test
    fun `blocked domains match themselves and their subdomains only`() {
        val policy = FlowPolicy(blockedDomains = listOf(" *.Example.com ", "https://tracker.test/path", "", "bad domain"))
        assertTrue(policy.blocksDomain("example.com"))
        assertTrue(policy.blocksDomain("a.b.EXAMPLE.com."))
        assertTrue(policy.blocksDomain("tracker.test"))
        assertFalse(policy.blocksDomain("notexample.com"))
        assertFalse(policy.blocksDomain("example.com.evil.org"))
        assertFalse(policy.blocksDomain("com"))
        assertFalse(FlowPolicy().blocksDomain("example.com"))
    }

    @Test
    fun `dns reply maps the queried name to its addresses`() {
        // header, question "a.example.com A IN", then a CNAME and two A records using compression
        val reply = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 1, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 3, 0, 0, 0, 0))
            write(1); write("a".toByteArray()); write(7); write("example".toByteArray()); write(3); write("com".toByteArray()); write(0)
            write(byteArrayOf(0, 1, 0, 1))
            write(byteArrayOf(0xC0.toByte(), 12, 0, 5, 0, 1, 0, 0, 0, 9, 0, 4, 1, 'b'.code.toByte(), 0xC0.toByte(), 14)) // CNAME b.example.com
            write(byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 9, 0, 4, 93.toByte(), 184.toByte(), 216.toByte(), 34))
            write(byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 9, 0, 4, 10, 0, 0, 1))
        }.toByteArray()

        val parsed = DnsMessage.parseReply(reply, 0, reply.size)!!
        assertEquals("a.example.com", parsed.name)
        assertEquals(listOf(0x5DB8D822L, 0x0A000001L), parsed.addresses)
        assertEquals("a.example.com", DnsMessage.questionName(reply, 0, reply.size))

        assertNull(DnsMessage.parseReply(reply, 0, 10))
        // a truncated reply keeps what was readable instead of failing
        assertEquals(1, DnsMessage.parseReply(reply, 0, reply.size - 8)!!.addresses.size)
    }

    @Test
    fun `nxdomain keeps the question and clears the record counts`() {
        val query = byteArrayOf(0x55, 0x66, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1, 1, 'x'.code.toByte(), 0, 0, 1, 0, 1)
        val reply = DnsMessage.nxDomainFor(query, 0, query.size)
        assertEquals(query.size, reply.size)
        assertEquals(0x55, reply[0].toInt())
        assertEquals(0x81, reply[2].toInt() and 0xFF)
        assertEquals(3, reply[3].toInt() and 0x0F)
        assertEquals(1, reply[5].toInt()) // one question
        assertEquals(0, reply[11].toInt()) // additional records dropped
        assertEquals("x", DnsMessage.questionName(reply, 0, reply.size))
    }

    @Test
    fun `owners are looked up once per flow unless refreshed`() {
        var lookups = 0
        val owners = FlowOwners { lookups++; 42 }
        val key = FlowKey(6, 1, 2, 3, 4)
        assertEquals(42, owners.resolve(key))
        assertEquals(42, owners.resolve(key))
        assertEquals(1, lookups)
        owners.resolve(key, refresh = true)
        owners.forget(key)
        owners.resolve(key)
        assertEquals(3, lookups)
    }
}
