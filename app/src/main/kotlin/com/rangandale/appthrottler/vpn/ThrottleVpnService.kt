package com.rangandale.appthrottler.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rangandale.appthrottler.MainActivity
import com.rangandale.appthrottler.R
import com.rangandale.appthrottler.domain.model.ThrottleConfig
import com.rangandale.appthrottler.logging.ThrottleLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress

class ThrottleVpnService : VpnService() {

    private var packetProcessor: PacketProcessor? = null
    private var tunFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background
            ThrottleLogger.e(TAG, "Could not enter foreground: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent or a foreign action (e.g. a system restart) carries no config to run with.
        val config = intent?.takeIf { it.action == ACTION_START }?.toThrottleConfig()
        if (config == null) {
            tearDown()
            return Service.START_NOT_STICKY
        }

        // Replace any tunnel that is already up (re-applying changed settings).
        stopTunnel()

        val newTunFd = buildTunnel(config)
        if (newTunFd == null) {
            ThrottleLogger.e(TAG, "Failed to establish VPN tunnel")
            tearDown()
            return Service.START_NOT_STICKY
        }
        tunFd = newTunFd

        ThrottleLogger.i(TAG, "VPN tunnel established — throttling ${config.selectedApps.size} app(s)")
        config.selectedApps.forEach { ThrottleLogger.i(TAG, "  → $it") }

        TrafficCounters.reset()
        packetProcessor = PacketProcessor(config, newTunFd, this, MTU).also { it.start() }
        _activeConfig.value = config // reported only once the tunnel is really up

        return Service.START_NOT_STICKY
    }

    /** The user disconnected the VPN from system settings, or another VPN took over. */
    override fun onRevoke() {
        ThrottleLogger.w(TAG, "VPN revoked by the system")
        tearDown()
    }

    override fun onDestroy() {
        tearDown()
        super.onDestroy()
    }

    private fun stopTunnel() {
        packetProcessor?.stop()
        packetProcessor = null
        try {
            tunFd?.close()
        } catch (e: Exception) {
            ThrottleLogger.e(TAG, "Closing TUN failed: $e")
        }
        tunFd = null
    }

    private fun tearDown() {
        val wasActive = _activeConfig.value != null
        _activeConfig.value = null
        stopTunnel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
        if (wasActive) ThrottleLogger.i(TAG, "VPN stopped")
    }

    private fun buildTunnel(config: ThrottleConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .addAddress(TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .setMtu(MTU)
            .setSession(getString(R.string.app_name))
            .setBlocking(true)

        dnsServers().forEach { builder.addDnsServer(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // VPNs are metered by default, which makes apps behave differently than on Wi-Fi.
            builder.setMetered(false)
        }

        var allowed = 0
        for (pkg in config.selectedApps) {
            try {
                builder.addAllowedApplication(pkg)
                allowed++
            } catch (e: PackageManager.NameNotFoundException) {
                ThrottleLogger.w(TAG, "Package not installed, skipping: $pkg")
            }
        }
        // With no allowed application the tunnel would capture every app on the device.
        if (allowed == 0) {
            ThrottleLogger.w(TAG, "No installed apps selected — VPN not started")
            return null
        }

        return try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "VpnService.Builder.establish() failed", e)
            null
        }
    }

    /**
     * The DNS servers of the current network (so captive or private resolvers keep working),
     * followed by public fallbacks. Only IPv4 is tunnelled.
     */
    private fun dnsServers(): List<InetAddress> {
        val servers = LinkedHashSet<InetAddress>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.getLinkProperties(cm.activeNetwork)?.dnsServers
                    ?.filterTo(servers) { it is Inet4Address && !it.isLoopbackAddress }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read system DNS servers", e)
            }
        }
        FALLBACK_DNS.forEach { servers.add(InetAddress.getByName(it)) }
        return servers.toList()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_throttle)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                pendingIntentFlags(),
            ),
        )
        .addAction(
            0,
            getString(R.string.notification_stop),
            PendingIntent.getService(this, 1, stopIntent(this), pendingIntentFlags()),
        )
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun pendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    companion object {
        private const val TAG = "ThrottleVpnService"
        private const val MTU = 1500
        private const val TUN_ADDRESS = "10.0.0.2"
        private val FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1")

        const val ACTION_START = "com.rangandale.appthrottler.vpn.action.START"
        const val ACTION_STOP = "com.rangandale.appthrottler.vpn.action.STOP"
        const val EXTRA_SELECTED_APPS = "extra_selected_apps"
        const val EXTRA_DOWNLOAD_SPEED_KBPS = "extra_download_speed_kbps"
        const val EXTRA_UPLOAD_SPEED_KBPS = "extra_upload_speed_kbps"
        const val EXTRA_LATENCY_MS = "extra_latency_ms"
        const val EXTRA_JITTER_MS = "extra_jitter_ms"
        const val EXTRA_PACKET_LOSS_PERCENT = "extra_packet_loss_percent"

        private const val CHANNEL_ID = "app_throttler_vpn"
        private const val NOTIFICATION_ID = 1001

        // Source of truth for the VPN state that the ViewModel observes: the config the tunnel
        // is currently running with, or null while stopped.
        private val _activeConfig = MutableStateFlow<ThrottleConfig?>(null)
        val activeConfig: StateFlow<ThrottleConfig?> = _activeConfig.asStateFlow()

        fun startIntent(context: Context, config: ThrottleConfig): Intent =
            Intent(context, ThrottleVpnService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_SELECTED_APPS, ArrayList(config.selectedApps))
                putExtra(EXTRA_DOWNLOAD_SPEED_KBPS, config.downloadSpeedKbps)
                putExtra(EXTRA_UPLOAD_SPEED_KBPS, config.uploadSpeedKbps)
                putExtra(EXTRA_LATENCY_MS, config.latencyMs)
                putExtra(EXTRA_JITTER_MS, config.jitterMs)
                putExtra(EXTRA_PACKET_LOSS_PERCENT, config.packetLossPercent)
            }

        fun stopIntent(context: Context): Intent = Intent(context, ThrottleVpnService::class.java).apply {
            action = ACTION_STOP
        }
    }
}

private fun Intent.toThrottleConfig(): ThrottleConfig = ThrottleConfig(
    selectedApps = getStringArrayListExtra(ThrottleVpnService.EXTRA_SELECTED_APPS) ?: arrayListOf(),
    downloadSpeedKbps = getIntExtra(ThrottleVpnService.EXTRA_DOWNLOAD_SPEED_KBPS, 0),
    uploadSpeedKbps = getIntExtra(ThrottleVpnService.EXTRA_UPLOAD_SPEED_KBPS, 0),
    latencyMs = getIntExtra(ThrottleVpnService.EXTRA_LATENCY_MS, 0),
    jitterMs = getIntExtra(ThrottleVpnService.EXTRA_JITTER_MS, 0),
    packetLossPercent = getFloatExtra(ThrottleVpnService.EXTRA_PACKET_LOSS_PERCENT, 0f),
)
