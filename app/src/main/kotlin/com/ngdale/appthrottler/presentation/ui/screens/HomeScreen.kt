package com.rangandale.appthrottler.presentation.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rangandale.appthrottler.domain.model.ThrottleConfig
import com.rangandale.appthrottler.presentation.viewmodel.VpnControlViewModel

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    viewModel: VpnControlViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onPermissionGranted(uiState.currentConfig, uiState.profileName.ifBlank { null })
        }
    }

    LaunchedEffect(uiState.vpnPermissionIntent) {
        uiState.vpnPermissionIntent?.let { permissionLauncher.launch(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "App Throttler", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Pick apps, choose a profile, then start the VPN throttle.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = if (uiState.isVpnRunning) "VPN is running" else "VPN is stopped",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (uiState.isVpnRunning) {
                    viewModel.stopVpn()
                } else {
                    viewModel.startCurrentVpn()
                }
            },
        ) {
            Text(text = if (uiState.isVpnRunning) "Stop Throttling" else "Start Throttling")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = viewModel::stopVpn,
            enabled = uiState.isVpnRunning,
        ) {
            Text(text = "Stop VPN")
        }
        Spacer(modifier = Modifier.height(24.dp))
        Card(colors = CardDefaults.cardColors()) {
            Text(
                modifier = Modifier.padding(16.dp),
                text = "Live stats will land here next.",
            )
        }
    }
}