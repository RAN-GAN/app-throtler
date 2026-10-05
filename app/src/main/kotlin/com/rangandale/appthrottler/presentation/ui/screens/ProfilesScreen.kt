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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rangandale.appthrottler.domain.model.BuiltInScenarios
import com.rangandale.appthrottler.domain.model.PresetProfiles
import com.rangandale.appthrottler.domain.model.Scenario
import com.rangandale.appthrottler.domain.model.ScenarioStep
import com.rangandale.appthrottler.domain.model.asStep
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

    var editingScenario by rememberSaveable { mutableStateOf(false) }

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

        item { SectionHeader("Scenarios") }
        item {
            Text(
                text = "A scenario steps through several network conditions while the VPN runs, " +
                    "then returns to your settings.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(uiState.customScenarios, key = { "scenario-${it.name}" }) { scenario ->
            ScenarioCard(
                scenario = scenario,
                canRun = uiState.isVpnRunning,
                onRun = { viewModel.runScenario(scenario) },
                onDelete = { viewModel.deleteScenario(scenario) },
            )
        }
        items(BuiltInScenarios.all, key = { "builtin-${it.name}" }) { scenario ->
            ScenarioCard(
                scenario = scenario,
                canRun = uiState.isVpnRunning,
                onRun = { viewModel.runScenario(scenario) },
                onDelete = null,
            )
        }
        item {
            OutlinedButton(onClick = { editingScenario = true }) { Text("New scenario") }
        }
    }

    if (editingScenario) {
        ScenarioEditor(
            profiles = uiState.savedProfiles + PresetProfiles.all,
            onDismiss = { editingScenario = false },
            onSave = { scenario ->
                editingScenario = false
                viewModel.saveScenario(scenario)
            },
        )
    }
}

@Composable
private fun ScenarioCard(scenario: Scenario, canRun: Boolean, onRun: () -> Unit, onDelete: (() -> Unit)?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = scenario.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 12.dp),
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete ${scenario.name}")
                    }
                }
            }
            Text(text = scenario.steps.joinToString(" → ") { "${it.label} ${it.seconds} s" })
            Text(
                text = "${scenario.totalSeconds} s in total",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onRun, enabled = canRun) { Text(if (canRun) "Run" else "Start the VPN to run") }
            }
        }
    }
}

@Composable
private fun ScenarioEditor(
    profiles: List<ThrottleProfile>,
    onDismiss: () -> Unit,
    onSave: (Scenario) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val steps = remember { mutableStateListOf<ScenarioStep>() }
    var selected by remember { mutableStateOf(profiles.first()) }
    var seconds by remember { mutableStateOf("30") }
    var menuOpen by remember { mutableStateOf(false) }
    val duration = seconds.toIntOrNull()?.takeIf { it in 1..3600 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New scenario") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "${index + 1}. ${step.label} for ${step.seconds} s", modifier = Modifier.weight(1f))
                        IconButton(onClick = { steps.removeAt(index) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove step ${index + 1}")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(onClick = { menuOpen = true }) { Text(selected.name) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.name) },
                                    onClick = { selected = profile; menuOpen = false },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { seconds = it.filter(Char::isDigit).take(4) },
                        label = { Text("Seconds") },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f),
                    )
                }
                OutlinedButton(onClick = { duration?.let { steps.add(selected.asStep(it)) } }, enabled = duration != null) {
                    Text("Add step")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(Scenario(name.trim(), steps.toList())) },
                enabled = name.isNotBlank() && steps.isNotEmpty(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
