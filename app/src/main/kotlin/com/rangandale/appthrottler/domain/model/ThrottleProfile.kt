package com.rangandale.appthrottler.domain.model

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

    val edge = ThrottleProfile(
        name = "EDGE",
        downloadSpeedKbps = 240,
        uploadSpeedKbps = 200,
        latencyMs = 400,
        jitterMs = 80,
        packetLossPercent = 1.5f,
        selectedAppPackages = emptyList(),
        isPreset = true,
    )

    val satellite = ThrottleProfile(
        name = "Satellite",
        downloadSpeedKbps = 5_000,
        uploadSpeedKbps = 1_000,
        latencyMs = 600,
        jitterMs = 30,
        packetLossPercent = 0.5f,
        selectedAppPackages = emptyList(),
        isPreset = true,
    )

    val congestedWifi = ThrottleProfile(
        name = "Congested Wi-Fi",
        downloadSpeedKbps = 2_000,
        uploadSpeedKbps = 1_000,
        latencyMs = 150,
        jitterMs = 100,
        packetLossPercent = 5.0f,
        selectedAppPackages = emptyList(),
        isPreset = true,
    )

    /** No connectivity: every packet is lost, so requests time out rather than fail fast. */
    val offline = ThrottleProfile(
        name = "Offline",
        downloadSpeedKbps = 0,
        uploadSpeedKbps = 0,
        latencyMs = 0,
        jitterMs = 0,
        packetLossPercent = 100f,
        selectedAppPackages = emptyList(),
        isPreset = true,
    )

    val all = listOf(twoG, edge, threeG, fourG, slowWifi, congestedWifi, satellite, offline)
}