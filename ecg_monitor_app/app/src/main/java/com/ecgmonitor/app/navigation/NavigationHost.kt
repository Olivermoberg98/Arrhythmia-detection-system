package com.ecgmonitor.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ecgmonitor.app.ui.screens.*
import com.ecgmonitor.app.ui.theme.MedicalDarkBlue
import com.ecgmonitor.app.ui.viewmodels.*

@Composable
fun EcgMonitorApp() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            BottomNavigationBar(navController)
        }
    ) { innerPadding ->
        NavigationHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
fun NavigationHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.LiveMonitor,
        modifier = modifier
    ) {
        // Live Monitor Screen
        composable<Screen.LiveMonitor> {
            val viewModel: LiveMonitorViewModel = viewModel()
            LiveMonitorScreen(
                viewModel = viewModel,
                onRecordingSaved = { sessionId ->
                    // Navigate to session detail after saving
                    navController.navigate(Screen.SessionDetail(sessionId))
                }
            )
        }

        // History Screen
        composable<Screen.History> {
            val viewModel: HistoryViewModel = viewModel()
            HistoryScreen(
                viewModel = viewModel,
                onSessionClick = { sessionId ->
                    navController.navigate(Screen.SessionDetail(sessionId))
                }
            )
        }

        // Session Detail Screen
        composable<Screen.SessionDetail> { backStackEntry ->
            val sessionDetail: Screen.SessionDetail = backStackEntry.toRoute()
            val viewModel: SessionDetailViewModel = viewModel()
            SessionDetailScreen(
                sessionId = sessionDetail.sessionId,
                viewModel = viewModel,
                onBack = { navController.navigateUp() }
            )
        }

        // Settings Screen
        composable<Screen.Settings> {
            val viewModel: SettingsViewModel = viewModel()
            SettingsScreen(viewModel = viewModel)
        }
    }
}

@Composable
fun BottomNavigationBar(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Hide bottom nav on SessionDetail screen
    val shouldShowBottomBar = currentDestination?.hierarchy?.none {
        it.route?.contains("SessionDetail") == true
    } ?: true

    if (!shouldShowBottomBar) return

    NavigationBar(
        containerColor = MedicalDarkBlue
    ) {
        bottomNavItems.forEach { item ->
            val selected = currentDestination?.hierarchy?.any {
                it.route == item.route::class.qualifiedName
            } == true

            NavigationBarItem(
                icon = {
                    Icon(
                        item.icon,
                        contentDescription = item.title
                    )
                },
                label = { Text(item.title) },
                selected = selected,
                onClick = {
                    navController.navigate(item.route) {
                        // Pop up to start destination to avoid building up backstack
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        // Avoid multiple copies of same destination
                        launchSingleTop = true
                        // Restore state when re-selecting previously selected item
                        restoreState = true
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    unselectedIconColor = Color.White.copy(alpha = 0.6f),
                    unselectedTextColor = Color.White.copy(alpha = 0.6f),
                    indicatorColor = Color.White.copy(alpha = 0.2f)
                )
            )
        }
    }
}

data class BottomNavItem(
    val title: String,
    val icon: ImageVector,
    val route: Screen
)

val bottomNavItems = listOf(
    BottomNavItem(
        title = "Live",
        icon = Icons.Default.Favorite,
        route = Screen.LiveMonitor
    ),
    BottomNavItem(
        title = "History",
        icon = Icons.Default.History,
        route = Screen.History
    ),
    BottomNavItem(
        title = "Settings",
        icon = Icons.Default.Settings,
        route = Screen.Settings
    )
)