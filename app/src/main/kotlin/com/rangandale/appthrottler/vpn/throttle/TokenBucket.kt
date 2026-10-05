package com.rangandale.appthrottler.vpn.throttle

/**
 * Byte-based token bucket. A rate of 0 (or less) means unlimited.
 *
 * The bucket holds [BURST_MS] worth of traffic (never less than [MIN_CAPACITY_BYTES]) so the
 * configured rate is enforced almost immediately instead of after a multi-second free burst.
 */
class TokenBucket(
    private val rateKbps: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val unlimited = rateKbps <= 0
    private val bytesPerSecond: Long = rateKbps.coerceAtLeast(0) * 125L
    private val capacityBytes: Long = (bytesPerSecond * BURST_MS / 1000L).coerceAtLeast(MIN_CAPACITY_BYTES)

    private var tokens: Long = capacityBytes
    private var lastRefillNs: Long = nanoTime()

    /**
     * Takes [bytes] tokens if the bucket can cover them. A request larger than the whole bucket
     * is granted when the bucket is full and leaves it in debt, so oversized packets are delayed
     * rather than starved forever.
     */
    @Synchronized
    fun tryConsume(bytes: Int): Boolean {
        if (unlimited) return true
        refill()
        if (tokens < minOf(bytes.toLong(), capacityBytes)) return false
        tokens -= bytes
        return true
    }

    /**
     * Takes as many tokens as are available, up to [maxBytes]. Returns the amount taken, or 0
     * while fewer than [minBytes] are available — callers that turn tokens into packets use
     * this to wait for a full packet's worth instead of emitting a stream of tiny ones.
     */
    @Synchronized
    fun consumeUpTo(maxBytes: Int, minBytes: Int = 1): Int {
        if (unlimited) return maxBytes
        refill()
        if (tokens < minOf(minBytes.toLong(), maxBytes.toLong(), capacityBytes)) return 0
        val taken = minOf(maxBytes.toLong(), tokens).toInt()
        tokens -= taken
        return taken
    }

    @Synchronized
    fun refund(bytes: Int) {
        if (unlimited || bytes <= 0) return
        tokens = (tokens + bytes).coerceAtMost(capacityBytes)
    }

    /** Blocks the calling thread until [bytes] tokens were taken. Interruptible. */
    @Throws(InterruptedException::class)
    fun consumeBlocking(bytes: Int) {
        while (!tryConsume(bytes)) {
            Thread.sleep(waitMillisFor(bytes))
        }
    }

    @Synchronized
    private fun waitMillisFor(bytes: Int): Long {
        val deficit = minOf(bytes.toLong(), capacityBytes) - tokens
        if (deficit <= 0) return 1L
        return (deficit * 1000L / bytesPerSecond).coerceIn(1L, MAX_WAIT_MS)
    }

    private fun refill() {
        val now = nanoTime()
        val elapsedNs = now - lastRefillNs
        if (elapsedNs <= 0) return
        if (elapsedNs >= MAX_ELAPSED_NS) {
            tokens = capacityBytes
            lastRefillNs = now
            return
        }
        val earned = elapsedNs * bytesPerSecond / NS_PER_SECOND
        if (earned <= 0) return
        tokens = (tokens + earned).coerceAtMost(capacityBytes)
        // Advance only by the time that was converted into whole bytes so the remainder
        // carries over to the next refill and slow rates do not lose throughput to rounding.
        lastRefillNs += earned * NS_PER_SECOND / bytesPerSecond
    }

    companion object {
        private const val BURST_MS = 250L
        private const val MIN_CAPACITY_BYTES = 4096L
        private const val MAX_WAIT_MS = 50L
        private const val NS_PER_SECOND = 1_000_000_000L
        private const val MAX_ELAPSED_NS = 10 * NS_PER_SECOND
    }
}
