package com.rangandale.appthrottler.vpn

import java.util.concurrent.atomic.AtomicLong

/** Process-wide byte counters for the current VPN session, written by the packet threads. */
object TrafficCounters {
    private val bytesIn = AtomicLong(0L)
    private val bytesOut = AtomicLong(0L)

    /** Bytes delivered to the throttled apps (internet → app). */
    val totalBytesIn: Long get() = bytesIn.get()

    /** Bytes accepted from the throttled apps (app → internet). */
    val totalBytesOut: Long get() = bytesOut.get()

    fun addIn(bytes: Int) {
        bytesIn.addAndGet(bytes.toLong())
    }

    fun addOut(bytes: Int) {
        bytesOut.addAndGet(bytes.toLong())
    }

    fun reset() {
        bytesIn.set(0L)
        bytesOut.set(0L)
    }
}
