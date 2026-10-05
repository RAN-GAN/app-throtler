package com.rangandale.appthrottler.vpn.forward

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** Parsing helpers and builders for the IPv4 packets exchanged with the TUN device. */
object IpPacket {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val IP_HEADER_LEN = 20
    const val TCP_HEADER_LEN = 20
    const val UDP_HEADER_LEN = 8

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    private const val FLAG_DONT_FRAGMENT = 0x4000
    private const val FLAG_MORE_FRAGMENTS = 0x2000
    private const val FRAGMENT_OFFSET_MASK = 0x1FFF
    private const val TTL = 64

    private val ipId = AtomicInteger(0)

    fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    fun addressOf(ip: Int): InetAddress = InetAddress.getByAddress(intToBytes(ip))

    /** Transport protocol of an IPv4 packet, or null if it is not a TCP/UDP packet we can forward. */
    fun protocolOf(packet: ByteArray): Int? {
        if (packet.size < IP_HEADER_LEN) return null
        if ((packet[0].toInt() and 0xFF) ushr 4 != 4) return null
        return when (val proto = packet[9].toInt() and 0xFF) {
            PROTO_UDP, PROTO_TCP -> proto
            else -> null
        }
    }

    fun isFragment(packet: ByteArray): Boolean {
        val flagsAndOffset = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
        return flagsAndOffset and (FLAG_MORE_FRAGMENTS or FRAGMENT_OFFSET_MASK) != 0
    }

    /** Internet checksum over [length] bytes, seeded with [initial] (e.g. a pseudo-header sum). */
    fun checksum(data: ByteArray, offset: Int, length: Int, initial: Long = 0L): Int {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.toInt().inv() and 0xFFFF
    }

    private fun pseudoHeaderSum(srcIp: Int, dstIp: Int, protocol: Int, length: Int): Long =
        (srcIp ushr 16).toLong() + (srcIp and 0xFFFF) +
            (dstIp ushr 16).toLong() + (dstIp and 0xFFFF) +
            protocol + length

    private fun writeIpHeader(
        packet: ByteArray,
        protocol: Int,
        srcIp: Int,
        dstIp: Int,
        id: Int,
        flagsAndOffset: Int,
    ) {
        val bb = ByteBuffer.wrap(packet)
        bb.put(0x45.toByte())
        bb.put(0)
        bb.putShort(packet.size.toShort())
        bb.putShort(id.toShort())
        bb.putShort(flagsAndOffset.toShort())
        bb.put(TTL.toByte())
        bb.put(protocol.toByte())
        bb.putShort(0)
        bb.putInt(srcIp)
        bb.putInt(dstIp)
        val cs = checksum(packet, 0, IP_HEADER_LEN)
        packet[10] = (cs ushr 8).toByte()
        packet[11] = cs.toByte()
    }

    fun buildTcp(
        srcIp: Int,
        srcPort: Int,
        dstIp: Int,
        dstPort: Int,
        seq: Int,
        ack: Int,
        flags: Int,
        window: Int,
        options: ByteArray = EMPTY,
        data: ByteArray = EMPTY,
        dataOffset: Int = 0,
        dataLength: Int = data.size,
    ): ByteArray {
        require(options.size % 4 == 0) { "TCP options must be padded to 32-bit words" }
        val tcpHeaderLen = TCP_HEADER_LEN + options.size
        val tcpLen = tcpHeaderLen + dataLength
        val packet = ByteArray(IP_HEADER_LEN + tcpLen)
        writeIpHeader(packet, PROTO_TCP, srcIp, dstIp, ipId.getAndIncrement(), FLAG_DONT_FRAGMENT)

        val bb = ByteBuffer.wrap(packet, IP_HEADER_LEN, tcpLen)
        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putInt(seq)
        bb.putInt(ack)
        bb.put(((tcpHeaderLen / 4) shl 4).toByte())
        bb.put(flags.toByte())
        bb.putShort(window.coerceIn(0, 0xFFFF).toShort())
        bb.putShort(0) // checksum
        bb.putShort(0) // urgent pointer
        bb.put(options)
        bb.put(data, dataOffset, dataLength)

        val cs = checksum(packet, IP_HEADER_LEN, tcpLen, pseudoHeaderSum(srcIp, dstIp, PROTO_TCP, tcpLen))
        packet[IP_HEADER_LEN + 16] = (cs ushr 8).toByte()
        packet[IP_HEADER_LEN + 17] = cs.toByte()
        return packet
    }

    /**
     * Builds the IPv4 packet(s) carrying one UDP datagram. Datagrams that do not fit in [mtu]
     * are split into IP fragments, since a TUN write larger than the MTU is rejected.
     */
    fun buildUdp(
        srcIp: Int,
        srcPort: Int,
        dstIp: Int,
        dstPort: Int,
        data: ByteArray,
        dataLength: Int,
        mtu: Int,
    ): List<ByteArray> {
        val udpLen = UDP_HEADER_LEN + dataLength
        val whole = ByteArray(IP_HEADER_LEN + udpLen)
        val bb = ByteBuffer.wrap(whole, IP_HEADER_LEN, udpLen)
        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putShort(udpLen.toShort())
        bb.putShort(0)
        bb.put(data, 0, dataLength)
        var cs = checksum(whole, IP_HEADER_LEN, udpLen, pseudoHeaderSum(srcIp, dstIp, PROTO_UDP, udpLen))
        if (cs == 0) cs = 0xFFFF // 0 means "no checksum" for UDP
        whole[IP_HEADER_LEN + 6] = (cs ushr 8).toByte()
        whole[IP_HEADER_LEN + 7] = cs.toByte()

        val id = ipId.getAndIncrement()
        if (whole.size <= mtu) {
            writeIpHeader(whole, PROTO_UDP, srcIp, dstIp, id, FLAG_DONT_FRAGMENT)
            return listOf(whole)
        }

        val maxPayload = (mtu - IP_HEADER_LEN) and 7.inv() // fragment offsets are in 8-byte units
        val fragments = ArrayList<ByteArray>(udpLen / maxPayload + 1)
        var offset = 0
        while (offset < udpLen) {
            val len = minOf(maxPayload, udpLen - offset)
            val fragment = ByteArray(IP_HEADER_LEN + len)
            System.arraycopy(whole, IP_HEADER_LEN + offset, fragment, IP_HEADER_LEN, len)
            val more = if (offset + len < udpLen) FLAG_MORE_FRAGMENTS else 0
            writeIpHeader(fragment, PROTO_UDP, srcIp, dstIp, id, more or (offset / 8))
            fragments.add(fragment)
            offset += len
        }
        return fragments
    }

    private val EMPTY = ByteArray(0)
}
