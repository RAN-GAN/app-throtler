package com.rangandale.appthrottler.presentation.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rangandale.appthrottler.presentation.ui.components.AppIcon
import com.rangandale.appthrottler.presentation.ui.formatBytes
import com.rangandale.appthrottler.presentation.ui.formatClockTime
import com.rangandale.appthrottler.presentation.viewmodel.ConnectionUi
import com.rangandale.appthrottler.presentation.viewmodel.ConnectionsViewModel
import com.rangandale.appthrottler.vpn.ThrottleVpnService
import com.rangandale.appthrottler.vpn.capture.CaptureState
import com.rangandale.appthrottler.vpn.flow.FlowState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConnectionsScreen(
    viewModel: ConnectionsViewModel,
    vpnRunning: Boolean,
    onBlockApp: (packageName: String, label: String) -> Unit,
    onBlockHost: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val capture by viewModel.captureState.collectAsState()
    var details by remember { mutableStateOf<ConnectionUi?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!ThrottleVpnService.canAttributeFlows) {
            Text(
                text = "Connections cannot be matched to apps before Android 10, so they are all listed as \"Unknown app\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CaptureBar(
            capture = capture,
            vpnRunning = vpnRunning,
            filteredApp = uiState.apps.firstOrNull { it.uid == uiState.appFilter }?.label,
            onStart = viewModel::startCapture,
            onStop = viewModel::stopCapture,
            canExport = uiState.connections.isNotEmpty(),
            onExportCsv = viewModel::exportCsv,
        )

        OutlinedTextField(
            value = uiState.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search app, host, address or port") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var appMenuOpen by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = uiState.appFilter != null,
                    onClick = { appMenuOpen = true },
                    label = { Text(uiState.apps.firstOrNull { it.uid == uiState.appFilter }?.label ?: "All apps") },
                )
                DropdownMenu(expanded = appMenuOpen, onDismissRequest = { appMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("All apps") },
                        onClick = { viewModel.setAppFilter(null); appMenuOpen = false },
                    )
                    uiState.apps.forEach { app ->
                        DropdownMenuItem(
                            text = { Text(app.label) },
                            onClick = { viewModel.setAppFilter(app.uid); appMenuOpen = false },
                        )
                    }
                }
            }
            FilterChip(
                selected = uiState.blockedOnly,
                onClick = { viewModel.setBlockedOnly(!uiState.blockedOnly) },
                label = { Text("Blocked only") },
            )
        }

        if (uiState.connections.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (vpnRunning) "No matching connections yet" else "Start the VPN to see the connections your apps make",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(uiState.connections, key = { it.id }) { connection ->
                    ConnectionRow(connection, onClick = { details = connection })
                }
            }
        }
    }

    details?.let { connection ->
        ConnectionDetails(
            connection = connection,
            onDismiss = { details = null },
            onBlockApp = { connection.packageName?.let { onBlockApp(it, connection.appLabel) }; details = null },
            onBlockHost = { onBlockHost(connection.destination); details = null },
        )
    }
}

@Composable
private fun CaptureBar(
    capture: CaptureState,
    vpnRunning: Boolean,
    filteredApp: String?,
    onStart: (android.net.Uri) -> Unit,
    onStop: () -> Unit,
    canExport: Boolean,
    onExportCsv: (android.net.Uri) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.tcpdump.pcap"),
    ) { uri -> if (uri != null) onStart(uri) }
    val csvPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> if (uri != null) onExportCsv(uri) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                capture.active -> "Capturing${filteredApp?.let { " $it" }.orEmpty()}: ${capture.packets} packets, ${formatBytes(capture.bytes)}" +
                    if (capture.dropped > 0) " (${capture.dropped} missed)" else ""
                capture.error != null -> capture.error
                capture.packets > 0 -> "Saved ${capture.packets} packets, ${formatBytes(capture.bytes)}"
                else -> "Save traffic to a .pcap file for Wireshark"
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (capture.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (capture.active) {
            Button(onClick = onStop) { Text("Stop capture") }
        } else {
            OutlinedButton(
                onClick = { picker.launch("capture-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.pcap") },
                enabled = vpnRunning,
            ) { Text("Capture") }
        }
        OutlinedButton(
            onClick = { csvPicker.launch("connections-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.csv") },
            enabled = canExport,
        ) { Text("CSV") }
    }
}

@Composable
private fun ConnectionRow(connection: ConnectionUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (connection.packageName != null) {
            AppIcon(packageName = connection.packageName, modifier = Modifier.size(32.dp))
        } else {
            Box(modifier = Modifier.size(32.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${connection.destination}:${connection.port}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${connection.appLabel} · ${connection.protocol} · ↓ ${formatBytes(connection.bytesDown)} ↑ ${formatBytes(connection.bytesUp)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stateLabel(connection),
            style = MaterialTheme.typography.labelMedium,
            color = when (connection.state) {
                FlowState.OPEN -> MaterialTheme.colorScheme.primary
                FlowState.BLOCKED, FlowState.FAILED -> MaterialTheme.colorScheme.error
                FlowState.CLOSED -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private fun stateLabel(connection: ConnectionUi): String = when (connection.state) {
    FlowState.OPEN -> "Open"
    FlowState.CLOSED -> "Closed"
    FlowState.FAILED -> "Failed"
    FlowState.BLOCKED -> if (connection.attempts > 1) "Blocked ×${connection.attempts}" else "Blocked"
}

@Composable
private fun ConnectionDetails(
    connection: ConnectionUi,
    onDismiss: () -> Unit,
    onBlockApp: () -> Unit,
    onBlockHost: () -> Unit,
) {
    // An address is not a domain rule; only connections with a known host name can be blocked by host.
    val hasHostName = connection.destination != connection.address
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(connection.destination, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow("App", connection.appLabel)
                connection.packageName?.let { DetailRow("Package", it) }
                DetailRow("Address", "${connection.address}:${connection.port}")
                DetailRow("Protocol", connection.protocol)
                DetailRow("Status", stateLabel(connection))
                DetailRow("Received", formatBytes(connection.bytesDown))
                DetailRow("Sent", formatBytes(connection.bytesUp))
                DetailRow("Started", formatClockTime(connection.startMs))
                if (connection.endMs > 0) DetailRow(if (connection.state == FlowState.BLOCKED) "Last attempt" else "Ended", formatClockTime(connection.endMs))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Row {
                if (connection.packageName != null) TextButton(onClick = onBlockApp) { Text("Block app") }
                if (hasHostName) TextButton(onClick = onBlockHost) { Text("Block host") }
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.35f))
        Text(text = value, modifier = Modifier.weight(0.65f))
    }
}
