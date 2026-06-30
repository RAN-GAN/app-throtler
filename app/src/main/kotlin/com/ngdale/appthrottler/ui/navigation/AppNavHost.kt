package com.ngdale.appthrottler.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.ngdale.appthrottler.presentation.ui.screens.AppListScreen
import com.ngdale.appthrottler.presentation.ui.screens.HomeScreen
import com.ngdale.appthrottler.presentation.ui.screens.ProfilesScreen
import com.ngdale.appthrottler.presentation.ui.screens.SettingsScreen
import com.ngdale.appthrottler.presentation.viewmodel.AppListViewModel
import com.ngdale.appthrottler.presentation.viewmodel.VpnControlViewModel

@Composable
fun AppThrottlerApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val vpnViewModel = hiltViewModel<com.ngdale.appthrottler.presentation.viewmodel.VpnControlViewModel>()
    val appListViewModel = hiltViewModel<AppListViewModel>()
    var currentRoute by remember { mutableStateOf(NavRoute.Home.route) }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                NavRoute.bottomBarRoutes.forEach { route ->
                    NavigationBarItem(
                        selected = currentRoute == route.route,
                        onClick = {
                            currentRoute = route.route
                            navController.navigate(route.route) {
                                launchSingleTop = true
                                popUpTo(NavRoute.Home.route) { saveState = true }
                                restoreState = true
                            }
                        },
                        icon = {},
                        label = { Text(route.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        AppNavHost(
            innerPadding = innerPadding,
            navController = navController,
            vpnViewModel = vpnViewModel,
            appListViewModel = appListViewModel,
            onRouteChanged = { currentRoute = it },
        )
    }
}

@Composable
private fun AppNavHost(
    innerPadding: PaddingValues,
    navController: androidx.navigation.NavHostController,
    vpnViewModel: VpnControlViewModel,
    appListViewModel: AppListViewModel,
    onRouteChanged: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        NavHost(
            navController = navController,
            startDestination = NavRoute.Home.route,
        ) {
            composable(NavRoute.Home.route) {
                onRouteChanged(NavRoute.Home.route)
                HomeScreen(contentPadding = innerPadding, viewModel = vpnViewModel)
            }
            composable(NavRoute.Apps.route) {
                onRouteChanged(NavRoute.Apps.route)
                AppListScreen(
                    contentPadding = innerPadding,
                    appListViewModel = appListViewModel,
                    vpnViewModel = vpnViewModel,
                )
            }
            composable(NavRoute.Settings.route) {
                onRouteChanged(NavRoute.Settings.route)
                SettingsScreen(contentPadding = innerPadding, viewModel = vpnViewModel)
            }
            composable(NavRoute.Profiles.route) {
                onRouteChanged(NavRoute.Profiles.route)
                ProfilesScreen(contentPadding = innerPadding, viewModel = vpnViewModel)
            }
        }
    }
}