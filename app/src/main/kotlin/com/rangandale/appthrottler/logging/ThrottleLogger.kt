package com.rangandale.appthrottler.logging

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object ThrottleLogger {

    private const val MAX_ENTRIES = 500
    private const val LOGCAT_TAG = "AppThrottler"
    private val counter = AtomicLong(0)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) = append(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = append(Level.INFO, tag, message)
    fun w(tag: String, message: String) = append(Level.WARN, tag, message)
    fun e(tag: String, message: String) = append(Level.ERROR, tag, message)

    fun clear() {
        _entries.value = emptyList()
    }

    private fun append(level: Level, tag: String, message: String) {
        val entry = LogEntry(counter.getAndIncrement(), System.currentTimeMillis(), level, tag, message)
        _entries.update { list -> (list + entry).takeLast(MAX_ENTRIES) }
        // Mirror to logcat so `adb logcat -s AppThrottler` captures everything
        when (level) {
            Level.DEBUG -> Log.d(LOGCAT_TAG, "[$tag] $message")
            Level.INFO  -> Log.i(LOGCAT_TAG, "[$tag] $message")
            Level.WARN  -> Log.w(LOGCAT_TAG, "[$tag] $message")
            Level.ERROR -> Log.e(LOGCAT_TAG, "[$tag] $message")
        }
    }

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class LogEntry(
        val id: Long,
        val timestampMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    ) {
        val formattedTime: String
            get() = java.time.Instant.ofEpochMilli(timestampMs)
                .atZone(java.time.ZoneId.systemDefault())
                .format(TIME_FMT)

        companion object {
            private val TIME_FMT = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        }
    }
}
