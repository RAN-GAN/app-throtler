package com.rangandale.appthrottler.vpn.forward

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class IpPacketTest {
    private val src = 0x08080808L // 8.8.8.8
    private val dst = 0x0A000002L // 10.0.0.2

    /** Checksum over [length] bytes plus the TCP/UDP pseudo header; 0 means the packet is valid. */
    private fun transportChecksum(packet: ByteArray, protocol: Int): Int {
        val length = packet.size - 20
        val pseudo = ByteBuffer.allocate(12 + length)
            .put(packet, 12, 8)
            .put(0)
            .put(protocol.toByte())
            .putShort(length.toShort())
            .put(packet, 20, length)
            .array()
        return IpPacket.checksum(pseudo, 0, pseudo.size)
    }

    @Test
    fun `tcp packet has valid header fields and checksums`() {
        val data = ByteArray(101) { it.toByte() } // odd length exercises checksum padding
        val packet = IpPacket.buildTcp(
            srcIp = src, srcPort = 443, dstIp = dst, dstPort = 50_000,
            seq = -2, ack = 7, flags = IpPacket.TCP_PSH or IpPacket.TCP_ACK, window = 65_535,
            data = data,
        )
        val bb = ByteBuffer.wrap(packet)

        assertEquals(20 + 20 + 101, packet.size)
        assertEquals(packet.size, bb.getShort(2).toInt() and 0xFFFF)
        assertEquals(IpPacket.PROTO_TCP, IpPacket.protocolOf(packet))
        assertFalse(IpPacket.isFragment(packet))
        assertEquals(src.toInt(), bb.getInt(12))
        assertEquals(dst.toInt(), bb.getInt(16))
        assertEquals(src, IpPacket.sourceOf(packet))
        assertEquals(dst, IpPacket.destinationOf(packet))
        assertEquals(443, bb.getShort(20).toInt() and 0xFFFF)
        assertEquals(50_000, bb.getShort(22).toInt() and 0xFFFF)
        assertEquals(-2, bb.getInt(24))
        assertEquals(7, bb.getInt(28))
        assertEquals(0xFFFF, bb.getShort(34).toInt() and 0xFFFF)
        assertArrayEquals(data, packet.copyOfRange(40, packet.size))
        assertEquals(0, IpPacket.checksum(packet, 0, 20))
        assertEquals(0, transportChecksum(packet, IpPacket.PROTO_TCP))
    }

    @Test
    fun `tcp options extend the header`() {
        val mss = byteArrayOf(2, 4, 0x05, 0xB4.toByte())
        val packet = IpPacket.buildTcp(
            srcIp = src, srcPort = 80, dstIp = dst, dstPort = 1234,
            seq = 1, ack = 2, flags = IpPacket.TCP_SYN or IpPacket.TCP_ACK, window = 1000,
            options = mss,
        )
        assertEquals(44, packet.size)
        assertEquals(6, (packet[32].toInt() and 0xF0) ushr 4) // data offset in 32-bit words
        assertEquals(1460, TcpForwarder.parseMss(packet, 40, 44))
        assertEquals(0, transportChecksum(packet, IpPacket.PROTO_TCP))
    }

    @Test
    fun `mss parser skips other options and tolerates garbage`() {
        // NOP, NOP, SACK-permitted, MSS 1400
        val options = byteArrayOf(1, 1, 4, 2, 2, 4, 0x05, 0x78)
        assertEquals(1400, TcpForwarder.parseMss(options, 0, options.size))
        assertEquals(536, TcpForwarder.parseMss(byteArrayOf(1, 1, 0, 0), 0, 4))
        assertEquals(536, TcpForwarder.parseMss(byteArrayOf(2, 0, 1, 1), 0, 4)) // zero-length option
        assertEquals(536, TcpForwarder.parseMss(byteArrayOf(8, 10, 1, 1), 0, 4)) // overruns the block
    }

    @Test
    fun `small udp datagram becomes a single packet`() {
        val data = ByteArray(33) { (it * 3).toByte() }
        val packets = IpPacket.buildUdp(src, 53, dst, 40_000, data, data.size, mtu = 1500)

        assertEquals(1, packets.size)
        val packet = packets.single()
        assertEquals(20 + 8 + 33, packet.size)
        assertEquals(IpPacket.PROTO_UDP, IpPacket.protocolOf(packet))
        assertFalse(IpPacket.isFragment(packet))
        assertEquals(0, IpPacket.checksum(packet, 0, 20))
        assertEquals(0, transportChecksum(packet, IpPacket.PROTO_UDP))
        assertArrayEquals(data, packet.copyOfRange(28, packet.size))
    }

    @Test
    fun `oversized udp datagram is fragmented and reassembles`() {
        val data = ByteArray(4000) { it.toByte() }
        val packets = IpPacket.buildUdp(src, 53, dst, 40_000, data, data.size, mtu = 1500)

        assertEquals(3, packets.size)
        val reassembled = ByteArray(8 + data.size)
        packets.forEachIndexed { index, packet ->
            assertTrue(packet.size <= 1500)
            assertEquals(0, IpPacket.checksum(packet, 0, 20))
            val bb = ByteBuffer.wrap(packet)
            assertEquals(bb.getShort(4), ByteBuffer.wrap(packets[0]).getShort(4)) // same IP id
            val flagsAndOffset = bb.getShort(6).toInt() and 0xFFFF
            assertEquals(index < packets.lastIndex, flagsAndOffset and 0x2000 != 0)
            assertEquals(index > 0 || packets.size > 1, IpPacket.isFragment(packet))
            System.arraycopy(packet, 20, reassembled, (flagsAndOffset and 0x1FFF) * 8, packet.size - 20)
        }
        assertArrayEquals(data, reassembled.copyOfRange(8, reassembled.size))

        val whole = ByteArray(20 + reassembled.size)
        System.arraycopy(packets[0], 0, whole, 0, 20)
        System.arraycopy(reassembled, 0, whole, 20, reassembled.size)
        assertEquals(0, transportChecksum(whole, IpPacket.PROTO_UDP))
    }

    private val src6 = IpPacket.v6Handle(java.net.InetAddress.getByName("2001:db8::1").address, 0)
    private val dst6 = IpPacket.v6Handle(java.net.InetAddress.getByName("fd00::2").address, 0)

    /** Checksum of the transport part of an IPv6 packet including its pseudo header; 0 means valid. */
    private fun transportChecksum6(packet: ByteArray, protocol: Int, transportOffset: Int = 40): Int {
        val length = packet.size - transportOffset
        val pseudo = ByteBuffer.allocate(40 + length)
            .put(packet, 8, 32)
            .putInt(length)
            .put(byteArrayOf(0, 0, 0, protocol.toByte()))
            .put(packet, transportOffset, length)
            .array()
        return IpPacket.checksum(pseudo, 0, pseudo.size)
    }

    @Test
    fun `ipv6 addresses get stable handles above the ipv4 range`() {
        val bytes = java.net.InetAddress.getByName("2001:db8::1").address
        assertEquals(src6, IpPacket.v6Handle(bytes, 0))
        assertTrue(IpPacket.isV6(src6))
        assertFalse(IpPacket.isV6(0xFFFFFFFFL))
        assertTrue(src6 != dst6)
        assertArrayEquals(bytes, IpPacket.addressBytes(src6))
        assertEquals("255.255.255.255", IpPacket.addressOf(0xFFFFFFFFL).hostAddress)
    }

    @Test
    fun `ipv6 tcp packet has a valid header and checksum`() {
        val data = ByteArray(77) { it.toByte() }
        val packet = IpPacket.buildTcp(
            srcIp = src6, srcPort = 443, dstIp = dst6, dstPort = 50_000,
            seq = 9, ack = 7, flags = IpPacket.TCP_ACK, window = 1_000, data = data,
        )
        assertEquals(40 + 20 + 77, packet.size)
        assertEquals(6, IpPacket.versionOf(packet))
        assertEquals(40, IpPacket.headerLength(packet))
        assertEquals(packet.size, IpPacket.totalLength(packet))
        assertEquals(IpPacket.PROTO_TCP, IpPacket.protocolOf(packet))
        assertFalse(IpPacket.isFragment(packet))
        assertEquals(src6, IpPacket.sourceOf(packet))
        assertEquals(dst6, IpPacket.destinationOf(packet))
        assertEquals(443, ByteBuffer.wrap(packet).getShort(40).toInt() and 0xFFFF)
        assertEquals(0, transportChecksum6(packet, IpPacket.PROTO_TCP))
    }

    @Test
    fun `ipv6 udp datagram is built whole or as fragments with an extension header`() {
        val small = IpPacket.buildUdp(src6, 53, dst6, 40_000, ByteArray(100) { 7 }, 100, mtu = 1500).single()
        assertEquals(40 + 8 + 100, small.size)
        assertEquals(IpPacket.PROTO_UDP, IpPacket.protocolOf(small))
        assertEquals(0, transportChecksum6(small, IpPacket.PROTO_UDP))

        val data = ByteArray(4000) { it.toByte() }
        val fragments = IpPacket.buildUdp(src6, 53, dst6, 40_000, data, data.size, mtu = 1500)
        assertEquals(3, fragments.size)
        val reassembled = ByteArray(8 + data.size)
        fragments.forEachIndexed { index, fragment ->
            assertTrue(fragment.size <= 1500)
            assertEquals(fragment.size, IpPacket.totalLength(fragment))
            assertEquals(44, fragment[6].toInt()) // next header: fragment
            assertEquals(null, IpPacket.protocolOf(fragment)) // never mistaken for a plain UDP packet
            val bb = ByteBuffer.wrap(fragment)
            assertEquals(IpPacket.PROTO_UDP, fragment[40].toInt())
            val offsetAndFlags = bb.getShort(42).toInt() and 0xFFFF
            assertEquals(index < fragments.lastIndex, offsetAndFlags and 1 == 1)
            assertEquals(ByteBuffer.wrap(fragments[0]).getInt(44), bb.getInt(44)) // same identification
            System.arraycopy(fragment, 48, reassembled, offsetAndFlags and 0xFFF8, fragment.size - 48)
        }
        assertArrayEquals(data, reassembled.copyOfRange(8, reassembled.size))
        val whole = ByteArray(40 + reassembled.size)
        System.arraycopy(fragments[0], 0, whole, 0, 40)
        System.arraycopy(reassembled, 0, whole, 40, reassembled.size)
        assertEquals(0, transportChecksum6(whole, IpPacket.PROTO_UDP))
    }

    @Test
    fun `unsupported protocols are rejected`() {
        val ipv6 = ByteArray(40).also { it[0] = 0x60; it[6] = 58 } // ICMPv6
        val icmp = ByteArray(28).also { it[0] = 0x45; it[9] = 1 }
        assertEquals(null, IpPacket.protocolOf(ipv6))
        assertEquals(null, IpPacket.protocolOf(icmp))
        assertEquals(null, IpPacket.protocolOf(ByteArray(10)))
    }
}
