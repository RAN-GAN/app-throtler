package com.rangandale.appthrottler.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rangandale.appthrottler.MainActivity
import com.rangandale.appthrottler.domain.model.ThrottleConfig
import com.rangandale.appthrottler.logging.ThrottleLogger

class ThrottleVpnService : VpnService() {

    private var packetProcessor: PacketProcessor? = null
    private var tunFd: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return Service.START_NOT_STICKY
        }

        val config = intent?.toThrottleConfig() ?: return Service.START_NOT_STICKY

        if (config.selectedApps.isEmpty()) {
            ThrottleLogger.w(TAG, "No apps selected — VPN not started")
            stopSelf()
            return Service.START_NOT_STICKY
        }

        packetProcessor?.stop()
        tunFd?.close()
        tunFd = null

        val newTunFd = buildTunnel(config)
        if (newTunFd == null) {
            ThrottleLogger.e(TAG, "Failed to establish VPN tunnel")
            stopSelf()
            return Service.START_NOT_STICKY
        }
        tunFd = newTunFd

        ThrottleLogger.i(TAG, "VPN tunnel established — throttling ${config.selectedApps.size} app(s)")
        config.selectedApps.forEach { ThrottleLogger.i(TAG, "  → $it") }

        packetProcessor = PacketProcessor(config, newTunFd, this)
        packetProcessor?.start()

        return Service.START_STICKY
    }

    override fun onDestroy() {
        ThrottleLogger.i(TAG, "VPN stopped")
        packetProcessor?.stop()
        packetProcessor = null
        tunFd?.close()
        tunFd = null
        super.onDestroy()
    }

    private fun buildTunnel(config: ThrottleConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .setMtu(MTU)
            .setSession("AppThrottler")
            .setBlocking(true)

        for (pkg in config.selectedApps) {
            try {
                builder.addAllowedApplication(pkg)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Package not found, skipping: $pkg")
            }
        }

        return try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "VpnService.Builder.establish() failed", e)
            null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle("App Throttler running")
        .setContentText("Selected apps are being throttled")
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                pendingIntentFlags(),
            ),
        )
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
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
        const val ACTION_START = "com.rangandale.appthrottler.vpn.action.START"
        const val ACTION_STOP = "com.rangandale.appthrottler.vpn.action.STOP"
        const val EXTRA_SELECTED_APPS = "extra_selected_apps"
        const val EXTRA_DOWNLOAD_SPEED_KBPS = "extra_download_speed_kbps"
        const val EXTRA_UPLOAD_SPEED_KBPS = "extra_upload_speed_kbps"
        const val EXTRA_LATENCY_MS = "extra_latency_ms"
        const val EXTRA_JITTER_MS = "extra_jitter_ms"
        const val EXTRA_PACKET_LOSS_PERCENT = "extra_packet_loss_percent"

        private const val CHANNEL_ID = "app_throttler_vpn"
        private const val CHANNEL_NAME = "App Throttler VPN"
        private const val NOTIFICATION_ID = 1001

        fun startIntent(
            context: Context,
            selectedApps: ArrayList<String>,
            downloadSpeedKbps: Int,
            uploadSpeedKbps: Int,
            latencyMs: Int,
            jitterMs: Int,
            packetLossPercent: Float,
        ): Intent = Intent(context, ThrottleVpnService::class.java).apply {
            action = ACTION_START
            putStringArrayListExtra(EXTRA_SELECTED_APPS, selectedApps)
            putExtra(EXTRA_DOWNLOAD_SPEED_KBPS, downloadSpeedKbps)
            putExtra(EXTRA_UPLOAD_SPEED_KBPS, uploadSpeedKbps)
            putExtra(EXTRA_LATENCY_MS, latencyMs)
            putExtra(EXTRA_JITTER_MS, jitterMs)
            putExtra(EXTRA_PACKET_LOSS_PERCENT, packetLossPercent)
        }

        fun stopIntent(context: Context): Intent = Intent(context, ThrottleVpnService::class.java).apply {
            action = ACTION_STOP
        }
    }
}

private fun Intent.toThrottleConfig(): ThrottleConfig? {
    val selectedApps = getStringArrayListExtra(ThrottleVpnService.EXTRA_SELECTED_APPS) ?: arrayListOf()
    return ThrottleConfig(
        selectedApps = selectedApps,
        downloadSpeedKbps = getIntExtra(ThrottleVpnService.EXTRA_DOWNLOAD_SPEED_KBPS, 0),
        uploadSpeedKbps = getIntExtra(ThrottleVpnService.EXTRA_UPLOAD_SPEED_KBPS, 0),
        latencyMs = getIntExtra(ThrottleVpnService.EXTRA_LATENCY_MS, 0),
        jitterMs = getIntExtra(ThrottleVpnService.EXTRA_JITTER_MS, 0),
        packetLossPercent = getFloatExtra(ThrottleVpnService.EXTRA_PACKET_LOSS_PERCENT, 0f),
    )
}