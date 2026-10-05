package com.rangandale.appthrottler.vpn.forward

import com.rangandale.appthrottler.vpn.TrafficCounters
import java.io.IOException
import java.io.OutputStream

/** Serialises writes of complete IP packets to the TUN device and counts delivered bytes. */
class TunWriter(private val out: OutputStream) {

    @Synchronized
    fun write(packet: ByteArray): Boolean {
        return try {
            out.write(packet)
            TrafficCounters.addIn(packet.size)
            true
        } catch (_: IOException) {
            // TUN closed during teardown
            false
        }
    }
}
