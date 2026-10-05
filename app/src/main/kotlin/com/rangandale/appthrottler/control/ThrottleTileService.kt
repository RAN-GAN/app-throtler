package com.rangandale.appthrottler.control

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.rangandale.appthrottler.MainActivity
import com.rangandale.appthrottler.data.repository.CurrentConfigRepository
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.vpn.ThrottleVpnService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Quick Settings tile that starts the throttle with the saved settings, or stops it. */
@RequiresApi(Build.VERSION_CODES.N)
@AndroidEntryPoint
class ThrottleTileService : TileService() {

    @Inject lateinit var controller: VpnController
    @Inject lateinit var currentConfig: CurrentConfigRepository

    private var watcher: Job? = null

    override fun onStartListening() {
        watcher = CoroutineScope(Dispatchers.Main.immediate).launch {
            ThrottleVpnService.activeConfig.collect { active ->
                qsTile?.apply {
                    state = if (active != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        watcher?.cancel()
        watcher = null
    }

    override fun onClick() {
        ThrottleLogger.d(TAG, "Tile tapped (running=${controller.isRunning})")
        if (controller.isRunning) {
            controller.stop()
            return
        }
        val config = currentConfig.load()
        // Without VPN consent or any selected app there is something to do in the app first.
        val reason = when {
            config.selectedApps.isEmpty() -> "no apps are selected"
            controller.consentIntent() != null -> "VPN permission has not been given"
            !controller.start(config) -> "the service could not be started"
            else -> return
        }
        ThrottleLogger.w(TAG, "Tile cannot start the VPN ($reason) — opening the app")
        openApp()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val TAG = "Tile"
    }
}
