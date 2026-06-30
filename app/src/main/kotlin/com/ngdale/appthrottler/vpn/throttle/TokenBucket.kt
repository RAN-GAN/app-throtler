package com.ngdale.appthrottler.vpn.throttle

class TokenBucket(
    private val rateKbps: Int,
    private val capacityBytes: Long = (rateKbps.coerceAtLeast(1) * 125L),
) {

    private var tokens: Long = capacityBytes
    private var lastRefillTimeMs: Long = System.currentTimeMillis()

    @Synchronized
    fun tryConsume(bytes: Int): Boolean {
        refill()
        if (rateKbps <= 0) return true
        return if (tokens >= bytes.toLong()) {
            tokens -= bytes.toLong()
            true
        } else {
            false
        }
    }

    @Synchronized
    fun refill() {
        if (rateKbps <= 0) {
            tokens = capacityBytes
            lastRefillTimeMs = System.currentTimeMillis()
            return
        }

        val now = System.currentTimeMillis()
        val elapsedMs = (now - lastRefillTimeMs).coerceAtLeast(0)
        if (elapsedMs == 0L) return

        val refillBytes = (elapsedMs * rateKbps * 125L) / 1000L
        tokens = (tokens + refillBytes).coerceAtMost(capacityBytes)
        lastRefillTimeMs = now
    }
}