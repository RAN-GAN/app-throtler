package com.rangandale.appthrottler.presentation.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rangandale.appthrottler.data.repository.AppIdentityRepository
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.vpn.capture.CaptureState
import com.rangandale.appthrottler.vpn.capture.PacketCapture
import com.rangandale.appthrottler.vpn.flow.FlowRegistry
import com.rangandale.appthrottler.vpn.flow.FlowSnapshot
import com.rangandale.appthrottler.vpn.flow.FlowState
import com.rangandale.appthrottler.vpn.forward.IpPacket
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One row of the connections list. */
data class ConnectionUi(
    val id: Long,
    val uid: Int,
    val packageName: String?,
    val appLabel: String,
    val protocol: String,
    /** Host name if known, otherwise the address. */
    val destination: String,
    val address: String,
    val port: Int,
    val state: FlowState,
    val bytesUp: Long,
    val bytesDown: Long,
    val startMs: Long,
    val endMs: Long,
    val attempts: Int,
)

data class AppOption(val uid: Int, val label: String)

data class ConnectionsUiState(
    val connections: List<ConnectionUi> = emptyList(),
    /** Apps that have flows this session, for the filter. */
    val apps: List<AppOption> = emptyList(),
    val query: String = "",
    /** UID the list (and a new capture) is limited to; null shows every app. */
    val appFilter: Int? = null,
    val blockedOnly: Boolean = false,
)

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appIdentity: AppIdentityRepository,
) : ViewModel() {

    private data class Filter(val query: String = "", val appFilter: Int? = null, val blockedOnly: Boolean = false)

    private val filter = MutableStateFlow(Filter())

    // The registry is written by the packet threads; sample it instead of observing every packet.
    private val snapshots = flow {
        while (true) {
            emit(FlowRegistry.snapshot().map(::toUi))
            delay(REFRESH_MS)
        }
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<ConnectionsUiState> = combine(snapshots, filter) { all, f ->
        val shown = all.filter { connection ->
            (f.appFilter == null || connection.uid == f.appFilter) &&
                (!f.blockedOnly || connection.state == FlowState.BLOCKED) &&
                (f.query.isBlank() || connection.matches(f.query.trim()))
        }
        ConnectionsUiState(
            connections = shown,
            apps = all.map { AppOption(it.uid, it.appLabel) }.distinct().sortedBy { it.label.lowercase() },
            query = f.query,
            appFilter = f.appFilter,
            blockedOnly = f.blockedOnly,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), ConnectionsUiState())

    val captureState: StateFlow<CaptureState> = PacketCapture.state

    fun setQuery(query: String) = filter.update { it.copy(query = query) }

    fun setAppFilter(uid: Int?) = filter.update { it.copy(appFilter = uid) }

    fun setBlockedOnly(blockedOnly: Boolean) = filter.update { it.copy(blockedOnly = blockedOnly) }

    /** Starts writing the tunnel's packets to [destination], limited to the filtered app if one is set. */
    fun startCapture(destination: Uri) {
        val uid = filter.value.appFilter
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val out = context.contentResolver.openOutputStream(destination)
                        ?: return@withContext "Could not open the chosen file"
                    PacketCapture.start(out, uidFilter = uid ?: PacketCapture.ALL_APPS)
                    null
                } catch (e: Exception) {
                    "Could not start the capture: ${e.message}"
                }
            }
            if (error != null) ThrottleLogger.e(TAG, error) else ThrottleLogger.i(TAG, "Capture started")
        }
    }

    fun stopCapture() {
        viewModelScope.launch(Dispatchers.IO) { PacketCapture.stop() }
    }

    private fun toUi(flow: FlowSnapshot): ConnectionUi {
        val identity = appIdentity.identify(flow.uid)
        val address = IpPacket.addressOf(flow.remoteIp).hostAddress.orEmpty()
        return ConnectionUi(
            id = flow.id,
            uid = flow.uid,
            packageName = identity.packageName,
            appLabel = identity.label,
            protocol = if (flow.protocol == IpPacket.PROTO_TCP) "TCP" else "UDP",
            destination = flow.hostname ?: address,
            address = address,
            port = flow.remotePort,
            state = flow.state,
            bytesUp = flow.bytesUp,
            bytesDown = flow.bytesDown,
            startMs = flow.startMs,
            endMs = flow.endMs,
            attempts = flow.attempts,
        )
    }

    private fun ConnectionUi.matches(query: String): Boolean =
        appLabel.contains(query, ignoreCase = true) ||
            destination.contains(query, ignoreCase = true) ||
            address.contains(query) ||
            port.toString() == query

    private companion object {
        const val TAG = "Capture"
        const val REFRESH_MS = 1_000L
    }
}
