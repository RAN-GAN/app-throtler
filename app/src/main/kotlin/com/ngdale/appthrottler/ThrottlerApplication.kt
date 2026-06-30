package com.rangandale.appthrottler

import android.app.Application
import com.rangandale.appthrottler.logging.CrashHandler
import com.rangandale.appthrottler.logging.ThrottleLogger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ThrottlerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        replayCrashIfAny()
    }

    private fun replayCrashIfAny() {
        val report = CrashHandler.readAndClear(this) ?: return
        ThrottleLogger.e("CRASH", "Previous session crashed — see below")
        report.lines().forEach { line ->
            if (line.isNotBlank()) ThrottleLogger.e("CRASH", line)
        }
    }
}