package com.ngdale.appthrottler.domain.model

data class ThrottleProfile(
    val id: Long = 0,
    val name: String,
    val downloadSpeedKbps: Int,
    val uploadSpeedKbps: Int,
    val latencyMs: Int,
    val jitterMs: Int,
    val packetLossPercent: Float,
    val selectedAppPackages: List<String>,
    val isPreset: Boolean = false,
)

object PresetProfiles {
    val twoG = ThrottleProfile(
        name = "2G",
        downloadSpeedKbps = 40,
        uploadSpeedKbps = 20,
        latencyMs = 300,
        jitterMs = 50,
        packetLossPercent = 2.0f,
        selectedAppPackages = emptyList(),
        isPreset = true,
    )

    val threeG = ThrottleProfile(
        name = "3G",
        downloadSpeedKbps = 384,
        uploadSpeedKbps = 128,
        latencyMs = 100,
        jitterMs = 20,
        packetLossPercent = 1.0f,
        selectedAppPackages = emptyList(),
        isPreset = true,
    )

    val fourG = ThrottleProfile(
        name = "4G",
        downloadSpeedKbps = 10_240,
        uploadSpeedKbps = 5_120,
        latencyMs = 20,
        jitterMs = 5,
        packetLossPercent = 0.1f,
        selectedAppPackages = emptyList(),
        isPreset = true,
    )

    val slowWifi = ThrottleProfile(
        name = "Slow Wi-Fi",
        downloadSpeedKbps = 1_024,
        uploadSpeedKbps = 512,
        latencyMs = 50,
        jitterMs = 10,
        packetLossPercent = 0.5f,
        selectedAppPackages = emptyList(),
        isPreset = true,
    )

    val all = listOf(twoG, threeG, fourG, slowWifi)
}