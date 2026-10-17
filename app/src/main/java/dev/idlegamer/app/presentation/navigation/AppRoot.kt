package dev.idlegamer.app.presentation.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.idlegamer.app.R
import dev.idlegamer.app.domain.model.AuthState
import dev.idlegamer.app.presentation.activity.ActivityDetailsScreen
import dev.idlegamer.app.presentation.auth.LoginScreen
import dev.idlegamer.app.presentation.dashboard.DashboardScreen
import dev.idlegamer.app.presentation.history.HistoryScreen
import dev.idlegamer.app.presentation.library.LibraryScreen
import dev.idlegamer.app.presentation.settings.LicensesScreen
import dev.idlegamer.app.presentation.settings.SettingsScreen

private enum class Tab(val route: String, val label: Int, val icon: ImageVector) {
    DASHBOARD("dashboard", R.string.nav_dashboard, Icons.Filled.Dashboard),
    LIBRARY("library", R.string.nav_library, Icons.Filled.SportsEsports),
    HISTORY("history", R.string.nav_history, Icons.Filled.History),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings),
}

private const val ROUTE_DETAILS = "details"
private const val ROUTE_LICENSES = "licenses"

/** Top-level switch driven only by [AuthState]; screens never decide by themselves who is signed in. */
@Composable
fun AppRoot(authState: AuthState, locked: Boolean, onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            locked -> LockedScreen(onUnlock)
            authState is AuthState.Unknown -> SplashScreen()
            authState is AuthState.Authenticated -> MainScaffold()
            else -> LoginScreen()
        }
    }
}

@Composable
private fun SplashScreen() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        CircularProgressIndicator(Modifier.padding(24.dp))
        Text(stringResource(R.string.splash_loading))
    }
}

@Composable
private fun LockedScreen(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        Button(onClick = onUnlock) { Text(stringResource(R.string.lock_unlock)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val currentTab = Tab.entries.firstOrNull { tab -> destination?.hierarchy?.any { it.route == tab.route } == true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (destination?.route) {
                            ROUTE_DETAILS -> stringResource(R.string.details_title)
                            ROUTE_LICENSES -> stringResource(R.string.settings_licenses)
                            else -> stringResource(currentTab?.label ?: R.string.app_name)
                        },
                    )
                },
                navigationIcon = {
                    if (currentTab == null && destination != null) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == currentTab,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(nav, startDestination = Tab.DASHBOARD.route) {
                composable(Tab.DASHBOARD.route) {
                    DashboardScreen(
                        onOpenLibrary = { nav.navigate(Tab.LIBRARY.route) { launchSingleTop = true } },
                        onOpenDetails = { nav.navigate(ROUTE_DETAILS) },
                    )
                }
                composable(Tab.LIBRARY.route) { LibraryScreen() }
                composable(Tab.HISTORY.route) { HistoryScreen() }
                composable(Tab.SETTINGS.route) { SettingsScreen(onOpenLicenses = { nav.navigate(ROUTE_LICENSES) }) }
                composable(ROUTE_DETAILS) { ActivityDetailsScreen() }
                composable(ROUTE_LICENSES) { LicensesScreen() }
            }
        }
    }
}

