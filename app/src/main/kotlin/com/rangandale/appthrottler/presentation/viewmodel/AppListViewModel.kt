package com.rangandale.appthrottler.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.rangandale.appthrottler.data.repository.AppRepository
import com.rangandale.appthrottler.domain.model.AppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppListUiState(
    val apps: List<AppInfo> = emptyList(),
    val isLoading: Boolean = true,
    val query: String = "",
) {
    val filteredApps: List<AppInfo>
        get() = if (query.isBlank()) apps else apps.filter { app ->
            app.appName.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
        }
}

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val appRepository: AppRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppListUiState())
    val uiState: StateFlow<AppListUiState> = _uiState.asStateFlow()

    /** Reloads the installed apps; called whenever the Apps tab is opened. */
    fun refresh() {
        viewModelScope.launch {
            appRepository.getInstalledApps().collect { apps ->
                _uiState.update { it.copy(apps = apps, isLoading = false) }
            }
        }
    }

    fun setQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }
}
