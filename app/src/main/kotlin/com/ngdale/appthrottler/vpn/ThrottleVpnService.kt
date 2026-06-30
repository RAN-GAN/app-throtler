package com.ngdale.appthrottler.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ngdale.appthrottler.MainActivity
import com.ngdale.appthrottler.domain.model.ThrottleConfig

class ThrottleVpnService : VpnService() {

    private var packetProcessor: PacketProcessor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return Service.START_NOT_STICKY
        }

        val config = intent?.toThrottleConfig() ?: return Service.START_NOT_STICKY

        packetProcessor?.stop()
        packetProcessor = PacketProcessor(config)
        packetProcessor?.start()

        return Service.START_STICKY
    }

    override fun onDestroy() {
        packetProcessor?.stop()
        packetProcessor = null
        super.onDestroy()
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
        const val ACTION_START = "com.ngdale.appthrottler.vpn.action.START"
        const val ACTION_STOP = "com.ngdale.appthrottler.vpn.action.STOP"
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