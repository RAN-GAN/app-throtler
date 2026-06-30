package com.ngdale.appthrottler.vpn.throttle

import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class DelayedPacket(
    val payload: ByteArray,
    private val releaseAtMs: Long,
) : Delayed {

    override fun getDelay(unit: TimeUnit): Long {
        val remainingMs = releaseAtMs - System.currentTimeMillis()
        return unit.convert(remainingMs, TimeUnit.MILLISECONDS)
    }

    override fun compareTo(other: Delayed): Int {
        val otherPacket = other as DelayedPacket
        return releaseAtMs.compareTo(otherPacket.releaseAtMs)
    }
}

class LatencyQueue {
    private val queue = DelayQueue<DelayedPacket>()

    fun enqueue(data: ByteArray, baseMs: Int, jitterMs: Int) {
        val jitter = if (jitterMs <= 0) 0 else Random.nextLong(-jitterMs.toLong(), jitterMs.toLong() + 1)
        val releaseAt = System.currentTimeMillis() + baseMs + jitter
        queue.offer(DelayedPacket(data, releaseAt))
    }

    fun poll(timeoutMs: Long): ByteArray? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)?.payload
}