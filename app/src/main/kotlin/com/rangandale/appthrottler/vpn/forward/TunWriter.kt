package com.rangandale.appthrottler.vpn.forward

import com.rangandale.appthrottler.vpn.TrafficCounters
import com.rangandale.appthrottler.vpn.capture.PacketCapture
import com.rangandale.appthrottler.vpn.flow.FlowOwners
import java.io.IOException
import java.io.OutputStream

/** Serialises writes of complete IP packets to the TUN device and counts delivered bytes. */
class TunWriter(private val out: OutputStream) {

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
}
