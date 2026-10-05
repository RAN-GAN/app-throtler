package com.rangandale.appthrottler.presentation.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rangandale.appthrottler.domain.model.ThrottleConfig
import com.rangandale.appthrottler.presentation.ui.components.StatChip
import com.rangandale.appthrottler.presentation.ui.formatBytes
import com.rangandale.appthrottler.presentation.ui.formatLatency
import com.rangandale.appthrottler.presentation.ui.formatPacketLoss
import com.rangandale.appthrottler.presentation.ui.formatRate
import com.rangandale.appthrottler.presentation.ui.formatSpeedLimit
import com.rangandale.appthrottler.presentation.viewmodel.VpnControlViewModel

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    viewModel: VpnControlViewModel,
    onStartVpn: () -> Unit,
    onChooseApps: () -> Unit,
    onEditSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val running = uiState.isVpnRunning
    val shownConfig = uiState.activeConfig ?: uiState.currentConfig

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "App Throttler",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        PowerButton(
            running = running,
            onClick = { if (running) viewModel.stopVpn() else onStartVpn() },
        )
        Text(
            text = when {
                running -> "Throttling ${appCount(shownConfig.selectedApps.size)}"
                shownConfig.selectedApps.isEmpty() -> "Choose the apps to throttle, then press Start"
                else -> "Stopped"
            },
            style = MaterialTheme.typography.titleMedium,
        )

        if (uiState.hasUnappliedChanges) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Your changes are not applied to the running VPN yet.",
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onStartVpn) { Text("Apply") }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatChip(
                icon = Icons.Default.KeyboardArrowDown,
                label = "Download",
                value = formatRate(uiState.networkStats.downloadBytesPerSecond),
                caption = "${formatBytes(uiState.networkStats.bytesIn)} total",
                modifier = Modifier.weight(1f),
            )
            StatChip(
                icon = Icons.Default.KeyboardArrowUp,
                label = "Upload",
                value = formatRate(uiState.networkStats.uploadBytesPerSecond),
                caption = "${formatBytes(uiState.networkStats.bytesOut)} total",
                modifier = Modifier.weight(1f),
            )
        }

        ConfigCard(
            title = when {
                running && uiState.hasUnappliedChanges -> "Active settings"
                uiState.profileName.isNotBlank() -> uiState.profileName
                else -> "Custom"
            },
            config = shownConfig,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onChooseApps, modifier = Modifier.weight(1f)) { Text("Choose apps") }
            OutlinedButton(onClick = onEditSettings, modifier = Modifier.weight(1f)) { Text("Edit settings") }
        }
    }
}

@Composable
private fun PowerButton(running: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(144.dp),
        shape = CircleShape,
        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (running) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (running) Icons.Default.Close else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
            )
            Text(text = if (running) "Stop" else "Start", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ConfigCard(title: String, config: ThrottleConfig) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            ConfigRow("Download limit", formatSpeedLimit(config.downloadSpeedKbps))
            ConfigRow("Upload limit", formatSpeedLimit(config.uploadSpeedKbps))
            ConfigRow("Latency", formatLatency(config.latencyMs, config.jitterMs))
            ConfigRow("Packet loss", formatPacketLoss(config.packetLossPercent))
            ConfigRow("Apps", "${config.selectedApps.size} selected")
        }
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value)
    }
}

private fun appCount(count: Int): String = if (count == 1) "1 app" else "$count apps"
