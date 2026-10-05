package com.rangandale.appthrottler.control

import androidx.activity.ComponentActivity
import android.content.Intent
import android.os.Bundle
import com.rangandale.appthrottler.MainActivity
import com.rangandale.appthrottler.data.repository.CurrentConfigRepository
import com.rangandale.appthrottler.data.repository.ProfileRepository
import com.rangandale.appthrottler.data.repository.ScenarioRepository
import com.rangandale.appthrottler.domain.model.BuiltInScenarios
import com.rangandale.appthrottler.domain.model.PresetProfiles
import com.rangandale.appthrottler.domain.model.Scenario
import com.rangandale.appthrottler.domain.model.ThrottleProfile
import com.rangandale.appthrottler.domain.model.asStep
import com.rangandale.appthrottler.logging.ThrottleLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Invisible entry point for controlling the throttle from outside the UI: launcher shortcuts,
 * and scripts or automated tests through adb. See docs/DEVELOPMENT.md for the commands.
 *
 * It is an activity rather than a broadcast receiver because only a foreground component may
 * start the VPN's foreground service.
 */
@AndroidEntryPoint
class ControlActivity : ComponentActivity() {

    @Inject lateinit var controller: VpnController
    @Inject lateinit var currentConfig: CurrentConfigRepository
    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var scenarios: ScenarioRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (e: Exception) {
            ThrottleLogger.e(TAG, "Command failed: ${e.message}")
        }
        finish()
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_PROFILE))
            ACTION_STOP -> {
                controller.stop()
                ThrottleLogger.i(TAG, "Stop requested")
            }
            ACTION_RESET_CONNECTIONS -> controller.resetConnections()
            ACTION_SCENARIO -> runScenario(intent)
            ACTION_CANCEL_SCENARIO -> controller.cancelScenario()
            else -> ThrottleLogger.w(TAG, "Unknown command: ${intent.action}")
        }
    }

    private fun start(profileName: String?) {
        var config = currentConfig.load()
        var name = currentConfig.loadProfileName()
        if (profileName != null) {
            val profile = findProfile(profileName)
            if (profile == null) {
                ThrottleLogger.e(TAG, "No profile named \"$profileName\"")
                return
            }
            config = config.copy(
                selectedApps = profile.selectedAppPackages.ifEmpty { config.selectedApps },
                downloadSpeedKbps = profile.downloadSpeedKbps,
                uploadSpeedKbps = profile.uploadSpeedKbps,
                latencyMs = profile.latencyMs,
                jitterMs = profile.jitterMs,
                packetLossPercent = profile.packetLossPercent,
            )
            config = config.copy(appRules = config.appRules.filter { it.packageName in config.selectedApps })
            name = profile.name
            currentConfig.save(config, name)
        }
        // Consent and app selection need the UI.
        if (config.selectedApps.isEmpty() || controller.consentIntent() != null) {
            ThrottleLogger.w(TAG, "Cannot start from outside the app yet — opening it")
            startActivity(Intent(this, MainActivity::class.java))
            return
        }
        if (controller.start(config)) {
            ThrottleLogger.i(TAG, "Start requested" + (if (name.isNotBlank()) " with \"$name\"" else ""))
        }
    }

    private fun runScenario(intent: Intent) {
        val scenarioName = intent.getStringExtra(EXTRA_SCENARIO)
        val profileName = intent.getStringExtra(EXTRA_PROFILE)
        val scenario = when {
            scenarioName != null -> (BuiltInScenarios.all + scenarios.scenarios.value)
                .firstOrNull { it.name.equals(scenarioName, ignoreCase = true) }
            // A profile with a duration is a one-step scenario: "be on 2G for 60 seconds".
            profileName != null -> findProfile(profileName)?.let { profile ->
                val seconds = intent.getIntExtra(EXTRA_SECONDS, DEFAULT_EVENT_SECONDS)
                Scenario("${profile.name} for $seconds s", listOf(profile.asStep(seconds)))
            }
            else -> null
        }
        if (scenario == null) {
            ThrottleLogger.e(TAG, "No scenario or profile named \"${scenarioName ?: profileName}\"")
            return
        }
        if (!controller.isRunning) {
            ThrottleLogger.w(TAG, "Scenario ignored — the VPN is not running")
            return
        }
        controller.runScenario(scenario)
    }

    private fun findProfile(name: String): ThrottleProfile? {
        PresetProfiles.all.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        // A tiny local database read; this activity has no UI to keep responsive.
        val saved = runBlocking(Dispatchers.IO) { profiles.getAllProfiles().first() }
        return saved.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    companion object {
        private const val TAG = "Control"
        private const val DEFAULT_EVENT_SECONDS = 60

        const val ACTION_START = "com.rangandale.appthrottler.action.START"
        const val ACTION_STOP = "com.rangandale.appthrottler.action.STOP"
        const val ACTION_RESET_CONNECTIONS = "com.rangandale.appthrottler.action.RESET_CONNECTIONS"
        const val ACTION_SCENARIO = "com.rangandale.appthrottler.action.SCENARIO"
        const val ACTION_CANCEL_SCENARIO = "com.rangandale.appthrottler.action.CANCEL_SCENARIO"

        const val EXTRA_PROFILE = "profile"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_SECONDS = "seconds"
    }
}
