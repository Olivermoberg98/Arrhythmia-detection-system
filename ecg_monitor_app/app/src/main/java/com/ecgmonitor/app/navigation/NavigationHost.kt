package com.ecgmonitor.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ecgmonitor.app.data.consent.ConsentManager
import com.ecgmonitor.app.ui.screens.*
import com.ecgmonitor.app.ui.theme.MedicalDarkBackground
import com.ecgmonitor.app.ui.theme.MedicalDarkBlue
import com.ecgmonitor.app.ui.theme.MedicalGreen
import com.ecgmonitor.app.ui.theme.MedicalTextSecondary
import com.ecgmonitor.app.ui.viewmodels.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationHost() {
    val context = LocalContext.current
    val consentManager = remember { ConsentManager(context) }
    val coroutineScope = rememberCoroutineScope()

    // Tri-state: null = still checking, true = consented, false = needs consent
    var consentGranted by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        consentGranted = consentManager.hasValidConsent()
        if (consentGranted == true) {
            consentManager.log(com.ecgmonitor.app.data.database.entities.AuditAction.APP_OPENED)
        }
    }

    when (consentGranted) {
        null -> {
            // Brief loading state while checking DB
        }
        false -> {
            var declinedConsent by remember { mutableStateOf(false) }
            if (declinedConsent) {
                // Show a gentle "app is locked" screen
                Box(
                    modifier = Modifier.fillMaxSize().background(MedicalDarkBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MedicalTextSecondary,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            "App unavailable",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "ECG Monitor requires your consent to store and process health data. Without it, recordings and monitoring are unavailable.",
                            fontSize = 14.sp,
                            color = MedicalTextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { declinedConsent = false },  // takes them back to consent screen
                            colors = ButtonDefaults.buttonColors(containerColor = MedicalGreen)
                        ) {
                            Text("Review consent again")
                        }
                    }
                }
            } else {
                ConsentScreen(
                    onAccept = {
                        coroutineScope.launch {
                            consentManager.recordConsent(granted = true)
                            consentGranted = true
                        }
                    },
                    onDecline = {
                        coroutineScope.launch {
                            consentManager.recordConsent(granted = false)
                        }
                        declinedConsent = true  // flip to locked screen instead of closing
                    }
                )
            }
        }
        true -> {
            MainAppNavigation(consentManager = consentManager)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainAppNavigation(consentManager: ConsentManager) {
    val navController = rememberNavController()
    val coroutineScope = rememberCoroutineScope()

    val bottomNavItems = listOf(
        BottomNavItem("live", Icons.Default.MonitorHeart, "Live"),
        BottomNavItem("history", Icons.Default.History, "History"),
        BottomNavItem("settings", Icons.Default.Settings, "Settings")
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showBottomBar = currentRoute?.startsWith("session_detail") == false

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MedicalDarkBlue) {
                    bottomNavItems.forEach { item ->
                        val selected = navBackStackEntry?.destination?.hierarchy
                            ?.any { it.route == item.route } == true

                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MedicalGreen,
                                selectedTextColor = MedicalGreen,
                                unselectedIconColor = MedicalTextSecondary,
                                unselectedTextColor = MedicalTextSecondary,
                                indicatorColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = "live",
            modifier = Modifier.padding(paddingValues)
        ) {
            composable("live") {
                val viewModel: LiveMonitorViewModel = viewModel()
                LiveMonitorScreen(
                    viewModel = viewModel,
                    onRecordingSaved = { sessionId ->
                        coroutineScope.launch {
                            consentManager.log(
                                action = com.ecgmonitor.app.data.database.entities.AuditAction.SESSION_RECORDED,
                                sessionId = sessionId
                            )
                        }
                        navController.navigate("session_detail/$sessionId")
                    }
                )
            }
            composable("history") {
                val viewModel: HistoryViewModel = viewModel()
                HistoryScreen(
                    viewModel = viewModel,
                    onSessionClick = { sessionId ->
                        coroutineScope.launch {
                            consentManager.log(
                                action = com.ecgmonitor.app.data.database.entities.AuditAction.SESSION_VIEWED,
                                sessionId = sessionId
                            )
                        }
                        navController.navigate("session_detail/$sessionId")
                    }
                )
            }
            composable("session_detail/{sessionId}") { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
                val viewModel: SessionDetailViewModel = viewModel()
                val historyViewModel: HistoryViewModel = viewModel()
                SessionDetailScreen(
                    sessionId = sessionId,
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onDelete = { id ->
                        historyViewModel.deleteSession(id)
                        navController.popBackStack()
                    }
                )
            }
            composable("settings") {
                val viewModel: SettingsViewModel = viewModel()
                SettingsScreen(
                    viewModel = viewModel,
                    consentManager = consentManager
                )
            }
        }
    }
}

private data class BottomNavItem(
    val route: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String
)