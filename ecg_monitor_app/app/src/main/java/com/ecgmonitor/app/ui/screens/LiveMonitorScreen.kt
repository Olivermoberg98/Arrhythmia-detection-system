package com.ecgmonitor.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecgmonitor.app.data.ble.ConnectionState
import com.ecgmonitor.app.data.models.DataSource
import com.ecgmonitor.app.ui.components.EcgChart
import com.ecgmonitor.app.ui.theme.*
import com.ecgmonitor.app.ui.viewmodels.LiveMonitorUiState
import com.ecgmonitor.app.ui.viewmodels.LiveMonitorViewModel

/**
 * Updated Live Monitor Screen - BLE streaming with recording
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveMonitorScreen(
    viewModel: LiveMonitorViewModel,
    onRecordingSaved: (String) -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ecgReadings by viewModel.ecgReadings.collectAsStateWithLifecycle()
    val visibleEvents by viewModel.visibleArrhythmiaEvents.collectAsStateWithLifecycle()
    val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
    val heartRate by viewModel.heartRate.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()

    LaunchedEffect(uiState) {
        if (uiState is LiveMonitorUiState.RecordingSaved) {
            val sessionId = (uiState as LiveMonitorUiState.RecordingSaved).sessionId
            onRecordingSaved(sessionId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Live Monitor",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 18.sp // Slightly smaller for compact look
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MedicalDarkBlue
                ),
                actions = {
                    if (isRecording) {
                        RecordingIndicator()
                    }
                },
            )
        },
        containerColor = MedicalDarkBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Connection Status Card
            ConnectionStatusBanner(connectionState)

            Spacer(modifier = Modifier.height(16.dp))

            // Vital Signs Card
            VitalSignsCard(
                heartRate = heartRate,
                isStreaming = connectionState is ConnectionState.Connected,
                isRecording = isRecording,
                recordingDuration = if (isRecording && currentSession != null) {
                    (System.currentTimeMillis() - currentSession!!.startTime) / 1000
                } else 0
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ECG Waveform Chart
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MedicalCardBackground
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    // Chart Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "ECG Waveform",
                            color = MedicalTextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            "360 Hz",
                            color = MedicalTextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ECG Chart
                    if (ecgReadings.isNotEmpty()) {
                        EcgChart(
                            ecgReadings = ecgReadings,
                            arrhythmiaEvents = visibleEvents,
                            samplingRate = 360,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.MonitorHeart,
                                    contentDescription = null,
                                    tint = MedicalTextSecondary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    if (connectionState is ConnectionState.Connected)
                                        "Waiting for ECG data..."
                                    else
                                        "Connect to device to start",
                                    color = MedicalTextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Recording Controls
            RecordingControls(
                isConnected = connectionState is ConnectionState.Connected,
                isRecording = isRecording,
                onStartRecording = { viewModel.startRecording() },
                onStopRecording = { viewModel.stopRecording() },
                onClearData = { viewModel.clearData() }
            )

            // Status Messages
            when (uiState) {
                is LiveMonitorUiState.Error -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusMessage(
                        message = (uiState as LiveMonitorUiState.Error).message,
                        isError = true
                    )
                }
                is LiveMonitorUiState.RecordingSaved -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusMessage(
                        message = "Recording saved successfully!",
                        isError = false
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun ConnectionStatusBanner(connectionState: ConnectionState) {
    val (text, color, icon) = when (connectionState) {
        is ConnectionState.Connected -> Triple(
            "Connected to ${connectionState.device.name}",
            MedicalGreen,
            Icons.Default.BluetoothConnected
        )
        is ConnectionState.Connecting -> Triple(
            "Connecting...",
            MedicalOrange,
            Icons.Default.Bluetooth
        )
        is ConnectionState.Disconnected -> Triple(
            "Not connected - Go to Settings to connect",
            MedicalTextSecondary,
            Icons.Default.BluetoothDisabled
        )
        is ConnectionState.Error -> Triple(
            connectionState.message,
            MedicalRed,
            Icons.Default.Error
        )
        is ConnectionState.Scanning -> Triple(
            "Scanning for devices...",
            MedicalCyan,
            Icons.Default.BluetoothSearching
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text,
                color = color,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun VitalSignsCard(
    heartRate: Int,
    isStreaming: Boolean,
    isRecording: Boolean,
    recordingDuration: Long
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MedicalCardBackground
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Heart Rate
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = "Heart Rate",
                        tint = if (isStreaming) MedicalGreen else MedicalTextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "$heartRate",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (isStreaming) MedicalGreen else MedicalTextSecondary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "BPM",
                        fontSize = 14.sp,
                        color = MedicalTextSecondary
                    )
                }
                Text(
                    "Heart Rate",
                    fontSize = 12.sp,
                    color = MedicalTextSecondary
                )
            }

            // Divider
            Divider(
                modifier = Modifier
                    .width(1.dp)
                    .height(60.dp),
                color = MedicalTextSecondary.copy(alpha = 0.3f)
            )

            // Recording Duration or Data Source
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    if (isRecording) Icons.Default.FiberManualRecord else Icons.Default.Bluetooth,
                    contentDescription = if (isRecording) "Recording" else "Data Source",
                    tint = if (isRecording) MedicalRed else MedicalCyan,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (isRecording) formatDuration(recordingDuration) else "ESP32",
                    fontSize = 12.sp,
                    color = MedicalTextSecondary
                )
            }
        }
    }
}

@Composable
private fun RecordingControls(
    isConnected: Boolean,
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onClearData: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Record/Stop Button
        Button(
            onClick = if (isRecording) onStopRecording else onStartRecording,
            enabled = isConnected,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) MedicalRed else MedicalGreen,
                disabledContainerColor = MedicalTextSecondary.copy(alpha = 0.3f)
            ),
            modifier = Modifier.weight(1.5f)
        ) {
            Icon(
                if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (isRecording) "Stop Recording" else "Start Recording",
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Clear Button
        OutlinedButton(
            onClick = onClearData,
            enabled = !isRecording,
            modifier = Modifier.weight(0.6f),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MedicalTextSecondary
            ),
            contentPadding = PaddingValues(8.dp)
        ) {
            Icon(
                Icons.Default.Clear,
                contentDescription = "Clear",
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun RecordingIndicator() {
    Surface(
        color = MedicalRed.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.FiberManualRecord,
                contentDescription = "Recording",
                tint = MedicalRed,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                "REC",
                color = MedicalRed,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatusMessage(message: String, isError: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError)
                MedicalRed.copy(alpha = 0.1f)
            else
                MedicalGreen.copy(alpha = 0.1f)
        ),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = if (isError) MedicalRed else MedicalGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                message,
                color = if (isError) MedicalRed else MedicalGreen,
                fontSize = 14.sp
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return String.format("%d:%02d", mins, secs)
}