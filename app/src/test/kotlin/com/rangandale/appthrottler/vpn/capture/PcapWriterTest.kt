package com.rangandale.appthrottler.vpn.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcapWriterTest {

    @Test
    fun `file has a raw-ip pcap header followed by timestamped records`() {
        val out = ByteArrayOutputStream()
        val writer = PcapWriter(out)
        val first = ByteArray(40) { it.toByte() }
        val second = ByteArray(3) { 9 }
        writer.write(timestampMs = 1_700_000_000_123, packet = first)
        writer.write(timestampMs = 1_700_000_001_000, packet = second)
        writer.close()

        val file = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24 + 16 + 40 + 16 + 3, file.remaining())
        assertEquals(0xA1B2C3D4.toInt(), file.int)
        assertEquals(2, file.short.toInt())
        assertEquals(4, file.short.toInt())
        file.position(20)
        assertEquals(PcapWriter.LINKTYPE_RAW, file.int)

        assertEquals(1_700_000_000, file.int)
        assertEquals(123_000, file.int)
        assertEquals(40, file.int)
        assertEquals(40, file.int)
        val data = ByteArray(40).also { file.get(it) }
        assertArrayEquals(first, data)

        assertEquals(1_700_000_001, file.int)
        assertEquals(0, file.int)
        assertEquals(3, file.int)
    }

    @Test
    fun `capture writes offered packets and honours the app filter`() {
        val out = ByteArrayOutputStream()
        PacketCapture.start(out, uidFilter = 7)
        PacketCapture.offer(ByteArray(100), 60, uid = 7)
        PacketCapture.offer(ByteArray(100), 50, uid = 8) // another app: filtered out
        PacketCapture.offer(ByteArray(100), 20, uid = 7)
        PacketCapture.stop()

        val state = PacketCapture.state.value
        assertFalse(state.active)
        assertNull(state.error)
        assertEquals(2L, state.packets)
        assertEquals(24 + 16 + 60 + 16 + 20, out.size())

        // offering without a running capture is a no-op
        PacketCapture.offer(ByteArray(10), 10, uid = 7)
        assertEquals(2L, PacketCapture.state.value.packets)
    }
}
