package com.rangandale.appthrottler.presentation.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rangandale.appthrottler.domain.model.ThrottleConfig
import com.rangandale.appthrottler.presentation.viewmodel.VpnControlViewModel
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(
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
        Text(text = "Profiles", style = MaterialTheme.typography.headlineLarge)
        Text(text = "Load or remove saved Room profiles.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(uiState.savedProfiles, key = { it.id }) { profile ->
                Card(colors = CardDefaults.cardColors()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = profile.name, style = MaterialTheme.typography.titleMedium)
                        Text(text = "${profile.downloadSpeedKbps} kbps down · ${profile.uploadSpeedKbps} kbps up")
                        Text(text = "${profile.latencyMs} ms latency · ${profile.packetLossPercent}% loss")
                        Text(text = "Apps: ${profile.selectedAppPackages.size}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.loadProfile(profile) }) { Text("Load") }
                            Button(onClick = {
                                viewModel.startVpn(
                                    config = ThrottleConfig(
                                        selectedApps = profile.selectedAppPackages,
                                        downloadSpeedKbps = profile.downloadSpeedKbps,
                                        uploadSpeedKbps = profile.uploadSpeedKbps,
                                        latencyMs = profile.latencyMs,
                                        jitterMs = profile.jitterMs,
                                        packetLossPercent = profile.packetLossPercent,
                                    ),
                                    profileName = profile.name,
                                )
                            }) { Text("Start") }
                            Button(onClick = {
                                coroutineScope.launch { viewModel.deleteProfile(profile.id) }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}