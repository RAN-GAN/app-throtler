package com.ngdale.appthrottler.presentation.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ngdale.appthrottler.presentation.viewmodel.AppListViewModel
import com.ngdale.appthrottler.presentation.viewmodel.VpnControlViewModel

@Composable
fun AppListScreen(
    contentPadding: PaddingValues,
    appListViewModel: AppListViewModel,
    vpnViewModel: VpnControlViewModel,
) {
    val uiState by appListViewModel.uiState.collectAsState()
    val vpnState by vpnViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        appListViewModel.loadApps()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Apps", style = MaterialTheme.typography.headlineLarge)
        Text(text = "Select the apps you want routed through the VPN.")
        Text(text = "Selected: ${uiState.selectedPackages.size}")
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uiState.apps.filter { app ->
                uiState.query.isBlank() ||
                    app.appName.contains(uiState.query, ignoreCase = true) ||
                    app.packageName.contains(uiState.query, ignoreCase = true)
            }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vpnViewModel.toggleSelectedApp(app.packageName) }
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(
                        checked = vpnState.currentConfig.selectedApps.contains(app.packageName),
                        onCheckedChange = { vpnViewModel.toggleSelectedApp(app.packageName) },
                    )
                    Column {
                        Text(text = app.appName, style = MaterialTheme.typography.titleMedium)
                        Text(text = app.packageName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}