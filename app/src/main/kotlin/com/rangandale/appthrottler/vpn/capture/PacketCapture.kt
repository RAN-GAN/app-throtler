package com.rangandale.appthrottler.vpn.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class CaptureState(
    val active: Boolean = false,
    val packets: Long = 0,
    val bytes: Long = 0,
    /** Packets left out of the file because the writer could not keep up. */
    val dropped: Long = 0,
    val error: String? = null,
)

/** Writes packets in the classic libpcap format with raw IP link type, readable by Wireshark. */
class PcapWriter(out: OutputStream) {
    private val out = BufferedOutputStream(out, 64 * 1024)
    var bytesWritten = 0L
        private set

    init {
        writeInt(0xA1B2C3D4.toInt()) // magic, microsecond timestamps
        writeShort(2) // version 2.4
        writeShort(4)
        writeInt(0) // GMT offset
        writeInt(0) // timestamp accuracy
        writeInt(SNAP_LEN)
        writeInt(LINKTYPE_RAW)
    }

    fun write(timestampMs: Long, packet: ByteArray) {
        writeInt((timestampMs / 1000).toInt())
        writeInt(((timestampMs % 1000) * 1000).toInt())
        writeInt(packet.size)
        writeInt(packet.size)
        out.write(packet)
        bytesWritten += packet.size
    }

    fun flush() = out.flush()

    fun close() = out.close()

    // pcap fields are written in the byte order implied by the magic number: little-endian here.
    private fun writeInt(value: Int) {
        out.write(value)
        out.write(value ushr 8)
        out.write(value ushr 16)
        out.write(value ushr 24)
        bytesWritten += 4
    }

    private fun writeShort(value: Int) {
        out.write(value)
        out.write(value ushr 8)
        bytesWritten += 2
    }

    companion object {
        const val LINKTYPE_RAW = 101
        private const val SNAP_LEN = 65_535
    }
}

/**
 * Optional capture of the tunnel's traffic to a pcap file. Packet threads hand packets to a
 * bounded queue; a dedicated thread writes them, so a slow disk can only cost capture
 * completeness, never forwarding speed.
 */
object PacketCapture {
    const val ALL_APPS = Int.MIN_VALUE
    private const val QUEUE_SIZE = 4096
    private const val STATE_INTERVAL_MS = 500L

    private class Entry(val timestampMs: Long, val packet: ByteArray)

    private class Session(val out: OutputStream, val uidFilter: Int, val maxBytes: Long) {
        val queue = ArrayBlockingQueue<Entry>(QUEUE_SIZE)
        val dropped = AtomicLong()
        @Volatile var running = true
        lateinit var thread: Thread
    }

    @Volatile private var session: Session? = null

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    /**
     * Starts capturing into [out], which is closed when the capture ends.
     * @param uidFilter capture only this app's packets, or [ALL_APPS]
     */
    @Synchronized
    fun start(out: OutputStream, uidFilter: Int = ALL_APPS, maxBytes: Long = 100L * 1024 * 1024) {
        stop()
        val newSession = Session(out, uidFilter, maxBytes)
        newSession.thread = Thread({ run(newSession) }, "throttler-capture").also { it.isDaemon = true }
        _state.value = CaptureState(active = true)
        session = newSession
        newSession.thread.start()
    }

    @Synchronized
    fun stop() {
        val current = session ?: return
        session = null
        current.running = false
        current.thread.interrupt()
        runCatching { current.thread.join(2_000) }
    }

    /** Called by the packet threads for every packet crossing the tunnel. Never blocks. */
    fun offer(packet: ByteArray, length: Int, uid: Int) {
        val current = session ?: return
        if (current.uidFilter != ALL_APPS && current.uidFilter != uid) return
        if (!current.queue.offer(Entry(System.currentTimeMillis(), packet.copyOf(length)))) {
            current.dropped.incrementAndGet()
        }
    }

    private fun run(current: Session) {
        var packets = 0L
        var error: String? = null
        var writer: PcapWriter? = null
        try {
            writer = PcapWriter(current.out)
            var lastStateMs = 0L
            while (current.running || current.queue.isNotEmpty()) {
                val entry = try {
                    current.queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null // stop requested: drain what is queued, then leave
                }
                if (entry != null) {
                    writer.write(entry.timestampMs, entry.packet)
                    packets++
                    if (writer.bytesWritten >= current.maxBytes) {
                        error = "Capture stopped: size limit reached"
                        break
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastStateMs >= STATE_INTERVAL_MS) {
                    lastStateMs = now
                    _state.value = CaptureState(true, packets, writer.bytesWritten, current.dropped.get())
                }
            }
        } catch (e: IOException) {
            error = "Capture failed: ${e.message}"
        } finally {
            if (session === current) session = null
            val bytes = writer?.bytesWritten ?: 0L
            try {
                writer?.close() ?: current.out.close()
            } catch (e: IOException) {
                if (error == null) error = "Capture failed: ${e.message}"
            }
            _state.value = CaptureState(false, packets, bytes, current.dropped.get(), error)
        }
    }
}
