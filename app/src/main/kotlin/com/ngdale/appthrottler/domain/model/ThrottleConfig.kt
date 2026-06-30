package com.rangandale.appthrottler.domain.model

data class ThrottleConfig(
    val selectedApps: List<String> = emptyList(),
    val downloadSpeedKbps: Int = 0,
    val uploadSpeedKbps: Int = 0,
    val latencyMs: Int = 0,
    val jitterMs: Int = 0,
    val packetLossPercent: Float = 0f,
)