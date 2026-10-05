package com.rangandale.appthrottler.vpn.throttle

import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DelayedPacket(
    val payload: ByteArray,
    /** Free-form value carried with the packet (the forwarders use it for the owning UID). */
    val tag: Int,
    private val releaseAtMs: Long,
    private val sequence: Long,
    private val clockMs: () -> Long,
) : Delayed {

    override fun getDelay(unit: TimeUnit): Long =
        unit.convert(releaseAtMs - clockMs(), TimeUnit.MILLISECONDS)

    override fun compareTo(other: Delayed): Int {
        if (other !is DelayedPacket) {
            return getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        }
        val byTime = releaseAtMs.compareTo(other.releaseAtMs)
        return if (byTime != 0) byTime else sequence.compareTo(other.sequence)
    }
}

/**
 * Holds packets back for `baseMs ± jitterMs` (plus any wait imposed by rate limiting). Within a
 * [Lane], release times never go backwards, so jitter varies the delay without reordering
 * packets (reordering would only trigger spurious TCP retransmits, which is not what a latency
 * setting is meant to simulate).
 */
class LatencyQueue(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** Packets that must keep their relative order, e.g. everything shaped by one profile. */
    class Lane {
        internal var lastReleaseAtMs = Long.MIN_VALUE
    }

    private val queue = DelayQueue<DelayedPacket>()
    private val defaultLane = Lane()
    private var nextSequence = 0L

    @Synchronized
    fun enqueue(
        data: ByteArray,
        baseMs: Int,
        jitterMs: Int,
        lane: Lane = defaultLane,
        extraDelayMs: Long = 0L,
        tag: Int = 0,
    ) {
        val jitter = if (jitterMs <= 0) 0L else Random.nextLong(-jitterMs.toLong(), jitterMs.toLong() + 1)
        val delay = (baseMs + jitter).coerceAtLeast(0L) + extraDelayMs
        val releaseAt = maxOf(lane.lastReleaseAtMs, clockMs() + delay)
        lane.lastReleaseAtMs = releaseAt
        queue.offer(DelayedPacket(data, tag, releaseAt, nextSequence++, clockMs))
    }

    @Throws(InterruptedException::class)
    fun poll(timeoutMs: Long): ByteArray? = pollPacket(timeoutMs)?.payload

    @Throws(InterruptedException::class)
    fun pollPacket(timeoutMs: Long): DelayedPacket? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun clear() = queue.clear()
}
