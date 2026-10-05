package com.rangandale.appthrottler.presentation.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rangandale.appthrottler.domain.model.AppInfo
import com.rangandale.appthrottler.domain.model.AppRule
import com.rangandale.appthrottler.domain.model.PresetProfiles
import com.rangandale.appthrottler.domain.model.ThrottleProfile
import com.rangandale.appthrottler.presentation.ui.components.AppIcon
import com.rangandale.appthrottler.presentation.viewmodel.AppListViewModel
import com.rangandale.appthrottler.presentation.viewmodel.VpnControlViewModel
import com.rangandale.appthrottler.vpn.ThrottleVpnService

@Composable
fun AppListScreen(
    contentPadding: PaddingValues,
    appListViewModel: AppListViewModel,
    vpnViewModel: VpnControlViewModel,
) {
    val uiState by appListViewModel.uiState.collectAsState()
    val vpnState by vpnViewModel.uiState.collectAsState()
    val config = vpnState.currentConfig
    val selected = config.selectedApps
    val apps = uiState.filteredApps
    val blockedCount = config.appRules.count { it.block }
    val profiles = vpnState.savedProfiles + PresetProfiles.all

    // Pick up apps installed or removed since the tab was last opened.
    LaunchedEffect(Unit) {
        appListViewModel.refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(start = 24.dp, end = 24.dp, top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Apps", style = MaterialTheme.typography.headlineLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Only selected apps go through the VPN. ${selected.size} selected" +
                    (if (blockedCount > 0) ", $blockedCount blocked." else "."),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = vpnViewModel::clearSelectedApps, enabled = selected.isNotEmpty()) {
                Text("Clear")
            }
        }

        OutlinedTextField(
            value = uiState.query,
            onValueChange = appListViewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search apps…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
        )

        when {
            uiState.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            apps.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (uiState.query.isBlank()) "No apps found" else "No apps match \"${uiState.query}\"",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        checked = app.packageName in selected,
                        rule = config.ruleFor(app.packageName),
                        profiles = profiles,
                        onToggle = { vpnViewModel.toggleSelectedApp(app.packageName) },
                        onRuleChange = { change -> vpnViewModel.updateAppRule(app.packageName, change) },
                        onProfileChange = { profile -> vpnViewModel.setAppProfile(app.packageName, profile) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: AppInfo,
    checked: Boolean,
    rule: AppRule?,
    profiles: List<ThrottleProfile>,
    onToggle: () -> Unit,
    onRuleChange: ((AppRule) -> AppRule) -> Unit,
    onProfileChange: (ThrottleProfile?) -> Unit,
) {
    Column {
        AppHeader(app, checked, onToggle)
        // Per-app rules need the system to say which app owns a connection (Android 10+).
        if (checked && ThrottleVpnService.canAttributeFlows) {
            AppRuleControls(rule ?: AppRule(app.packageName), profiles, onRuleChange, onProfileChange)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppRuleControls(
    rule: AppRule,
    profiles: List<ThrottleProfile>,
    onRuleChange: ((AppRule) -> AppRule) -> Unit,
    onProfileChange: (ThrottleProfile?) -> Unit,
) {
    // Blocking on neither network is the same as not blocking at all.
    fun AppRule.orUnblocked() =
        if (!blockOnWifi && !blockOnMobile) copy(block = false, blockOnWifi = true, blockOnMobile = true) else this

    // Wraps onto further lines so every option stays visible on narrow screens.
    FlowRow(
        modifier = Modifier.padding(start = 52.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = rule.block,
            onClick = { onRuleChange { it.copy(block = !it.block) } },
            label = { Text("Block") },
        )
        if (rule.block) {
            FilterChip(
                selected = rule.blockOnWifi,
                onClick = { onRuleChange { it.copy(blockOnWifi = !it.blockOnWifi).orUnblocked() } },
                label = { Text("On Wi-Fi") },
            )
            FilterChip(
                selected = rule.blockOnMobile,
                onClick = { onRuleChange { it.copy(blockOnMobile = !it.blockOnMobile).orUnblocked() } },
                label = { Text("On mobile data") },
            )
            FilterChip(
                selected = rule.onlyWhenScreenOff,
                onClick = { onRuleChange { it.copy(onlyWhenScreenOff = !it.onlyWhenScreenOff) } },
                label = { Text("Only when screen is off") },
            )
        } else {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = rule.throttle != null,
                    onClick = { menuOpen = true },
                    label = { Text(rule.throttleName?.let { "Throttle: $it" } ?: "Throttle: session settings") },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Session settings") },
                        onClick = { onProfileChange(null); menuOpen = false },
                    )
                    profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.name) },
                            onClick = { onProfileChange(profile); menuOpen = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(app: AppInfo, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(packageName = app.packageName, modifier = Modifier.size(40.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}
