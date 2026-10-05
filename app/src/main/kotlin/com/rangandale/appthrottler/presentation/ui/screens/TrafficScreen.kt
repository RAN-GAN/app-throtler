package com.rangandale.appthrottler.presentation.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rangandale.appthrottler.presentation.viewmodel.ConnectionsViewModel
import com.rangandale.appthrottler.presentation.viewmodel.LogsViewModel

/** What the routed apps are doing on the network: their connections, and the app's own log. */
@Composable
fun TrafficScreen(
    contentPadding: PaddingValues,
    connectionsViewModel: ConnectionsViewModel,
    logsViewModel: LogsViewModel,
    vpnRunning: Boolean,
    onBlockApp: (packageName: String, label: String) -> Unit,
    onBlockHost: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        Text(
            text = "Traffic",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
        )
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Connections") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Logs") })
        }
        Column(modifier = Modifier.padding(top = 8.dp)) {
            if (tab == 0) {
                ConnectionsScreen(
                    viewModel = connectionsViewModel,
                    vpnRunning = vpnRunning,
                    onBlockApp = onBlockApp,
                    onBlockHost = onBlockHost,
                )
            } else {
                LogsScreen(viewModel = logsViewModel)
            }
        }
    }
}
