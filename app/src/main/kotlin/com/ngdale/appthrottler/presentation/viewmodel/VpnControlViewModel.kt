package com.ngdale.appthrottler.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ngdale.appthrottler.data.repository.ProfileRepository
import com.ngdale.appthrottler.domain.model.NetworkStats
import com.ngdale.appthrottler.domain.model.ThrottleConfig
import com.ngdale.appthrottler.domain.model.ThrottleProfile
import com.ngdale.appthrottler.vpn.ThrottleVpnService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VpnUiState(
    val isVpnRunning: Boolean = false,
    val vpnPermissionIntent: Intent? = null,
    val currentConfig: ThrottleConfig = ThrottleConfig(),
    val profileName: String = "",
    val savedProfiles: List<ThrottleProfile> = emptyList(),
    val networkStats: NetworkStats = NetworkStats(),
)

@HiltViewModel
class VpnControlViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VpnUiState())
    val uiState: StateFlow<VpnUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            profileRepository.getAllProfiles().collect { profiles ->
                _uiState.update { it.copy(savedProfiles = profiles) }
            }
        }
    }

    fun startCurrentVpn() {
        startVpn(
            config = uiState.value.currentConfig,
            profileName = uiState.value.profileName.ifBlank { null },
        )
    }

    fun startVpn(config: ThrottleConfig, profileName: String? = null) {
        val intent = VpnService.prepare(context)
        if (intent != null) {
            _uiState.update {
                it.copy(
                    vpnPermissionIntent = intent,
                    currentConfig = config,
                    profileName = profileName.orEmpty(),
                )
            }
            return
        }

        startThrottleService(config)

        _uiState.update {
            it.copy(
                isVpnRunning = true,
                vpnPermissionIntent = null,
                currentConfig = config,
                profileName = profileName.orEmpty(),
            )
        }
    }

    fun onPermissionGranted(config: ThrottleConfig, profileName: String? = null) {
        startThrottleService(config)
        _uiState.update {
            it.copy(
                isVpnRunning = true,
                vpnPermissionIntent = null,
                currentConfig = config,
                profileName = profileName.orEmpty(),
            )
        }
    }

    fun stopVpn() {
        context.startService(ThrottleVpnService.stopIntent(context))
        _uiState.update {
            it.copy(
                isVpnRunning = false,
                currentConfig = ThrottleConfig(),
                profileName = "",
            )
        }
    }

    fun updateProfileName(name: String) {
        _uiState.update { it.copy(profileName = name) }
    }

    fun updateDownloadSpeed(value: Int) {
        updateConfig { it.copy(downloadSpeedKbps = value) }
    }

    fun updateUploadSpeed(value: Int) {
        updateConfig { it.copy(uploadSpeedKbps = value) }
    }

    fun updateLatency(value: Int) {
        updateConfig { it.copy(latencyMs = value) }
    }

    fun updateJitter(value: Int) {
        updateConfig { it.copy(jitterMs = value) }
    }

    fun updatePacketLoss(value: Float) {
        updateConfig { it.copy(packetLossPercent = value) }
    }

    fun toggleSelectedApp(packageName: String) {
        updateConfig {
            val selected = it.selectedApps.toMutableSet()
            if (!selected.add(packageName)) {
                selected.remove(packageName)
            }
            it.copy(selectedApps = selected.toList().sorted())
        }
    }

    fun loadProfile(profile: ThrottleProfile) {
        _uiState.update {
            it.copy(
                currentConfig = ThrottleConfig(
                    selectedApps = profile.selectedAppPackages,
                    downloadSpeedKbps = profile.downloadSpeedKbps,
                    uploadSpeedKbps = profile.uploadSpeedKbps,
                    latencyMs = profile.latencyMs,
                    jitterMs = profile.jitterMs,
                    packetLossPercent = profile.packetLossPercent,
                ),
                profileName = profile.name,
            )
        }
    }

    fun applyPreset(profile: ThrottleProfile) {
        loadProfile(profile)
    }

    suspend fun saveCurrentProfile() {
        val state = uiState.value
        val name = state.profileName.ifBlank { "Custom Profile" }
        profileRepository.save(
            ThrottleProfile(
                id = 0,
                name = name,
                downloadSpeedKbps = state.currentConfig.downloadSpeedKbps,
                uploadSpeedKbps = state.currentConfig.uploadSpeedKbps,
                latencyMs = state.currentConfig.latencyMs,
                jitterMs = state.currentConfig.jitterMs,
                packetLossPercent = state.currentConfig.packetLossPercent,
                selectedAppPackages = state.currentConfig.selectedApps,
                isPreset = false,
            ),
        )
    }

    suspend fun deleteProfile(profileId: Long) {
        profileRepository.delete(profileId)
    }

    private fun startThrottleService(config: ThrottleConfig) {
        val serviceIntent = ThrottleVpnService.startIntent(
            context = context,
            selectedApps = ArrayList(config.selectedApps),
            downloadSpeedKbps = config.downloadSpeedKbps,
            uploadSpeedKbps = config.uploadSpeedKbps,
            latencyMs = config.latencyMs,
            jitterMs = config.jitterMs,
            packetLossPercent = config.packetLossPercent,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private inline fun updateConfig(transform: (ThrottleConfig) -> ThrottleConfig) {
        _uiState.update { state ->
            state.copy(currentConfig = transform(state.currentConfig))
        }
    }
}