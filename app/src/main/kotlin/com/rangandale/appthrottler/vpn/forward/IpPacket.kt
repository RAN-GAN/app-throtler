package com.rangandale.appthrottler.vpn.forward

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Parsing helpers and builders for the IPv4 and IPv6 packets exchanged with the TUN device.
 *
 * Addresses are passed around as `Long` handles so flow keys stay cheap to hash and compare:
 * an IPv4 address is its 32-bit value, an IPv6 address is an id (above the IPv4 range) under
 * which its 16 bytes are remembered for the life of the process.
 */
object IpPacket {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val IP_HEADER_LEN = 20
    const val IP6_HEADER_LEN = 40
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

    private const val MAX_IPV4 = 0xFFFFFFFFL
    private const val NEXT_HEADER_FRAGMENT = 44
    private const val FRAGMENT_HEADER_LEN = 8

    private data class V6(val high: Long, val low: Long)

    private val v6Handles = ConcurrentHashMap<V6, Long>()
    private val v6Bytes = ConcurrentHashMap<Long, ByteArray>()
    private val nextV6Handle = AtomicLong(MAX_IPV4 + 1)

    fun isV6(address: Long): Boolean = address > MAX_IPV4

    /** The handle for the 16-byte IPv6 address at [offset]. */
    fun v6Handle(data: ByteArray, offset: Int): Long {
        val bb = ByteBuffer.wrap(data, offset, 16)
        val key = V6(bb.long, bb.long)
        return v6Handles.getOrPut(key) {
            val handle = nextV6Handle.getAndIncrement()
            v6Bytes[handle] = data.copyOfRange(offset, offset + 16)
            handle
        }
    }

    fun addressBytes(address: Long): ByteArray =
        if (isV6(address)) v6Bytes.getValue(address) else intToBytes(address.toInt())

    fun versionOf(packet: ByteArray): Int = (packet[0].toInt() and 0xFF) ushr 4

    /** Length of the IP header in front of the transport header. */
    fun headerLength(packet: ByteArray): Int =
        if (versionOf(packet) == 6) IP6_HEADER_LEN else (packet[0].toInt() and 0x0F) * 4

    /** Length of the whole packet according to its header. */
    fun totalLength(packet: ByteArray): Int =
        if (versionOf(packet) == 6) IP6_HEADER_LEN + u16(packet, 4) else u16(packet, 2)

    fun sourceOf(packet: ByteArray): Long =
        if (versionOf(packet) == 6) v6Handle(packet, 8) else ByteBuffer.wrap(packet).getInt(12).toLong() and MAX_IPV4

    fun destinationOf(packet: ByteArray): Long =
        if (versionOf(packet) == 6) v6Handle(packet, 24) else ByteBuffer.wrap(packet).getInt(16).toLong() and MAX_IPV4

    private fun u16(data: ByteArray, i: Int) = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)

    fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    fun addressOf(address: Long): InetAddress = InetAddress.getByAddress(addressBytes(address))

    /**
     * Transport protocol of an IP packet, or null if it is not a TCP/UDP packet we can forward.
     * IPv6 packets with extension headers (including fragments) are not forwarded.
     */
    fun protocolOf(packet: ByteArray): Int? {
        if (packet.size < IP_HEADER_LEN) return null
        val proto = when (versionOf(packet)) {
            4 -> packet[9].toInt() and 0xFF
            6 -> if (packet.size >= IP6_HEADER_LEN) packet[6].toInt() and 0xFF else return null
            else -> return null
        }
        return if (proto == PROTO_UDP || proto == PROTO_TCP) proto else null
    }

    /** Whether an IPv4 packet is a fragment. (IPv6 fragments are rejected by [protocolOf].) */
    fun isFragment(packet: ByteArray): Boolean {
        if (versionOf(packet) != 4) return false
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

    private fun pseudoHeaderSum(srcIp: Long, dstIp: Long, protocol: Int, length: Int): Long {
        var sum = (protocol + (length ushr 16) + (length and 0xFFFF)).toLong()
        for (address in arrayOf(addressBytes(srcIp), addressBytes(dstIp))) {
            for (i in address.indices step 2) sum += u16(address, i)
        }
        return sum
    }

    /** Writes the IP header for a packet that carries [protocol] directly, and returns its length. */
    private fun writeHeader(packet: ByteArray, protocol: Int, srcIp: Long, dstIp: Long, id: Int, flagsAndOffset: Int): Int {
        if (!isV6(srcIp)) {
            writeIpHeader(packet, protocol, srcIp.toInt(), dstIp.toInt(), id, flagsAndOffset)
            return IP_HEADER_LEN
        }
        writeIp6Header(packet, protocol, srcIp, dstIp)
        return IP6_HEADER_LEN
    }

    private fun writeIp6Header(packet: ByteArray, nextHeader: Int, srcIp: Long, dstIp: Long) {
        val bb = ByteBuffer.wrap(packet)
        bb.putInt(0x60000000) // version 6, no traffic class or flow label
        bb.putShort((packet.size - IP6_HEADER_LEN).toShort())
        bb.put(nextHeader.toByte())
        bb.put(TTL.toByte())
        bb.put(addressBytes(srcIp))
        bb.put(addressBytes(dstIp))
    }

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
        srcIp: Long,
        srcPort: Int,
        dstIp: Long,
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
        val ipLen = if (isV6(srcIp)) IP6_HEADER_LEN else IP_HEADER_LEN
        val packet = ByteArray(ipLen + tcpLen)
        writeHeader(packet, PROTO_TCP, srcIp, dstIp, ipId.getAndIncrement(), FLAG_DONT_FRAGMENT)

        val bb = ByteBuffer.wrap(packet, ipLen, tcpLen)
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

        val cs = checksum(packet, ipLen, tcpLen, pseudoHeaderSum(srcIp, dstIp, PROTO_TCP, tcpLen))
        packet[ipLen + 16] = (cs ushr 8).toByte()
        packet[ipLen + 17] = cs.toByte()
        return packet
    }

    /**
     * Builds the IP packet(s) carrying one UDP datagram. Datagrams that do not fit in [mtu]
     * are split into IP fragments, since a TUN write larger than the MTU is rejected.
     */
    fun buildUdp(
        srcIp: Long,
        srcPort: Int,
        dstIp: Long,
        dstPort: Int,
        data: ByteArray,
        dataLength: Int,
        mtu: Int,
    ): List<ByteArray> {
        if (isV6(srcIp)) return buildUdp6(srcIp, srcPort, dstIp, dstPort, data, dataLength, mtu)
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
            writeIpHeader(whole, PROTO_UDP, srcIp.toInt(), dstIp.toInt(), id, FLAG_DONT_FRAGMENT)
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
            writeIpHeader(fragment, PROTO_UDP, srcIp.toInt(), dstIp.toInt(), id, more or (offset / 8))
            fragments.add(fragment)
            offset += len
        }
        return fragments
    }

    private fun buildUdp6(
        srcIp: Long,
        srcPort: Int,
        dstIp: Long,
        dstPort: Int,
        data: ByteArray,
        dataLength: Int,
        mtu: Int,
    ): List<ByteArray> {
        val udpLen = UDP_HEADER_LEN + dataLength
        val whole = ByteArray(IP6_HEADER_LEN + udpLen)
        val bb = ByteBuffer.wrap(whole, IP6_HEADER_LEN, udpLen)
        bb.putShort(srcPort.toShort())
        bb.putShort(dstPort.toShort())
        bb.putShort(udpLen.toShort())
        bb.putShort(0)
        bb.put(data, 0, dataLength)
        var cs = checksum(whole, IP6_HEADER_LEN, udpLen, pseudoHeaderSum(srcIp, dstIp, PROTO_UDP, udpLen))
        if (cs == 0) cs = 0xFFFF // the checksum is mandatory over IPv6
        whole[IP6_HEADER_LEN + 6] = (cs ushr 8).toByte()
        whole[IP6_HEADER_LEN + 7] = cs.toByte()

        if (whole.size <= mtu) {
            writeIp6Header(whole, PROTO_UDP, srcIp, dstIp)
            return listOf(whole)
        }

        // IPv6 fragments carry a fragment extension header between the IP header and the data.
        val id = ipId.getAndIncrement()
        val maxPayload = (mtu - IP6_HEADER_LEN - FRAGMENT_HEADER_LEN) and 7.inv()
        val fragments = ArrayList<ByteArray>(udpLen / maxPayload + 1)
        var offset = 0
        while (offset < udpLen) {
            val len = minOf(maxPayload, udpLen - offset)
            val fragment = ByteArray(IP6_HEADER_LEN + FRAGMENT_HEADER_LEN + len)
            writeIp6Header(fragment, NEXT_HEADER_FRAGMENT, srcIp, dstIp)
            val more = if (offset + len < udpLen) 1 else 0
            ByteBuffer.wrap(fragment, IP6_HEADER_LEN, FRAGMENT_HEADER_LEN)
                .put(PROTO_UDP.toByte())
                .put(0)
                .putShort((offset or more).toShort()) // offset is a multiple of 8, so its low 3 bits are free for the flags
                .putInt(id)
            System.arraycopy(whole, IP6_HEADER_LEN + offset, fragment, IP6_HEADER_LEN + FRAGMENT_HEADER_LEN, len)
            fragments.add(fragment)
            offset += len
        }
        return fragments
    }

    private val EMPTY = ByteArray(0)
}
