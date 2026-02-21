package com.ecgmonitor.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ecgmonitor.app.EcgMonitorApp
import com.ecgmonitor.app.data.consent.ConsentManager
import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.database.entities.AuditAction
import com.ecgmonitor.app.data.firebase.FirebaseRepository
import com.ecgmonitor.app.data.firebase.SyncManager
import com.ecgmonitor.app.ui.screens.ConsentScreen
import com.ecgmonitor.app.ui.screens.HistoryScreen
import com.ecgmonitor.app.ui.screens.LiveMonitorScreen
import com.ecgmonitor.app.ui.screens.SessionDetailScreen
import com.ecgmonitor.app.ui.screens.SettingsScreen
import com.ecgmonitor.app.ui.theme.MedicalDarkBackground
import com.ecgmonitor.app.ui.theme.MedicalDarkBlue
import com.ecgmonitor.app.ui.theme.MedicalGreen
import com.ecgmonitor.app.ui.theme.MedicalTextSecondary
import com.ecgmonitor.app.ui.viewmodels.HistoryViewModel
import com.ecgmonitor.app.ui.viewmodels.LiveMonitorViewModel
import com.ecgmonitor.app.ui.viewmodels.SessionDetailViewModel
import com.ecgmonitor.app.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

@Composable
fun NavigationHost() {
    val context = LocalContext.current
    val app = context.applicationContext as EcgMonitorApp

    val consentManager = app.consentManager
    val firebaseRepository = app.firebaseRepository
    val syncManager = app.syncManager

    val coroutineScope = rememberCoroutineScope()

    // null = still checking DB, true = consented, false = needs consent
    var consentGranted by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        consentGranted = consentManager.hasValidConsent()
        if (consentGranted == true) {
            consentManager.log(AuditAction.APP_OPENED)
        }
    }

    when (consentGranted) {
        null  -> Unit // Brief loading state while checking DB

        false -> {
            var declinedConsent by remember { mutableStateOf(false) }
            if (declinedConsent) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MedicalDarkBackground),
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
                        Text("App unavailable", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(
                            "ECG Monitor requires your consent to store and process health data. Without it, recordings and monitoring are unavailable.",
                            fontSize = 14.sp,
                            color = MedicalTextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { declinedConsent = false },
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
                        coroutineScope.launch { consentManager.recordConsent(granted = false) }
                        declinedConsent = true
                    }
                )
            }
        }

        true -> MainAppNavigation(
            consentManager     = consentManager,
            firebaseRepository = firebaseRepository,
            syncManager        = syncManager
        )
    }
}

@Composable
private fun MainAppNavigation(
    consentManager: ConsentManager,
    firebaseRepository: FirebaseRepository,
    syncManager: SyncManager
) {
    val navController  = rememberNavController()
    val coroutineScope = rememberCoroutineScope()

    val bottomNavItems = listOf(
        BottomNavItem(Screen.LiveMonitor, Screen.LiveMonitor::class, Icons.Default.MonitorHeart, "Live"),
        BottomNavItem(Screen.History,     Screen.History::class,     Icons.Default.History,      "History"),
        BottomNavItem(Screen.Settings,    Screen.Settings::class,    Icons.Default.Settings,     "Settings")
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val showBottomBar = navBackStackEntry?.destination?.hasRoute<Screen.SessionDetail>() != true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MedicalDarkBlue) {
                    bottomNavItems.forEach { item ->
                        val selected = navBackStackEntry?.destination?.hierarchy
                            ?.any { it.hasRoute(item.routeClass) } == true

                        NavigationBarItem(
                            icon     = { Icon(item.icon, contentDescription = item.label) },
                            label    = { Text(item.label) },
                            selected = selected,
                            onClick  = { navController.navigate(item.route) },
                            colors   = NavigationBarItemDefaults.colors(
                                selectedIconColor   = MedicalGreen,
                                selectedTextColor   = MedicalGreen,
                                unselectedIconColor = MedicalTextSecondary,
                                unselectedTextColor = MedicalTextSecondary,
                                indicatorColor      = Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController    = navController,
            startDestination = Screen.LiveMonitor,
            modifier         = Modifier.padding(paddingValues)
        ) {
            composable<Screen.LiveMonitor> {
                val viewModel: LiveMonitorViewModel = viewModel()
                LiveMonitorScreen(
                    viewModel = viewModel,
                    onRecordingSaved = { sessionId ->
                        coroutineScope.launch {
                            consentManager.log(action = AuditAction.SESSION_RECORDED, sessionId = sessionId)
                        }
                        navController.navigate(Screen.SessionDetail(sessionId))
                    }
                )
            }

            composable<Screen.History> {
                val viewModel: HistoryViewModel = viewModel()
                HistoryScreen(
                    viewModel = viewModel,
                    onSessionClick = { sessionId ->
                        coroutineScope.launch {
                            consentManager.log(action = AuditAction.SESSION_VIEWED, sessionId = sessionId)
                        }
                        navController.navigate(Screen.SessionDetail(sessionId))
                    }
                )
            }

            composable<Screen.SessionDetail> { backStackEntry ->
                val route     = backStackEntry.toRoute<Screen.SessionDetail>()
                val viewModel: SessionDetailViewModel = viewModel()
                val historyViewModel: HistoryViewModel = viewModel()
                SessionDetailScreen(
                    sessionId = route.sessionId,
                    viewModel = viewModel,
                    onBack    = { navController.popBackStack() },
                    onDelete  = { id ->
                        historyViewModel.deleteSession(id)
                        navController.popBackStack()
                    }
                )
            }

            composable<Screen.Settings> {
                val viewModel: SettingsViewModel = viewModel()
                SettingsScreen(
                    viewModel          = viewModel,
                    consentManager     = consentManager,
                    firebaseRepository = firebaseRepository,
                    syncManager        = syncManager
                )
            }
        }
    }
}

private data class BottomNavItem(
    val route: Screen,
    val routeClass: KClass<out Screen>,
    val icon: ImageVector,
    val label: String
)