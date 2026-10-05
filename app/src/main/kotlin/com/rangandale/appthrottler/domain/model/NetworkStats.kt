package com.rangandale.appthrottler.domain.model

data class NetworkStats(
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    /** Session totals per app, busiest first. Empty where flows cannot be attributed. */
    val apps: List<AppTraffic> = emptyList(),
    /** Connections refused by a block rule or blocked domain this session. */
    val blockedAttempts: Long = 0,
)

data class AppTraffic(
    val uid: Int,
    /** Null for system components and flows whose owner is unknown. */
    val packageName: String?,
    val label: String,
    val bytesIn: Long,
    val bytesOut: Long,
)
