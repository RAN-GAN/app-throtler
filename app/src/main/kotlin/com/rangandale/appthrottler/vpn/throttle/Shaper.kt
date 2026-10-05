package com.rangandale.appthrottler.vpn.throttle

import com.rangandale.appthrottler.domain.model.ThrottleParams

/** The throttling state for one set of [ThrottleParams]: both buckets and the latency lane. */
class Shaper(val params: ThrottleParams) {
    val upload = TokenBucket(params.uploadSpeedKbps)
    val download = TokenBucket(params.downloadSpeedKbps)
    val lane = LatencyQueue.Lane()
}

/**
 * The session's shapers: one shared by every app using the session settings, plus one per app
 * (UID) that has its own values. Apps with an override do not compete with the others for
 * bandwidth.
 */
class ShaperSet(
    default: ThrottleParams,
    overridesByUid: Map<Int, ThrottleParams> = emptyMap(),
) {
    private val defaultShaper = Shaper(default)
    private val byUid: Map<Int, Shaper> = overridesByUid.mapValues { Shaper(it.value) }

    fun forUid(uid: Int): Shaper = byUid[uid] ?: defaultShaper
}
