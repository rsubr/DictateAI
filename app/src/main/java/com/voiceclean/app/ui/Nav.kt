package com.voiceclean.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.voiceclean.app.di.AppContainer

private enum class Dest(val route: String, val label: String, val icon: ImageVector) {
    Home("home", "Home", Icons.Filled.Mic),
    History("history", "History", Icons.Filled.History),
    Models("models", "Models", Icons.Filled.Storage),
    Settings("settings", "Settings", Icons.Filled.Settings),
}

@Composable
fun VoiceCleanNav(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val openRequest = androidx.compose.runtime.remember { kotlinx.coroutines.flow.MutableStateFlow<Long?>(null) }
    val goTo: (String) -> Unit = { route ->
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Dest.entries.forEach { d ->
                    NavigationBarItem(
                        selected = current == d.route,
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(d.icon, d.label) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = Dest.Home.route, modifier = Modifier.padding(pad)) {
            composable(Dest.Home.route) { HomeScreen(container, openRequest, onGoToModels = { goTo(Dest.Models.route) }) }
            composable(Dest.History.route) { HistoryScreen(container, onOpen = { openRequest.value = it; goTo(Dest.Home.route) }) }
            composable(Dest.Models.route) { ModelsScreen(container) }
            composable(Dest.Settings.route) { SettingsScreen(container) }
        }
    }
}
