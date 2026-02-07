package com.ecgmonitor.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ecgmonitor.app.data.models.DataSource
import com.ecgmonitor.app.ui.components.EcgChart
import com.ecgmonitor.app.ui.theme.*
import com.ecgmonitor.app.ui.viewmodels.LiveMonitorViewModel
import com.ecgmonitor.app.ui.viewmodels.MonitorUiState

/**
 * Main Live ECG Monitor Screen
 * Medical-grade aesthetics with dark theme
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveMonitorScreen(
    viewModel: LiveMonitorViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ecgReadings by viewModel.ecgReadings.collectAsStateWithLifecycle()
    val arrhythmiaEvents by viewModel.arrhythmiaEvents.collectAsStateWithLifecycle()
    val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
    val heartRate by viewModel.heartRate.collectAsStateWithLifecycle()

    var showRecordSelector by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "ECG Monitor",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MedicalDarkBlue
                ),
                actions = {
                    IconButton(onClick = { showRecordSelector = true }) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = "Load Record",
                            tint = Color.White
                        )
                    }
                }
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
            // Status and Vital Signs Card
            VitalSignsCard(
                heartRate = heartRate,
                isStreaming = uiState is MonitorUiState.Streaming,
                dataSource = currentSession?.source ?: DataSource.MIT_BIH_DATABASE
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

                        currentSession?.let { session ->
                            Text(
                                "${session.samplingRate} Hz",
                                color = MedicalTextSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // ECG Chart
                    if (ecgReadings.isNotEmpty()) {
                        EcgChart(
                            ecgReadings = ecgReadings,
                            arrhythmiaEvents = arrhythmiaEvents,
                            samplingRate = currentSession?.samplingRate ?: 360,
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
                            Text(
                                "No ECG data",
                                color = MedicalTextSecondary,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Control Buttons
            ControlButtons(
                uiState = uiState,
                onStartPlayback = { viewModel.startPlayback() },
                onStopPlayback = { viewModel.stopPlayback() },
                onClearData = { viewModel.clearData() }
            )

            // Status Message
            if (uiState is MonitorUiState.Ready || uiState is MonitorUiState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                StatusMessage(uiState)
            }
        }
    }

    // Record Selector Dialog
    if (showRecordSelector) {
        RecordSelectorDialog(
            records = viewModel.getAvailableRecords(),
            onRecordSelected = { record ->
                viewModel.loadMitBihRecord(record)
                showRecordSelector = false
            },
            onDismiss = { showRecordSelector = false }
        )
    }
}

@Composable
private fun VitalSignsCard(
    heartRate: Int,
    isStreaming: Boolean,
    dataSource: DataSource
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
            // Heart Rate Display
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

            // Data Source
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    when (dataSource) {
                        DataSource.MIT_BIH_DATABASE -> Icons.Default.Storage
                        DataSource.ESP32_DEVICE -> Icons.Default.Bluetooth
                        DataSource.SIMULATION -> Icons.Default.Science
                    },
                    contentDescription = "Data Source",
                    tint = MedicalCyan,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    when (dataSource) {
                        DataSource.MIT_BIH_DATABASE -> "MIT-BIH"
                        DataSource.ESP32_DEVICE -> "ESP32"
                        DataSource.SIMULATION -> "Simulation"
                    },
                    fontSize = 12.sp,
                    color = MedicalTextSecondary
                )
            }
        }
    }
}

@Composable
private fun ControlButtons(
    uiState: MonitorUiState,
    onStartPlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onClearData: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        // Play/Stop Button
        Button(
            onClick = {
                if (uiState is MonitorUiState.Streaming) {
                    onStopPlayback()
                } else {
                    onStartPlayback()
                }
            },
            enabled = uiState !is MonitorUiState.Loading,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState is MonitorUiState.Streaming)
                    MedicalRed else MedicalGreen
            ),
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                if (uiState is MonitorUiState.Streaming)
                    Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (uiState is MonitorUiState.Streaming) "Stop" else "Play"
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Clear Button
        OutlinedButton(
            onClick = onClearData,
            enabled = uiState !is MonitorUiState.Streaming,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MedicalTextSecondary
            )
        ) {
            Icon(
                Icons.Default.Clear,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear")
        }
    }
}

@Composable
private fun StatusMessage(uiState: MonitorUiState) {
    val (message, color) = when (uiState) {
        is MonitorUiState.Ready -> uiState.message to MedicalGreen
        is MonitorUiState.Error -> uiState.message to MedicalRed
        else -> return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (uiState is MonitorUiState.Error) Icons.Default.Error else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                message,
                color = color,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun RecordSelectorDialog(
    records: List<String>,
    onRecordSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select MIT-BIH Record") },
        text = {
            Column {
                records.forEach { record ->
                    TextButton(
                        onClick = { onRecordSelected(record) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Record $record",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
