package com.rangandale.appthrottler.domain.model

/** One stretch of a scenario: these network conditions for this long. */
data class ScenarioStep(
    val label: String,
    val params: ThrottleParams,
    val seconds: Int,
)

/**
 * A scripted sequence of network conditions, e.g. good coverage, then a tunnel, then recovery.
 * While it runs, every routed app gets the current step's conditions; afterwards the session
 * settings (and per-app profiles) apply again.
 */
data class Scenario(
    val name: String,
    val steps: List<ScenarioStep>,
) {
    val totalSeconds: Int get() = steps.sumOf { it.seconds }
}

/** What a running scenario is doing right now. */
data class ScenarioStatus(
    val name: String,
    val stepLabel: String,
    val stepIndex: Int,
    val stepCount: Int,
    val stepEndsAtMs: Long,
)

val ThrottleProfile.params: ThrottleParams
    get() = ThrottleParams(downloadSpeedKbps, uploadSpeedKbps, latencyMs, jitterMs, packetLossPercent)

fun ThrottleProfile.asStep(seconds: Int) = ScenarioStep(name, params, seconds)

object BuiltInScenarios {
    val trainTunnel = Scenario(
        name = "Train through a tunnel",
        steps = listOf(
            PresetProfiles.fourG.asStep(30),
            PresetProfiles.twoG.asStep(20),
            PresetProfiles.offline.asStep(15),
            PresetProfiles.threeG.asStep(30),
        ),
    )

    val elevator = Scenario(
        name = "Elevator ride",
        steps = listOf(
            PresetProfiles.fourG.asStep(15),
            PresetProfiles.offline.asStep(20),
            PresetProfiles.fourG.asStep(15),
        ),
    )

    val flakyWifi = Scenario(
        name = "Flaky Wi-Fi",
        steps = listOf(
            PresetProfiles.slowWifi.asStep(20),
            PresetProfiles.congestedWifi.asStep(20),
            PresetProfiles.offline.asStep(5),
            PresetProfiles.congestedWifi.asStep(20),
            PresetProfiles.slowWifi.asStep(20),
        ),
    )

    val all = listOf(trainTunnel, elevator, flakyWifi)
}
