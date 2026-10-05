package com.rangandale.appthrottler.vpn.throttle

import com.rangandale.appthrottler.domain.model.ThrottleParams

/** The throttling state for one set of [ThrottleParams]: both buckets and the latency lanes. */
class Shaper(val params: ThrottleParams) {
    val upload = TokenBucket(params.uploadSpeedKbps)
    val download = TokenBucket(params.downloadSpeedKbps)
    val lane = LatencyQueue.Lane()
    val downLane = LatencyQueue.Lane()

    // The latency setting is the delay added to a round trip, split across the two directions
    // so that data pushed by a server arrives late too.
    val upLatencyMs: Int = params.latencyMs / 2
    val downLatencyMs: Int = params.latencyMs - upLatencyMs
    val upJitterMs: Int = params.jitterMs / 2
    val downJitterMs: Int = params.jitterMs - upJitterMs

    /** With total loss nothing gets through in either direction. */
    val isOffline: Boolean = params.packetLossPercent >= 100f
}

/**
 * The session's shapers: one shared by every app using the session settings, plus one per app
 * (UID) that has its own values. Apps with an override do not compete with the others for
 * bandwidth.
 *
 * A scenario step or network event can temporarily put every app on the same conditions with
 * [overrideAll]; shapers are therefore looked up per packet, never cached by a flow.
 */
class ShaperSet(
    default: ThrottleParams,
    overridesByUid: Map<Int, ThrottleParams> = emptyMap(),
) {
    private val defaultShaper = Shaper(default)
    private val byUid: Map<Int, Shaper> = overridesByUid.mapValues { Shaper(it.value) }

    @Volatile private var everyone: Shaper? = null

    fun forUid(uid: Int): Shaper = everyone ?: byUid[uid] ?: defaultShaper

    /** Applies [params] to every app until called again; null returns to the configured values. */
    fun overrideAll(params: ThrottleParams?) {
        everyone = params?.let(::Shaper)
    }
}
