package com.rangandale.appthrottler.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

sealed class NavRoute(val route: String, val title: String, val icon: ImageVector) {
    data object Home : NavRoute("home", "Home", Icons.Default.Home)
    data object Apps : NavRoute("apps", "Apps", Icons.AutoMirrored.Filled.List)
    data object Settings : NavRoute("settings", "Settings", Icons.Default.Settings)
    data object Profiles : NavRoute("profiles", "Profiles", Icons.Default.Star)
    data object Logs : NavRoute("logs", "Logs", Icons.Default.Info)

    companion object {
        val bottomBarRoutes: List<NavRoute>
            get() = listOf(Home, Apps, Settings, Profiles, Logs)
    }
}
