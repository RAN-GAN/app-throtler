package com.rangandale.appthrottler.vpn.throttle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBucketTest {
    private var nowNs = 0L
    private fun bucket(rateKbps: Int) = TokenBucket(rateKbps) { nowNs }
    private fun advanceMs(ms: Long) {
        nowNs += ms * 1_000_000L
    }

    @Test
    fun `zero rate is unlimited`() {
        val bucket = bucket(0)
        repeat(1_000) { assertTrue(bucket.tryConsume(1_500)) }
        assertEquals(1_000_000, bucket.consumeUpTo(1_000_000))
    }

    @Test
    fun `long-run throughput matches the configured rate`() {
        val bucket = bucket(800) // 100,000 bytes per second
        bucket.consumeUpTo(Int.MAX_VALUE) // drain the initial burst

        var consumed = 0L
        repeat(10_000) { // 10 seconds in 1 ms steps
            advanceMs(1)
            consumed += bucket.consumeUpTo(Int.MAX_VALUE)
        }
        assertEquals(1_000_000L, consumed)
    }

    @Test
    fun `slow rates do not lose bytes to rounding`() {
        val bucket = bucket(10) // 1,250 bytes per second = 1.25 bytes per ms
        bucket.consumeUpTo(Int.MAX_VALUE)

        var consumed = 0L
        repeat(4_000) {
            advanceMs(1)
            consumed += bucket.consumeUpTo(Int.MAX_VALUE)
        }
        assertEquals(5_000L, consumed)
    }

    @Test
    fun `burst is capped after a long idle period`() {
        val bucket = bucket(800)
        bucket.consumeUpTo(Int.MAX_VALUE)
        advanceMs(60_000)
        assertEquals(25_000, bucket.consumeUpTo(Int.MAX_VALUE)) // 250 ms worth
    }

    @Test
    fun `tryConsume refuses until enough tokens are available`() {
        val bucket = bucket(80) // 10,000 bytes per second, 4,096 byte bucket
        assertTrue(bucket.tryConsume(4_096))
        assertFalse(bucket.tryConsume(1_500))
        advanceMs(100)
        assertFalse(bucket.tryConsume(1_500))
        advanceMs(50)
        assertTrue(bucket.tryConsume(1_500))
    }

    @Test
    fun `a packet larger than the bucket is delayed instead of starved`() {
        val bucket = bucket(80)
        assertTrue(bucket.tryConsume(20_000)) // full bucket covers it and goes into debt
        advanceMs(1_000)
        assertFalse(bucket.tryConsume(1)) // still paying off the debt
        advanceMs(1_000)
        assertTrue(bucket.tryConsume(1_500))
    }

    @Test
    fun `consumeUpTo can wait for a minimum amount`() {
        val bucket = bucket(80) // 10,000 bytes per second
        bucket.consumeUpTo(Int.MAX_VALUE)
        advanceMs(100) // 1,000 bytes available
        assertEquals(0, bucket.consumeUpTo(10_000, minBytes = 1_460))
        assertEquals(500, bucket.consumeUpTo(500, minBytes = 1_460)) // the request itself is smaller
        advanceMs(150) // 500 left + 1,500 earned
        assertEquals(2_000, bucket.consumeUpTo(10_000, minBytes = 1_460))
    }

    @Test
    fun `reserve turns a shortfall into a wait and refuses when the queue is too long`() {
        val bucket = bucket(80) // 10,000 bytes per second, 4,096 byte bucket
        assertEquals(0L, bucket.reserve(4_096, maxWaitMs = 1_000))
        assertEquals(150L, bucket.reserve(1_500, maxWaitMs = 1_000))
        assertEquals(300L, bucket.reserve(1_500, maxWaitMs = 1_000)) // queued behind the first
        assertEquals(-1L, bucket.reserve(1_500, maxWaitMs = 400)) // would wait 450 ms: dropped
        assertEquals(350L, bucket.reserve(500, maxWaitMs = 400)) // the refused packet booked nothing
        advanceMs(350)
        assertEquals(0L, bucket.reserve(0, maxWaitMs = 0))
        assertEquals(0L, bucket(0).reserve(1_000_000, maxWaitMs = 0))
    }

    @Test
    fun `refund returns unused tokens`() {
        val bucket = bucket(80)
        assertEquals(4_096, bucket.consumeUpTo(10_000))
        bucket.refund(1_000)
        assertEquals(1_000, bucket.consumeUpTo(10_000))
    }
}
