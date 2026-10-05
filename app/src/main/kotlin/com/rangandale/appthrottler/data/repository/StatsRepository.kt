package com.rangandale.appthrottler.data.repository

import com.rangandale.appthrottler.domain.model.AppTraffic
import com.rangandale.appthrottler.domain.model.NetworkStats
import com.rangandale.appthrottler.vpn.TrafficCounters
import com.rangandale.appthrottler.vpn.flow.FlowRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsRepository @Inject constructor(
    private val appIdentity: AppIdentityRepository,
) {

    /** Samples the VPN traffic counters once per [POLL_INTERVAL_MS] for as long as it is collected. */
    fun observe(): Flow<NetworkStats> = flow {
        var lastIn = TrafficCounters.totalBytesIn
        var lastOut = TrafficCounters.totalBytesOut
        var lastNs = System.nanoTime()
        emit(NetworkStats(bytesIn = lastIn, bytesOut = lastOut))

        while (true) {
            delay(POLL_INTERVAL_MS)
            val bytesIn = TrafficCounters.totalBytesIn
            val bytesOut = TrafficCounters.totalBytesOut
            val now = System.nanoTime()
            val elapsedNs = (now - lastNs).coerceAtLeast(1L)
            // Counters restart from zero when the tunnel is rebuilt.
            val deltaIn = (bytesIn - lastIn).coerceAtLeast(0L)
            val deltaOut = (bytesOut - lastOut).coerceAtLeast(0L)
            emit(
                NetworkStats(
                    bytesIn = bytesIn,
                    bytesOut = bytesOut,
                    downloadBytesPerSecond = deltaIn * NS_PER_SECOND / elapsedNs,
                    uploadBytesPerSecond = deltaOut * NS_PER_SECOND / elapsedNs,
                    apps = appTraffic(),
                    blockedAttempts = FlowRegistry.totalBlockedAttempts,
                ),
            )
            lastIn = bytesIn
            lastOut = bytesOut
            lastNs = now
        }
    }

    private fun appTraffic(): List<AppTraffic> = FlowRegistry.appTotals()
        .filter { it.bytesUp + it.bytesDown > 0 }
        .sortedByDescending { it.bytesUp + it.bytesDown }
        .map { totals ->
            val identity = appIdentity.identify(totals.uid)
            AppTraffic(totals.uid, identity.packageName, identity.label, bytesIn = totals.bytesDown, bytesOut = totals.bytesUp)
        }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val NS_PER_SECOND = 1_000_000_000L
    }
}
