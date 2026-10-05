package com.rangandale.appthrottler.vpn.forward

import com.rangandale.appthrottler.vpn.TrafficCounters
import com.rangandale.appthrottler.vpn.capture.PacketCapture
import com.rangandale.appthrottler.vpn.flow.FlowOwners
import com.rangandale.appthrottler.vpn.throttle.LatencyQueue
import com.rangandale.appthrottler.vpn.throttle.Shaper
import java.io.IOException
import java.io.OutputStream

/**
 * Serialises writes of complete IP packets to the TUN device and counts delivered bytes.
 * Packets handed to [deliver] are first held back for the downstream half of the latency.
 */
class TunWriter(private val out: OutputStream) {
    private val delayed = LatencyQueue()
    private var delayThread: Thread? = null
    @Volatile private var running = true

    /** @param uid the app the packet is delivered to, if known (used to filter captures) */
    @Synchronized
    fun write(packet: ByteArray, uid: Int = FlowOwners.UNKNOWN_UID): Boolean {
        return try {
            out.write(packet)
            TrafficCounters.addIn(packet.size)
            PacketCapture.offer(packet, packet.size, uid)
            true
        } catch (_: IOException) {
            // TUN closed during teardown
            false
        }
    }

    /** Writes [packet] after the downstream latency of [shaper], keeping the shaper's packets in order. */
    fun deliver(packet: ByteArray, uid: Int, shaper: Shaper) {
        if (shaper.downLatencyMs <= 0 && shaper.downJitterMs <= 0) {
            write(packet, uid)
            return
        }
        ensureDelayThread()
        delayed.enqueue(packet, shaper.downLatencyMs, shaper.downJitterMs, shaper.downLane, tag = uid)
    }

    fun stop() {
        running = false
        delayed.clear()
        synchronized(delayed) { delayThread }?.interrupt()
    }

    private fun ensureDelayThread() {
        synchronized(delayed) {
            if (delayThread != null || !running) return
            delayThread = Thread(::runDelayed, "throttler-tun-out").also { it.isDaemon = true; it.start() }
        }
    }

    private fun runDelayed() {
        while (running) {
            val packet = try {
                delayed.pollPacket(POLL_MS)
            } catch (_: InterruptedException) {
                break
            } ?: continue
            write(packet.payload, packet.tag)
        }
    }

    private companion object {
        const val POLL_MS = 200L
    }
}
