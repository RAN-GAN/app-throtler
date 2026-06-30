package com.ngdale.appthrottler.ui.navigation

sealed class NavRoute(val route: String, val title: String) {
    data object Home : NavRoute("home", "Home")
    data object Apps : NavRoute("apps", "Apps")
    data object Settings : NavRoute("settings", "Settings")
    data object Profiles : NavRoute("profiles", "Profiles")

    companion object {
        val bottomBarRoutes = listOf(Home, Apps, Settings, Profiles)
    }
}