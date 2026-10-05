package com.rangandale.appthrottler.domain.model

data class NetworkStats(
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val downloadBytesPerSecond: Long = 0,
    val uploadBytesPerSecond: Long = 0,
)