package com.rangandale.appthrottler.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rangandale.appthrottler.domain.model.ThrottleConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CurrentConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("current_config", Context.MODE_PRIVATE)

    private val listType = object : TypeToken<List<String>>() {}.type

    fun load(): ThrottleConfig {
        val appsJson = prefs.getString(KEY_SELECTED_APPS, null)
        val selectedApps: List<String> = if (appsJson != null) {
            gson.fromJson(appsJson, listType) ?: emptyList()
        } else emptyList()

        return ThrottleConfig(
            selectedApps = selectedApps,
            downloadSpeedKbps = prefs.getInt(KEY_DOWNLOAD_SPEED, 0),
            uploadSpeedKbps = prefs.getInt(KEY_UPLOAD_SPEED, 0),
            latencyMs = prefs.getInt(KEY_LATENCY, 0),
            jitterMs = prefs.getInt(KEY_JITTER, 0),
            packetLossPercent = prefs.getFloat(KEY_PACKET_LOSS, 0f),
        )
    }

    fun save(config: ThrottleConfig) {
        prefs.edit()
            .putString(KEY_SELECTED_APPS, gson.toJson(config.selectedApps))
            .putInt(KEY_DOWNLOAD_SPEED, config.downloadSpeedKbps)
            .putInt(KEY_UPLOAD_SPEED, config.uploadSpeedKbps)
            .putInt(KEY_LATENCY, config.latencyMs)
            .putInt(KEY_JITTER, config.jitterMs)
            .putFloat(KEY_PACKET_LOSS, config.packetLossPercent)
            .apply()
    }

    companion object {
        private const val KEY_SELECTED_APPS = "selected_apps"
        private const val KEY_DOWNLOAD_SPEED = "download_speed_kbps"
        private const val KEY_UPLOAD_SPEED = "upload_speed_kbps"
        private const val KEY_LATENCY = "latency_ms"
        private const val KEY_JITTER = "jitter_ms"
        private const val KEY_PACKET_LOSS = "packet_loss_percent"
    }
}
