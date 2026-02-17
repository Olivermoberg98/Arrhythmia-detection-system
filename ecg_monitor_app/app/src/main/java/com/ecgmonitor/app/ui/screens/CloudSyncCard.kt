package com.ecgmonitor.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecgmonitor.app.data.firebase.FirebaseRepository
import com.ecgmonitor.app.data.firebase.UploadState
import com.ecgmonitor.app.ui.theme.*

/**
 * Shows cloud sync status in Settings.
 * No login UI needed — anonymous auth happens silently on launch.
 *
 * Add to SettingsScreen LazyColumn before DataPrivacyCard:
 *   item { CloudSyncCard(firebaseRepository = firebaseRepository) }
 *
 * Update SettingsScreen signature:
 *   fun SettingsScreen(
 *       viewModel: SettingsViewModel,
 *       consentManager: ConsentManager,
 *       firebaseRepository: FirebaseRepository,
 *       syncManager: SyncManager
 *   )
 */
@Composable
fun CloudSyncCard(firebaseRepository: FirebaseRepository) {
    val uploadState by firebaseRepository.uploadState.collectAsState(initial = UploadState.Idle)
    val isAuthenticated = firebaseRepository.isAuthenticated

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MedicalCardBackground),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        !isAuthenticated -> Icons.Default.CloudOff
                        uploadState is UploadState.Success -> Icons.Default.CloudDone
                        else -> Icons.Default.Cloud
                    },
                    contentDescription = null,
                    tint = when {
                        !isAuthenticated -> MedicalTextSecondary
                        uploadState is UploadState.Error -> MedicalRed
                        uploadState is UploadState.Success -> MedicalGreen
                        else -> MedicalCyan
                    },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cloud Backup", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (val state = uploadState) {
                is UploadState.Idle -> {
                    Text(
                        if (isAuthenticated) "Connected — recordings sync automatically after each session"
                        else "Not connected — recordings are stored locally only",
                        fontSize = 13.sp,
                        color = MedicalTextSecondary
                    )
                }
                is UploadState.Uploading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MedicalCyan,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Syncing to cloud...", fontSize = 13.sp, color = MedicalCyan)
                    }
                }
                is UploadState.Success -> {
                    Text("Last sync successful (session ${state.sessionId.take(8)}...)", fontSize = 13.sp, color = MedicalGreen)
                }
                is UploadState.Error -> {
                    Text("Sync failed: ${state.message}", fontSize = 13.sp, color = MedicalRed)
                }
                is UploadState.NotAuthenticated -> {
                    Text("Authentication error — restart the app", fontSize = 13.sp, color = MedicalRed)
                }
            }
        }
    }
}