package com.rangandale.appthrottler.logging

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val report = buildString {
                appendLine("=== CRASH REPORT ===")
                appendLine("Time   : $timestamp")
                appendLine("Thread : ${thread.name}")
                appendLine("Cause  : ${throwable::class.java.name}: ${throwable.message}")
                appendLine("---")
                appendLine(sw.toString().take(8000))
            }
            crashFile(context).writeText(report)
        } catch (_: Exception) {
            // Never block the default handler
        }
        defaultHandler?.uncaughtException(thread, throwable)
    }

    companion object {
        private const val CRASH_FILE = "last_crash.txt"

        fun crashFile(context: Context): File =
            File(context.filesDir, CRASH_FILE)

        fun install(context: Context) {
            val default = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context, default))
        }

        fun readAndClear(context: Context): String? {
            val file = crashFile(context)
            if (!file.exists()) return null
            val content = file.readText()
            file.delete()
            return content
        }
    }
}
