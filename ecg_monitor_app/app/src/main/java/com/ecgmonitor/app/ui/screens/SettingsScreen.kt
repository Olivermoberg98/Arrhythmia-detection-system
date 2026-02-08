package com.ecgmonitor.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecgmonitor.app.data.ble.BleDevice
import com.ecgmonitor.app.data.ble.ConnectionState
import com.ecgmonitor.app.ui.theme.*
import com.ecgmonitor.app.ui.viewmodels.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 18.sp // Compact
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MedicalDarkBlue
                ),
            )
        },
        containerColor = MedicalDarkBackground
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection status card
            item {
                ConnectionStatusCard(
                    connectionState = connectionState,
                    onDisconnect = { viewModel.disconnect() }
                )
            }

            // BLE device scanning section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MedicalCardBackground
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "ECG Devices",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Button(
                                onClick = {
                                    if (connectionState is ConnectionState.Scanning) {
                                        viewModel.stopScanning()
                                    } else {
                                        viewModel.startScanning()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (connectionState is ConnectionState.Scanning)
                                        MedicalRed else MedicalGreen
                                )
                            ) {
                                Icon(
                                    if (connectionState is ConnectionState.Scanning)
                                        Icons.Default.Stop else Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (connectionState is ConnectionState.Scanning)
                                        "Stop" else "Scan"
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Device list
                        if (connectionState is ConnectionState.Scanning) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    color = MedicalGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Scanning for devices...",
                                    color = MedicalTextSecondary,
                                    fontSize = 14.sp
                                )
                            }
                        }

                        if (discoveredDevices.isEmpty() && connectionState !is ConnectionState.Scanning) {
                            Text(
                                "No devices found. Tap Scan to search for ECG devices.",
                                color = MedicalTextSecondary,
                                fontSize = 14.sp
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                discoveredDevices.forEach { device ->
                                    DeviceItem(
                                        device = device,
                                        isConnected = (connectionState as? ConnectionState.Connected)?.device == device,
                                        isConnecting = connectionState is ConnectionState.Connecting,
                                        onClick = { viewModel.connectToDevice(device) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // App information
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MedicalCardBackground
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            "About",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingRow("Version", "1.0.0")
                        SettingRow("Sampling Rate", "360 Hz")
                        SettingRow("Data Source", "ESP32 via BLE")
                    }
                }
            }

            // Placeholder for future settings
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MedicalCardBackground
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            "Preferences",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "Additional settings will be added here",
                            color = MedicalTextSecondary,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    connectionState: ConnectionState,
    onDisconnect: () -> Unit
) {
    val (statusText, statusColor, icon) = when (connectionState) {
        is ConnectionState.Disconnected -> Triple(
            "Not Connected",
            MedicalTextSecondary,
            Icons.Default.BluetoothDisabled
        )
        is ConnectionState.Scanning -> Triple(
            "Scanning...",
            MedicalCyan,
            Icons.Default.BluetoothSearching
        )
        is ConnectionState.Connecting -> Triple(
            "Connecting...",
            MedicalOrange,
            Icons.Default.Bluetooth
        )
        is ConnectionState.Connected -> Triple(
            "Connected to ${connectionState.device.name}",
            MedicalGreen,
            Icons.Default.BluetoothConnected
        )
        is ConnectionState.Error -> Triple(
            connectionState.message,
            MedicalRed,
            Icons.Default.Error
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.1f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "Device Status",
                        fontSize = 12.sp,
                        color = MedicalTextSecondary
                    )
                    Text(
                        statusText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
            }

            if (connectionState is ConnectionState.Connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MedicalRed
                    )
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
private fun DeviceItem(
    device: BleDevice,
    isConnected: Boolean,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, enabled = !isConnecting),
        color = if (isConnected) MedicalGreen.copy(alpha = 0.1f) else Color.Transparent,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    device.name ?: "Unknown Device",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    device.address,
                    fontSize = 12.sp,
                    color = MedicalTextSecondary
                )
            }

            when {
                isConnected -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Connected",
                    tint = MedicalGreen
                )
                isConnecting -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MedicalOrange,
                    strokeWidth = 2.dp
                )
                else -> Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Connect",
                    tint = MedicalTextSecondary
                )
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = MedicalTextSecondary
        )
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}