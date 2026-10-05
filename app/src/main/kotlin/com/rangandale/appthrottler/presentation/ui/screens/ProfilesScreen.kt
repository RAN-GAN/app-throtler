package com.rangandale.appthrottler.presentation.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rangandale.appthrottler.domain.model.PresetProfiles
import com.rangandale.appthrottler.domain.model.ThrottleProfile
import com.rangandale.appthrottler.presentation.ui.formatLatency
import com.rangandale.appthrottler.presentation.ui.formatPacketLoss
import com.rangandale.appthrottler.presentation.ui.formatSpeedLimit
import com.rangandale.appthrottler.presentation.viewmodel.VpnControlViewModel

@Composable
fun ProfilesScreen(
    contentPadding: PaddingValues,
    viewModel: VpnControlViewModel,
    onStartVpn: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val startWith: (ThrottleProfile) -> Unit = { profile ->
        viewModel.loadProfile(profile)
        onStartVpn()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(text = "Profiles", style = MaterialTheme.typography.headlineLarge) }

        item { SectionHeader("Saved") }
        if (uiState.savedProfiles.isEmpty()) {
            item {
                Text(
                    text = "No saved profiles yet. Tune the sliders in Settings and tap \"Save as profile\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(uiState.savedProfiles, key = { it.id }) { profile ->
            SwipeToDelete(onDelete = { viewModel.deleteProfile(profile) }) {
                ProfileCard(
                    profile = profile,
                    isLoaded = uiState.profileName == profile.name,
                    onLoad = { viewModel.loadProfile(profile) },
                    onStart = { startWith(profile) },
                    onDelete = { viewModel.deleteProfile(profile) },
                )
            }
        }

        item { SectionHeader("Presets") }
        items(PresetProfiles.all, key = { "preset-${it.name}" }) { profile ->
            ProfileCard(
                profile = profile,
                isLoaded = uiState.profileName == profile.name,
                onLoad = { viewModel.loadProfile(profile) },
                onStart = { startWith(profile) },
                onDelete = null,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onDelete()
            value != SwipeToDismissBoxValue.Settled
        },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    ) {
        content()
    }
}

@Composable
private fun ProfileCard(
    profile: ThrottleProfile,
    isLoaded: Boolean,
    onLoad: () -> Unit,
    onStart: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp),
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete ${profile.name}")
                    }
                }
            }
            Text(text = "↓ ${formatSpeedLimit(profile.downloadSpeedKbps)} · ↑ ${formatSpeedLimit(profile.uploadSpeedKbps)}")
            Text(
                text = "${formatLatency(profile.latencyMs, profile.jitterMs)} latency · " +
                    "${formatPacketLoss(profile.packetLossPercent)} loss",
            )
            if (!profile.isPreset) {
                Text(
                    text = if (profile.selectedAppPackages.isEmpty()) {
                        "Keeps the currently selected apps"
                    } else {
                        "${profile.selectedAppPackages.size} app(s)"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.padding(top = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onLoad, enabled = !isLoaded) { Text(if (isLoaded) "Loaded" else "Load") }
                Button(onClick = onStart) { Text("Start") }
            }
        }
    }
}
