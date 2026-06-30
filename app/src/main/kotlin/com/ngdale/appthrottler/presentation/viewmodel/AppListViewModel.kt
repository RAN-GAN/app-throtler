package com.ngdale.appthrottler.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.ngdale.appthrottler.data.repository.AppRepository
import com.ngdale.appthrottler.domain.model.AppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppListUiState(
    val apps: List<AppInfo> = emptyList(),
    val selectedPackages: Set<String> = emptySet(),
    val query: String = "",
)

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val appRepository: AppRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    fun loadApps() {
        viewModelScope.launch {
            appRepository.getInstalledApps().collect { apps ->
                _uiState.value = _uiState.value.copy(apps = apps)
            }
        }
    }

    fun toggleApp(packageName: String) {
        val selected = _uiState.value.selectedPackages.toMutableSet()
        if (!selected.add(packageName)) {
            selected.remove(packageName)
        }
        _uiState.value = _uiState.value.copy(selectedPackages = selected)
    }

    fun setQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }
}