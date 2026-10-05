package com.rangandale.appthrottler.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.rangandale.appthrottler.control.VpnController
import com.rangandale.appthrottler.data.repository.CurrentConfigRepository
import com.rangandale.appthrottler.data.repository.ProfileRepository
import com.rangandale.appthrottler.data.repository.ScenarioRepository
import com.rangandale.appthrottler.data.repository.StatsRepository
import com.rangandale.appthrottler.domain.model.AppRule
import com.rangandale.appthrottler.domain.model.NetworkStats
import com.rangandale.appthrottler.domain.model.PresetProfiles
import com.rangandale.appthrottler.domain.model.Scenario
import com.rangandale.appthrottler.domain.model.ScenarioStatus
import com.rangandale.appthrottler.domain.model.asStep
import com.rangandale.appthrottler.domain.model.ThrottleConfig
import com.rangandale.appthrottler.domain.model.ThrottleParams
import com.rangandale.appthrottler.domain.model.ThrottleProfile
import com.rangandale.appthrottler.logging.ThrottleLogger
import com.rangandale.appthrottler.vpn.flow.FlowPolicy
import com.rangandale.appthrottler.vpn.ThrottleVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class VpnUiState(
    /** The config being edited; becomes active on the next start/apply. */
    val currentConfig: ThrottleConfig = ThrottleConfig(),
    /** The config the tunnel is running with, or null while stopped. */
    val activeConfig: ThrottleConfig? = null,
    /** Name of the profile [currentConfig] was loaded from; blank once it was customised. */
    val profileName: String = "",
    val savedProfiles: List<ThrottleProfile> = emptyList(),
    val networkStats: NetworkStats = NetworkStats(),
    /** Recent transfer rates, oldest first, for the Home chart. */
    val rateHistory: List<RateSample> = emptyList(),
    /** The scenario step currently overriding the settings, if any. */
    val scenario: ScenarioStatus? = null,
    /** Scenarios the user created; built-in ones are listed separately. */
    val customScenarios: List<Scenario> = emptyList(),
    /** Non-null while the system VPN consent dialog needs to be shown. */
    val vpnPermissionIntent: Intent? = null,
    /** One-shot message for the snackbar. */
    val message: String? = null,
) {
    val isVpnRunning: Boolean get() = activeConfig != null
    val hasUnappliedChanges: Boolean get() = activeConfig != null && activeConfig != currentConfig
}

data class RateSample(val downloadBytesPerSecond: Long, val uploadBytesPerSecond: Long)

/** Everything a tester would hand to a colleague to reproduce a setup. */
data class SetupBundle(
    val version: Int = 1,
    val config: ThrottleConfig = ThrottleConfig(),
    val profileName: String = "",
    val profiles: List<ThrottleProfile> = emptyList(),
    val scenarios: List<Scenario> = emptyList(),
)

@HiltViewModel
class VpnControlViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val currentConfigRepository: CurrentConfigRepository,
    private val statsRepository: StatsRepository,
    private val scenarioRepository: ScenarioRepository,
    private val controller: VpnController,
    private val gson: Gson,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VpnUiState())
    val uiState: StateFlow<VpnUiState> = _uiState.asStateFlow()

    private var startWatchdog: Job? = null

    init {
        viewModelScope.launch {
            val (config, name) = withContext(Dispatchers.IO) {
                currentConfigRepository.load() to currentConfigRepository.loadProfileName()
            }
            _uiState.update { it.copy(currentConfig = config, profileName = name) }
        }
        // The service is the source of truth for whether (and with what) the VPN is running.
        viewModelScope.launch {
            ThrottleVpnService.activeConfig.collectLatest { active ->
                _uiState.update { it.copy(activeConfig = active) }
                if (active != null) {
                    statsRepository.observe().collect { stats ->
                        _uiState.update {
                            val sample = RateSample(stats.downloadBytesPerSecond, stats.uploadBytesPerSecond)
                            it.copy(networkStats = stats, rateHistory = (it.rateHistory + sample).takeLast(HISTORY_SAMPLES))
                        }
                    }
                } else {
                    // Keep the session totals on screen, but nothing is flowing any more.
                    _uiState.update {
                        it.copy(
                            networkStats = it.networkStats.copy(
                                downloadBytesPerSecond = 0,
                                uploadBytesPerSecond = 0,
                            ),
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            profileRepository.getAllProfiles().collect { profiles ->
                _uiState.update { it.copy(savedProfiles = profiles) }
            }
        }
        viewModelScope.launch {
            ThrottleVpnService.scenario.collect { status -> _uiState.update { it.copy(scenario = status) } }
        }
        viewModelScope.launch {
            scenarioRepository.scenarios.collect { scenarios -> _uiState.update { it.copy(customScenarios = scenarios) } }
        }
    }

    /** Starts the VPN with the current config, or re-applies it if the VPN is already running. */
    fun startCurrentVpn() {
        val config = uiState.value.currentConfig
        if (config.selectedApps.isEmpty()) {
            showMessage("Select at least one app to throttle")
            return
        }
        val permissionIntent = controller.consentIntent()
        if (permissionIntent != null) {
            _uiState.update { it.copy(vpnPermissionIntent = permissionIntent) }
            return
        }
        startThrottleService(config)
    }

    /** The consent dialog was launched; clear the request so it is not launched twice. */
    fun onVpnPermissionRequested() {
        _uiState.update { it.copy(vpnPermissionIntent = null) }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        if (granted) {
            startCurrentVpn()
        } else {
            showMessage("VPN permission is required to throttle apps")
        }
    }

    fun stopVpn() {
        startWatchdog?.cancel()
        ThrottleLogger.i(TAG, "Stop requested")
        controller.stop()
    }

    // --- Network events and scenarios (only meaningful while the VPN runs) ---

    fun runScenario(scenario: Scenario) {
        if (!uiState.value.isVpnRunning) {
            showMessage("Start the VPN first")
            return
        }
        controller.runScenario(scenario)
    }

    /** Puts every routed app on [profile] for [seconds], then returns to the configured values. */
    fun runEvent(profile: ThrottleProfile, seconds: Int) =
        runScenario(Scenario("${profile.name} for $seconds s", listOf(profile.asStep(seconds))))

    fun goOffline(seconds: Int) = runEvent(PresetProfiles.offline, seconds)

    fun cancelScenario() = controller.cancelScenario()

    fun resetConnections() {
        controller.resetConnections()
        showMessage("All connections were reset")
    }

    fun saveScenario(scenario: Scenario) {
        if (scenario.name.isBlank() || scenario.steps.isEmpty()) return
        scenarioRepository.save(scenario.copy(name = scenario.name.trim()))
        showMessage("Saved scenario \"${scenario.name.trim()}\"")
    }

    fun deleteScenario(scenario: Scenario) {
        scenarioRepository.delete(scenario.name)
        showMessage("Deleted scenario \"${scenario.name}\"")
    }

    // --- Rules created from the connections list ---

    /** Blocks the app behind a connection, if it is one of the routed apps. */
    fun blockApp(packageName: String, label: String) {
        if (packageName !in uiState.value.currentConfig.selectedApps) {
            showMessage("$label is not one of the selected apps")
            return
        }
        updateAppRule(packageName) { it.copy(block = true, blockOnWifi = true, blockOnMobile = true, onlyWhenScreenOff = false) }
        showMessage(if (uiState.value.isVpnRunning) "$label will be blocked once you press Apply" else "$label will be blocked")
    }

    fun blockHost(host: String) {
        if (addBlockedDomain(host)) {
            showMessage(if (uiState.value.isVpnRunning) "$host will be blocked once you press Apply" else "$host will be blocked")
        }
    }

    // --- Export and import ---

    fun exportSetup(destination: Uri) {
        val state = uiState.value
        val bundle = SetupBundle(
            config = state.currentConfig,
            profileName = state.profileName,
            profiles = state.savedProfiles,
            scenarios = state.customScenarios,
        )
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(destination)!!.use { it.write(gson.toJson(bundle).toByteArray()) }
                }.isSuccess
            }
            showMessage(if (ok) "Setup exported" else "Could not write the file")
        }
    }

    /** Replaces the settings, rules and scenarios with the file's, and adds its profiles. */
    fun importSetup(source: Uri) {
        viewModelScope.launch {
            val bundle = withContext(Dispatchers.IO) {
                runCatching {
                    val json = context.contentResolver.openInputStream(source)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                    gson.fromJson(json, SetupBundle::class.java)
                }.getOrNull()
            }
            // Gson leaves fields that are missing from the file as null, whatever Kotlin says.
            @Suppress("SENSELESS_COMPARISON")
            if (bundle == null || bundle.config == null || bundle.config.selectedApps == null) {
                showMessage("That file is not an App Throttler setup")
                return@launch
            }
            val existing = uiState.value.savedProfiles.associateBy { it.name }
            (bundle.profiles as List<ThrottleProfile>?).orEmpty().forEach { profile ->
                profileRepository.save(profile.copy(id = existing[profile.name]?.id ?: 0L, isPreset = false))
            }
            scenarioRepository.replaceAll(
                (uiState.value.customScenarios.associateBy { it.name } + (bundle.scenarios as List<Scenario>?).orEmpty().associateBy { it.name }).values.toList(),
            )
            val config = bundle.config.copy(
                appRules = (bundle.config.appRules as List<AppRule>?).orEmpty(),
                blockedDomains = (bundle.config.blockedDomains as List<String>?).orEmpty(),
            )
            updateState { it.copy(currentConfig = config, profileName = (bundle.profileName as String?).orEmpty()) }
            showMessage("Setup imported")
        }
    }

    fun messageShown() {
        _uiState.update { it.copy(message = null) }
    }

    fun updateDownloadSpeed(value: Int) = editConfig { it.copy(downloadSpeedKbps = value) }

    fun updateUploadSpeed(value: Int) = editConfig { it.copy(uploadSpeedKbps = value) }

    fun updateLatency(value: Int) = editConfig { it.copy(latencyMs = value) }

    fun updateJitter(value: Int) = editConfig { it.copy(jitterMs = value) }

    fun updatePacketLoss(value: Float) = editConfig { it.copy(packetLossPercent = value) }

    fun toggleSelectedApp(packageName: String) {
        updateState { state ->
            val config = state.currentConfig
            val selected = config.selectedApps.toMutableSet()
            if (!selected.add(packageName)) selected.remove(packageName)
            state.copy(
                currentConfig = config.copy(
                    selectedApps = selected.sorted(),
                    // A rule only means something while its app is routed.
                    appRules = config.appRules.filter { it.packageName in selected },
                ),
            )
        }
    }

    fun clearSelectedApps() {
        updateState { it.copy(currentConfig = it.currentConfig.copy(selectedApps = emptyList(), appRules = emptyList())) }
    }

    /** Changes the rule of a routed app; a rule that says nothing special is dropped. */
    fun updateAppRule(packageName: String, transform: (AppRule) -> AppRule) {
        updateState { state ->
            val config = state.currentConfig
            if (packageName !in config.selectedApps) return@updateState state
            val updated = transform(config.ruleFor(packageName) ?: AppRule(packageName))
            val others = config.appRules.filter { it.packageName != packageName }
            val rules = if (updated.isDefault) others else (others + updated).sortedBy { it.packageName }
            state.copy(currentConfig = config.copy(appRules = rules))
        }
    }

    /** Gives a routed app its own throttle values, copied from [profile]; null returns it to the session settings. */
    fun setAppProfile(packageName: String, profile: ThrottleProfile?) {
        updateAppRule(packageName) { rule ->
            rule.copy(
                throttle = profile?.let {
                    ThrottleParams(
                        it.downloadSpeedKbps, it.uploadSpeedKbps, it.latencyMs, it.jitterMs, it.packetLossPercent,
                    )
                },
                throttleName = profile?.name,
            )
        }
    }

    fun addBlockedDomain(input: String): Boolean {
        val domain = FlowPolicy.normalizeDomain(input)
        if (domain == null || '.' !in domain) {
            showMessage("Enter a domain such as example.com")
            return false
        }
        updateState { state ->
            val domains = (state.currentConfig.blockedDomains + domain).distinct().sorted()
            state.copy(currentConfig = state.currentConfig.copy(blockedDomains = domains))
        }
        return true
    }

    fun removeBlockedDomain(domain: String) {
        updateState { state ->
            state.copy(currentConfig = state.currentConfig.copy(blockedDomains = state.currentConfig.blockedDomains - domain))
        }
    }

    /**
     * Loads a preset or saved profile into the editor. Profiles without an app list (all
     * presets) keep the apps that are currently selected.
     */
    fun loadProfile(profile: ThrottleProfile) {
        updateState { state ->
            val apps = profile.selectedAppPackages.ifEmpty { state.currentConfig.selectedApps }
            state.copy(
                // Per-app rules and blocked domains are not part of a profile; they stay as set.
                currentConfig = state.currentConfig.copy(
                    selectedApps = apps,
                    downloadSpeedKbps = profile.downloadSpeedKbps,
                    uploadSpeedKbps = profile.uploadSpeedKbps,
                    latencyMs = profile.latencyMs,
                    jitterMs = profile.jitterMs,
                    packetLossPercent = profile.packetLossPercent,
                    appRules = state.currentConfig.appRules.filter { it.packageName in apps },
                ),
                profileName = profile.name,
            )
        }
    }

    /** Saves the current config under [name], replacing a saved profile with the same name. */
    fun saveCurrentProfile(name: String) {
        val profileName = name.trim()
        if (profileName.isEmpty()) return
        val state = uiState.value
        val existingId = state.savedProfiles.firstOrNull { it.name == profileName }?.id ?: 0L
        // Launched in viewModelScope so navigating away cannot cancel the write.
        viewModelScope.launch {
            profileRepository.save(
                ThrottleProfile(
                    id = existingId,
                    name = profileName,
                    downloadSpeedKbps = state.currentConfig.downloadSpeedKbps,
                    uploadSpeedKbps = state.currentConfig.uploadSpeedKbps,
                    latencyMs = state.currentConfig.latencyMs,
                    jitterMs = state.currentConfig.jitterMs,
                    packetLossPercent = state.currentConfig.packetLossPercent,
                    selectedAppPackages = state.currentConfig.selectedApps,
                    isPreset = false,
                ),
            )
            updateState { it.copy(profileName = profileName) }
            showMessage("Saved profile \"$profileName\"")
        }
    }

    fun deleteProfile(profile: ThrottleProfile) {
        viewModelScope.launch {
            profileRepository.delete(profile.id)
            // The editor keeps its values but no longer corresponds to a saved profile.
            updateState { if (it.profileName == profile.name) it.copy(profileName = "") else it }
            showMessage("Deleted profile \"${profile.name}\"")
        }
    }

    private fun startThrottleService(config: ThrottleConfig) {
        if (!controller.start(config)) {
            showMessage("Could not start the VPN — see Logs")
            return
        }
        // The service reports success through activeConfig; surface it if that never happens.
        startWatchdog?.cancel()
        startWatchdog = viewModelScope.launch {
            val started = withTimeoutOrNull(START_TIMEOUT_MS) {
                ThrottleVpnService.activeConfig.first { it == config }
            }
            if (started == null) showMessage("Could not start the VPN — see Logs")
        }
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    /** Edits a throttle value; the result no longer matches the profile it was loaded from. */
    private inline fun editConfig(crossinline transform: (ThrottleConfig) -> ThrottleConfig) {
        updateState { it.copy(currentConfig = transform(it.currentConfig), profileName = "") }
    }

    private inline fun updateState(transform: (VpnUiState) -> VpnUiState) {
        _uiState.update(transform)
        val state = _uiState.value
        currentConfigRepository.save(state.currentConfig, state.profileName)
    }

    private companion object {
        const val TAG = "VpnControlViewModel"
        const val START_TIMEOUT_MS = 5_000L
        const val HISTORY_SAMPLES = 60
    }
}
