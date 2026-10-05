package com.rangandale.appthrottler.ui.navigation

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rangandale.appthrottler.presentation.ui.screens.AppListScreen
import com.rangandale.appthrottler.presentation.ui.screens.HomeScreen
import com.rangandale.appthrottler.presentation.ui.screens.ProfilesScreen
import com.rangandale.appthrottler.presentation.ui.screens.SettingsScreen
import com.rangandale.appthrottler.presentation.ui.screens.TrafficScreen
import com.rangandale.appthrottler.presentation.viewmodel.AppListViewModel
import com.rangandale.appthrottler.presentation.viewmodel.ConnectionsViewModel
import com.rangandale.appthrottler.presentation.viewmodel.LogsViewModel
import com.rangandale.appthrottler.presentation.viewmodel.VpnControlViewModel

@Composable
fun AppThrottlerApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val vpnViewModel = hiltViewModel<VpnControlViewModel>()
    val appListViewModel = hiltViewModel<AppListViewModel>()
    val logsViewModel = hiltViewModel<LogsViewModel>()
    val connectionsViewModel = hiltViewModel<ConnectionsViewModel>()

    val uiState by vpnViewModel.uiState.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: NavRoute.Home.route
    val snackbarHostState = remember { SnackbarHostState() }

    // The VPN consent dialog can be triggered from any screen, so it is handled here.
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        vpnViewModel.onVpnPermissionResult(granted = result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(uiState.vpnPermissionIntent) {
        uiState.vpnPermissionIntent?.let { intent ->
            vpnViewModel.onVpnPermissionRequested()
            vpnPermissionLauncher.launch(intent)
        }
    }

    // Ask for the notification permission once before the first start; the VPN starts either
    // way, the status notification is just hidden if the user declines.
    val context = LocalContext.current
    var notificationPermissionAsked by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        vpnViewModel.startCurrentVpn()
    }
    val startVpn: () -> Unit = {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission && !notificationPermissionAsked) {
            notificationPermissionAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vpnViewModel.startCurrentVpn()
        }
    }

    LaunchedEffect(uiState.message) {
        uiState.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            vpnViewModel.messageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavRoute.bottomBarRoutes.forEach { route ->
                    NavigationBarItem(
                        selected = currentRoute == route.route,
                        onClick = { navController.navigateToTab(route) },
                        icon = { Icon(route.icon, contentDescription = null) },
                        label = { Text(route.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoute.Home.route,
        ) {
            composable(NavRoute.Home.route) {
                HomeScreen(
                    contentPadding = innerPadding,
                    viewModel = vpnViewModel,
                    onStartVpn = startVpn,
                    onChooseApps = { navController.navigateToTab(NavRoute.Apps) },
                    onEditSettings = { navController.navigateToTab(NavRoute.Settings) },
                )
            }
            composable(NavRoute.Apps.route) {
                AppListScreen(
                    contentPadding = innerPadding,
                    appListViewModel = appListViewModel,
                    vpnViewModel = vpnViewModel,
                )
            }
            composable(NavRoute.Settings.route) {
                SettingsScreen(contentPadding = innerPadding, viewModel = vpnViewModel, onStartVpn = startVpn)
            }
            composable(NavRoute.Profiles.route) {
                ProfilesScreen(contentPadding = innerPadding, viewModel = vpnViewModel, onStartVpn = startVpn)
            }
            composable(NavRoute.Traffic.route) {
                TrafficScreen(
                    contentPadding = innerPadding,
                    connectionsViewModel = connectionsViewModel,
                    logsViewModel = logsViewModel,
                    vpnRunning = uiState.isVpnRunning,
                )
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: NavRoute) {
    navigate(route.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
