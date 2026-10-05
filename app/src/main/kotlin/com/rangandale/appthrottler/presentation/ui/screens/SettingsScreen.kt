package com.rangandale.appthrottler.presentation.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rangandale.appthrottler.domain.model.PresetProfiles
import com.rangandale.appthrottler.presentation.ui.SpeedScale
import com.rangandale.appthrottler.presentation.ui.components.SliderControl
import com.rangandale.appthrottler.presentation.ui.formatPacketLoss
import com.rangandale.appthrottler.presentation.ui.formatSpeedLimit
import com.rangandale.appthrottler.presentation.viewmodel.VpnControlViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    viewModel: VpnControlViewModel,
    onStartVpn: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val config = uiState.currentConfig
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineLarge)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Quick presets", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PresetProfiles.all.forEach { preset ->
                    FilterChip(
                        selected = uiState.profileName == preset.name,
                        onClick = { viewModel.loadProfile(preset) },
                        label = { Text(preset.name) },
                    )
                }
            }
        }

        SliderControl(
            label = "Download speed",
            valueText = formatSpeedLimit(config.downloadSpeedKbps),
            value = SpeedScale.toPosition(config.downloadSpeedKbps),
            onValueChange = { viewModel.updateDownloadSpeed(SpeedScale.toKbps(it)) },
            valueRange = 0f..1f,
        )
        SliderControl(
            label = "Upload speed",
            valueText = formatSpeedLimit(config.uploadSpeedKbps),
            value = SpeedScale.toPosition(config.uploadSpeedKbps),
            onValueChange = { viewModel.updateUploadSpeed(SpeedScale.toKbps(it)) },
            valueRange = 0f..1f,
        )
        SliderControl(
            label = "Latency",
            valueText = "${config.latencyMs} ms",
            value = config.latencyMs.toFloat(),
            onValueChange = { viewModel.updateLatency(it.roundToInt()) },
            valueRange = 0f..MAX_LATENCY_MS,
            supportingText = "Delay added to every round trip",
        )
        SliderControl(
            label = "Jitter",
            valueText = "± ${config.jitterMs} ms",
            value = config.jitterMs.toFloat(),
            onValueChange = { viewModel.updateJitter(it.roundToInt()) },
            valueRange = 0f..MAX_JITTER_MS,
            supportingText = "Random variation of the latency",
        )
        SliderControl(
            label = "Packet loss",
            valueText = formatPacketLoss(config.packetLossPercent),
            value = config.packetLossPercent,
            onValueChange = { viewModel.updatePacketLoss((it * 10).roundToInt() / 10f) },
            valueRange = 0f..MAX_PACKET_LOSS_PERCENT,
        )

        HorizontalDivider()
        BlockedDomains(
            domains = config.blockedDomains,
            onAdd = viewModel::addBlockedDomain,
            onRemove = viewModel::removeBlockedDomain,
        )

        HorizontalDivider()
        ShareSetup(onExport = viewModel::exportSetup, onImport = viewModel::importSetup)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { showSaveDialog = true }, modifier = Modifier.weight(1f)) {
                Text("Save as profile")
            }
            Button(
                onClick = onStartVpn,
                modifier = Modifier.weight(1f),
                enabled = !uiState.isVpnRunning || uiState.hasUnappliedChanges,
            ) {
                Text(
                    when {
                        !uiState.isVpnRunning -> "Start throttling"
                        uiState.hasUnappliedChanges -> "Apply changes"
                        else -> "Applied"
                    },
                )
            }
        }
    }

    if (showSaveDialog) {
        SaveProfileDialog(
            initialName = uiState.profileName.takeUnless { name -> PresetProfiles.all.any { it.name == name } }.orEmpty(),
            existingNames = uiState.savedProfiles.map { it.name },
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                showSaveDialog = false
                viewModel.saveCurrentProfile(name)
            },
        )
    }
}

@Composable
private fun ShareSetup(onExport: (android.net.Uri) -> Unit, onImport: (android.net.Uri) -> Unit) {
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) onExport(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Share this setup", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Saves the settings, app rules, blocked domains, profiles and scenarios to a file. " +
                "Importing replaces the current settings and rules and adds the file's profiles and scenarios.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { exporter.launch("app-throttler-setup.json") }) { Text("Export") }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import") }
        }
    }
}

@Composable
private fun BlockedDomains(domains: List<String>, onAdd: (String) -> Boolean, onRemove: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Blocked domains", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Selected apps cannot reach these domains or their subdomains. " +
                "Apps that use encrypted DNS and hide the host name can get around this.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("example.com") },
                singleLine = true,
            )
            Button(onClick = { if (onAdd(input)) input = "" }, enabled = input.isNotBlank()) { Text("Add") }
        }
        domains.forEach { domain ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = domain, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRemove(domain) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove $domain")
                }
            }
        }
    }
}

@Composable
private fun SaveProfileDialog(
    initialName: String,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save profile") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile name") },
                singleLine = true,
                supportingText = {
                    if (trimmed in existingNames) Text("Replaces the saved profile with this name")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(trimmed) }, enabled = trimmed.isNotEmpty()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private const val MAX_LATENCY_MS = 2_000f
private const val MAX_JITTER_MS = 500f
private const val MAX_PACKET_LOSS_PERCENT = 30f
