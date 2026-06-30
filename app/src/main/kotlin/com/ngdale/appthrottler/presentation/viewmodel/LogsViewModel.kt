package com.rangandale.appthrottler.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.rangandale.appthrottler.logging.ThrottleLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor() : ViewModel() {
    val entries = ThrottleLogger.entries
    fun clear() = ThrottleLogger.clear()
}
