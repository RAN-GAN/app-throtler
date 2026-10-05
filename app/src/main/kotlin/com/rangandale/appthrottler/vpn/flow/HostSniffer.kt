package com.rangandale.appthrottler.vpn.flow

/**
 * Reads the destination host name out of the first bytes a client sends: the SNI extension of
 * a TLS ClientHello, or the Host header of a plain HTTP request. Nothing is decrypted.
 */
object HostSniffer {

    fun hostOf(data: ByteArray, offset: Int, length: Int): String? =
        tlsServerName(data, offset, length) ?: httpHost(data, offset, length)

    internal fun tlsServerName(data: ByteArray, offset: Int, length: Int): String? {
        val end = offset + length
        fun u8(i: Int) = data[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)

        // Record header (5) + handshake header (4) + version (2) + random (32)
        var i = offset
        if (length < 43 || u8(i) != 0x16 || u8(i + 5) != 0x01) return null
        i += 43
        if (i + 1 > end) return null
        i += 1 + u8(i) // session id
        if (i + 2 > end) return null
        i += 2 + u16(i) // cipher suites
        if (i + 1 > end) return null
        i += 1 + u8(i) // compression methods
        if (i + 2 > end) return null
        val extensionsEnd = minOf(end, i + 2 + u16(i))
        i += 2

        while (i + 4 <= extensionsEnd) {
            val type = u16(i)
            val size = u16(i + 2)
            i += 4
            if (type == 0) {
                // server_name list: length (2), name type (1), name length (2), name
                if (i + 5 > extensionsEnd || u8(i + 2) != 0) return null
                val nameLen = u16(i + 3)
                if (i + 5 + nameLen > extensionsEnd) return null
                return String(data, i + 5, nameLen, Charsets.US_ASCII).lowercase()
            }
            i += size
        }
        return null
    }

    private val HTTP_METHODS = listOf("GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ")

    internal fun httpHost(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 16) return null
        val text = String(data, offset, minOf(length, 2048), Charsets.ISO_8859_1)
        if (HTTP_METHODS.none { text.startsWith(it) }) return null
        val start = text.indexOf("\r\nhost:", ignoreCase = true)
        if (start < 0) return null
        val lineEnd = text.indexOf('\r', start + 2).let { if (it < 0) text.length else it }
        return text.substring(start + 7, lineEnd).trim().substringBefore(':').lowercase().takeIf { it.isNotEmpty() }
    }
}

/** Just enough DNS to learn names from replies and to answer blocked queries. */
object DnsMessage {
    private const val HEADER_LEN = 12
    private const val TYPE_A = 1

    class Reply(val name: String, val addresses: List<Int>)

    /** The name asked for in a query or reply, lower-cased. */
    fun questionName(data: ByteArray, offset: Int, length: Int): String? {
        if (length < HEADER_LEN + 5) return null
        if (u16(data, offset + 4) < 1) return null
        return readName(data, offset, offset + length, offset + HEADER_LEN)?.first
    }

    /** The queried name and the IPv4 addresses a reply maps it to. */
    fun parseReply(data: ByteArray, offset: Int, length: Int): Reply? {
        val end = offset + length
        if (length < HEADER_LEN + 5) return null
        if (data[offset + 2].toInt() and 0x80 == 0) return null // not a response
        val questions = u16(data, offset + 4)
        val answers = u16(data, offset + 6)
        if (questions < 1) return null

        val (name, afterName) = readName(data, offset, end, offset + HEADER_LEN) ?: return null
        var i = afterName + 4
        repeat(questions - 1) {
            i = (readName(data, offset, end, i) ?: return null).second + 4
        }

        val addresses = ArrayList<Int>(answers)
        repeat(answers) {
            i = (readName(data, offset, end, i) ?: return Reply(name, addresses)).second
            if (i + 10 > end) return Reply(name, addresses)
            val type = u16(data, i)
            val dataLen = u16(data, i + 8)
            i += 10
            if (i + dataLen > end) return Reply(name, addresses)
            if (type == TYPE_A && dataLen == 4) {
                addresses.add(
                    ((data[i].toInt() and 0xFF) shl 24) or ((data[i + 1].toInt() and 0xFF) shl 16) or
                        ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF),
                )
            }
            i += dataLen
        }
        return Reply(name, addresses)
    }

    /** Turns a query into a "no such domain" reply for the same question. */
    fun nxDomainFor(query: ByteArray, offset: Int, length: Int): ByteArray {
        val reply = query.copyOfRange(offset, offset + length)
        reply[2] = (reply[2].toInt() or 0x80).toByte() // QR: response
        reply[3] = 0x83.toByte() // recursion available, RCODE 3 (NXDOMAIN)
        // No answer, authority or additional records (also drops any EDNS record's count).
        for (i in 6 until HEADER_LEN) reply[i] = 0
        return reply
    }

    private fun u16(data: ByteArray, i: Int) = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)

    /** Reads a (possibly compressed) name; returns it with the offset just past it. */
    private fun readName(data: ByteArray, base: Int, end: Int, start: Int): Pair<String, Int>? {
        val name = StringBuilder()
        var i = start
        var next = -1
        var jumps = 0
        while (true) {
            if (i >= end) return null
            val len = data[i].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (next < 0) next = i + 1
                    return name.toString().lowercase() to next
                }
                len and 0xC0 == 0xC0 -> {
                    if (i + 1 >= end || ++jumps > 16) return null
                    if (next < 0) next = i + 2
                    i = base + (((len and 0x3F) shl 8) or (data[i + 1].toInt() and 0xFF))
                }
                else -> {
                    if (i + 1 + len > end) return null
                    if (name.isNotEmpty()) name.append('.')
                    name.append(String(data, i + 1, len, Charsets.US_ASCII))
                    i += 1 + len
                }
            }
        }
    }
}
