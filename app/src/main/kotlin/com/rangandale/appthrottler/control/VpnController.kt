package com.rangandale.appthrottler.control

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import com.rangandale.appthrottler.domain.model.Scenario
import com.rangandale.appthrottler.domain.model.ThrottleConfig
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.vpn.ThrottleVpnService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place that talks to [ThrottleVpnService], shared by the UI, the Quick Settings tile
 * and external control (shortcuts, adb).
 */
@Singleton
class VpnController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val isRunning: Boolean get() = ThrottleVpnService.activeConfig.value != null

    /** The system's VPN consent screen, or null if consent was already given. */
    fun consentIntent(): Intent? = VpnService.prepare(context)

    /** Starts the tunnel, or replaces a running one. Returns false if the service could not be started. */
    fun start(config: ThrottleConfig): Boolean {
        return try {
            val intent = ThrottleVpnService.startIntent(context, config)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: Exception) {
            ThrottleLogger.e(TAG, "Could not start VPN service: ${e.message}")
            false
        }
    }

    fun stop() = send(ThrottleVpnService.stopIntent(context))

    fun runScenario(scenario: Scenario) = send(ThrottleVpnService.scenarioIntent(context, scenario))

    fun cancelScenario() = send(ThrottleVpnService.cancelScenarioIntent(context))

    fun resetConnections() = send(ThrottleVpnService.resetConnectionsIntent(context))

    private fun send(intent: Intent) {
        // Only a running service can act on these; starting it just to be told to stop is
        // not allowed from the background on recent Android versions anyway.
        if (!isRunning) return
        try {
            context.startService(intent)
        } catch (e: Exception) {
            ThrottleLogger.e(TAG, "Could not reach VPN service: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "VpnController"
    }
}
