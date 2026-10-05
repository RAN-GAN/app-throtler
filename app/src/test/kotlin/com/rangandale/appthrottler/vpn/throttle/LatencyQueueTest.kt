package com.rangandale.appthrottler.vpn.throttle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyQueueTest {
    private var nowMs = 0L
    private val queue = LatencyQueue { nowMs }

    @Test
    fun `packets are held for the configured latency`() {
        queue.enqueue(byteArrayOf(1), baseMs = 100, jitterMs = 0)
        nowMs = 99
        assertNull(queue.poll(0))
        nowMs = 100
        assertEquals(1, queue.poll(0)?.single()?.toInt())
    }

    @Test
    fun `packets enqueued in the same millisecond keep their order`() {
        repeat(200) { queue.enqueue(byteArrayOf(it.toByte()), baseMs = 0, jitterMs = 0) }
        repeat(200) { assertEquals(it.toByte(), queue.poll(0)?.single()) }
    }

    @Test
    fun `jitter never reorders packets`() {
        repeat(200) {
            queue.enqueue(byteArrayOf(it.toByte()), baseMs = 50, jitterMs = 40)
            nowMs += 1
        }
        nowMs += 1_000
        repeat(200) { assertEquals(it.toByte(), queue.poll(0)?.single()) }
    }

    @Test
    fun `lanes are ordered independently and rate-limit delay is added`() {
        val slow = LatencyQueue.Lane()
        val fast = LatencyQueue.Lane()
        queue.enqueue(byteArrayOf(1), baseMs = 300, jitterMs = 0, lane = slow)
        queue.enqueue(byteArrayOf(2), baseMs = 0, jitterMs = 0, lane = fast) // not held behind the slow lane
        queue.enqueue(byteArrayOf(3), baseMs = 0, jitterMs = 0, lane = fast, extraDelayMs = 100)

        assertEquals(2, queue.poll(0)?.single()?.toInt())
        assertNull(queue.poll(0))
        nowMs = 100
        assertEquals(3, queue.poll(0)?.single()?.toInt())
        nowMs = 300
        assertEquals(1, queue.poll(0)?.single()?.toInt())
    }
}
