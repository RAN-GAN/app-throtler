package com.rangandale.appthrottler.presentation.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rangandale.appthrottler.domain.model.PresetProfiles
import com.rangandale.appthrottler.presentation.viewmodel.VpnControlViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    viewModel: VpnControlViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Settings", style = MaterialTheme.typography.headlineLarge)
        OutlinedTextField(
            value = uiState.profileName,
            onValueChange = viewModel::updateProfileName,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Profile name") },
        )
        Text(text = "Download ${uiState.currentConfig.downloadSpeedKbps} kbps")
        Slider(
            value = uiState.currentConfig.downloadSpeedKbps.toFloat(),
            onValueChange = { viewModel.updateDownloadSpeed(it.toInt()) },
            valueRange = 10f..50_000f,
        )
        Text(text = "Upload ${uiState.currentConfig.uploadSpeedKbps} kbps")
        Slider(
            value = uiState.currentConfig.uploadSpeedKbps.toFloat(),
            onValueChange = { viewModel.updateUploadSpeed(it.toInt()) },
            valueRange = 10f..50_000f,
        )
        Text(text = "Latency ${uiState.currentConfig.latencyMs} ms")
        Slider(
            value = uiState.currentConfig.latencyMs.toFloat(),
            onValueChange = { viewModel.updateLatency(it.toInt()) },
            valueRange = 0f..500f,
        )
        Text(text = "Jitter ${uiState.currentConfig.jitterMs} ms")
        Slider(
            value = uiState.currentConfig.jitterMs.toFloat(),
            onValueChange = { viewModel.updateJitter(it.toInt()) },
            valueRange = 0f..100f,
        )
        Text(text = "Packet loss ${uiState.currentConfig.packetLossPercent.toInt()}%")
        Slider(
            value = uiState.currentConfig.packetLossPercent,
            onValueChange = { viewModel.updatePacketLoss(it) },
            valueRange = 0f..10f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.applyPreset(PresetProfiles.twoG) }) { Text("2G") }
            Button(onClick = { viewModel.applyPreset(PresetProfiles.threeG) }) { Text("3G") }
            Button(onClick = { viewModel.applyPreset(PresetProfiles.fourG) }) { Text("4G") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.applyPreset(PresetProfiles.slowWifi) }) { Text("Slow Wi-Fi") }
            Button(onClick = viewModel::startCurrentVpn) { Text("Start Throttling") }
        }
        Button(onClick = {
            coroutineScope.launch { viewModel.saveCurrentProfile() }
        }) { Text("Save Profile") }
        Card(colors = CardDefaults.cardColors()) {
            Text(
                modifier = Modifier.padding(16.dp),
                text = "Saved profiles appear below and can be loaded into this editor.",
            )
        }
    }
}