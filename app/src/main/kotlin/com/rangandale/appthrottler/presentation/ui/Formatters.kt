package com.rangandale.appthrottler.presentation.ui

import com.rangandale.appthrottler.domain.model.ThrottleParams
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** A configured speed limit, where 0 means no limit. */
fun formatSpeedLimit(kbps: Int): String = if (kbps <= 0) "Unlimited" else formatKbps(kbps.toDouble())

/** A measured transfer rate. */
fun formatRate(bytesPerSecond: Long): String = formatKbps(bytesPerSecond * 8 / 1000.0)

private fun formatKbps(kbps: Double): String = when {
    kbps < 1000 -> "${kbps.roundToInt()} kbps"
    else -> "${trimmed(kbps / 1000)} Mbps"
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1_000L -> "$bytes B"
    bytes < 1_000_000L -> "${trimmed(bytes / 1_000.0)} KB"
    bytes < 1_000_000_000L -> "${trimmed(bytes / 1_000_000.0)} MB"
    else -> "${trimmed(bytes / 1_000_000_000.0)} GB"
}

fun formatLatency(latencyMs: Int, jitterMs: Int): String =
    if (jitterMs > 0) "$latencyMs ms ± $jitterMs ms" else "$latencyMs ms"

fun formatClockTime(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(epochMs))

fun formatPacketLoss(percent: Float): String = "${String.format(Locale.US, "%.1f", percent)}%"

fun ThrottleParams.summary(): String =
    "↓ ${formatSpeedLimit(downloadSpeedKbps)} · ↑ ${formatSpeedLimit(uploadSpeedKbps)} · " +
        "${formatLatency(latencyMs, jitterMs)} · ${formatPacketLoss(packetLossPercent)} loss"

private fun trimmed(value: Double): String =
    String.format(Locale.US, "%.1f", value).removeSuffix(".0")

/**
 * Maps a 0..1 slider position onto speed limits on a logarithmic scale, so slow-network speeds
 * (tens of kbps) are as easy to dial in as broadband ones. The far right end means unlimited.
 */
object SpeedScale {
    const val MIN_KBPS = 10
    const val MAX_KBPS = 50_000
    const val UNLIMITED = 0

    private const val MAX_FINITE_POSITION = 0.95f
    private val RANGE = ln(MAX_KBPS.toDouble() / MIN_KBPS)

    fun toPosition(kbps: Int): Float {
        if (kbps <= 0) return 1f
        val clamped = kbps.coerceIn(MIN_KBPS, MAX_KBPS)
        return (ln(clamped.toDouble() / MIN_KBPS) / RANGE * MAX_FINITE_POSITION).toFloat()
    }

    fun toKbps(position: Float): Int {
        if (position > (1f + MAX_FINITE_POSITION) / 2f) return UNLIMITED
        val fraction = (position / MAX_FINITE_POSITION).coerceIn(0f, 1f)
        val raw = MIN_KBPS * (MAX_KBPS.toDouble() / MIN_KBPS).pow(fraction.toDouble())
        // Two significant digits keep the values readable (e.g. 380 kbps, not 383).
        val magnitude = 10.0.pow(floor(log10(raw)) - 1)
        return ((raw / magnitude).roundToInt() * magnitude).roundToInt().coerceIn(MIN_KBPS, MAX_KBPS)
    }
}
