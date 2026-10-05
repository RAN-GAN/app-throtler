package com.rangandale.appthrottler.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rangandale.appthrottler.domain.model.AppRule
import com.rangandale.appthrottler.domain.model.ThrottleConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Persists the config being edited (and the profile name it came from) across restarts. */
@Singleton
class CurrentConfigRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("current_config", Context.MODE_PRIVATE)

    private val listType = object : TypeToken<List<String>>() {}.type
    private val rulesType = object : TypeToken<List<AppRule>>() {}.type

    private fun <T> readList(key: String, type: java.lang.reflect.Type): List<T> = try {
        prefs.getString(key, null)?.let { gson.fromJson<List<T>>(it, type) } ?: emptyList()
    } catch (e: Exception) {
        emptyList() // unreadable (e.g. written by an incompatible version): start clean
    }

    fun load(): ThrottleConfig {
        return ThrottleConfig(
            selectedApps = readList(KEY_SELECTED_APPS, listType),
            appRules = readList(KEY_APP_RULES, rulesType),
            blockedDomains = readList(KEY_BLOCKED_DOMAINS, listType),
            downloadSpeedKbps = prefs.getInt(KEY_DOWNLOAD_SPEED, 0),
            uploadSpeedKbps = prefs.getInt(KEY_UPLOAD_SPEED, 0),
            latencyMs = prefs.getInt(KEY_LATENCY, 0),
            jitterMs = prefs.getInt(KEY_JITTER, 0),
            packetLossPercent = prefs.getFloat(KEY_PACKET_LOSS, 0f),
        )
    }

    fun loadProfileName(): String = prefs.getString(KEY_PROFILE_NAME, null).orEmpty()

    fun save(config: ThrottleConfig, profileName: String) {
        prefs.edit()
            .putString(KEY_SELECTED_APPS, gson.toJson(config.selectedApps))
            .putString(KEY_APP_RULES, gson.toJson(config.appRules))
            .putString(KEY_BLOCKED_DOMAINS, gson.toJson(config.blockedDomains))
            .putInt(KEY_DOWNLOAD_SPEED, config.downloadSpeedKbps)
            .putInt(KEY_UPLOAD_SPEED, config.uploadSpeedKbps)
            .putInt(KEY_LATENCY, config.latencyMs)
            .putInt(KEY_JITTER, config.jitterMs)
            .putFloat(KEY_PACKET_LOSS, config.packetLossPercent)
            .putString(KEY_PROFILE_NAME, profileName)
            .apply()
    }

    companion object {
        private const val KEY_SELECTED_APPS = "selected_apps"
        private const val KEY_APP_RULES = "app_rules"
        private const val KEY_BLOCKED_DOMAINS = "blocked_domains"
        private const val KEY_DOWNLOAD_SPEED = "download_speed_kbps"
        private const val KEY_UPLOAD_SPEED = "upload_speed_kbps"
        private const val KEY_LATENCY = "latency_ms"
        private const val KEY_JITTER = "jitter_ms"
        private const val KEY_PACKET_LOSS = "packet_loss_percent"
        private const val KEY_PROFILE_NAME = "profile_name"
    }
}
