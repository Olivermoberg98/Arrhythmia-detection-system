package com.ecgmonitor.app.ui.screens

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecgmonitor.app.data.ble.BleDevice
import com.ecgmonitor.app.data.ble.BlePermissions
import com.ecgmonitor.app.data.ble.ConnectionState
import com.ecgmonitor.app.data.consent.ConsentManager
import com.ecgmonitor.app.data.firebase.FirebaseRepository
import com.ecgmonitor.app.data.firebase.SyncManager
import com.ecgmonitor.app.ui.theme.*
import com.ecgmonitor.app.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    consentManager: ConsentManager,
    firebaseRepository: FirebaseRepository,
    syncManager: SyncManager
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()

    var permissionsGranted by remember { mutableStateOf(BlePermissions.allGranted(context)) }
    // True once user has tapped deny and "Don't ask again" — we must send them to system Settings
    var permanentlyDenied by remember { mutableStateOf(false) }
    var connectingAddress by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(connectionState) {
        if (connectionState !is ConnectionState.Connecting) {
            connectingAddress = null
        }
    }

    // Re-check permissions every time the screen resumes (e.g. returning from system Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsGranted = BlePermissions.allGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
        if (!permissionsGranted) {
            // Permanently denied = still denied AND rationale should NOT be shown
            permanentlyDenied = BlePermissions.required.any { permission ->
                !results[permission]!! &&
                        (context as? ComponentActivity)?.shouldShowRequestPermissionRationale(permission) == false
            }
        } else {
            permanentlyDenied = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold, color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MedicalDarkBlue)
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

            // Permission banner — always shown until granted
            if (!permissionsGranted) {
                item {
                    PermissionCard(
                        permanentlyDenied = permanentlyDenied,
                        onRequest = { permissionLauncher.launch(BlePermissions.required) },
                        onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                            )
                        }
                    )
                }
            }

            item {
                ConnectionStatusCard(
                    connectionState = connectionState,
                    onDisconnect = { viewModel.disconnect() }
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MedicalCardBackground),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("ECG Devices", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)

                            Button(
                                onClick = {
                                    when {
                                        !permissionsGranted ->
                                            permissionLauncher.launch(BlePermissions.required)
                                        !isBluetoothEnabled(context) ->
                                            viewModel.setError("Bluetooth is disabled. Enable it in device settings.")
                                        connectionState is ConnectionState.Scanning ->
                                            viewModel.stopScanning()
                                        else ->
                                            viewModel.startScanning()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        !permissionsGranted -> MedicalOrange
                                        connectionState is ConnectionState.Scanning -> MedicalRed
                                        else -> MedicalGreen
                                    }
                                )
                            ) {
                                Icon(
                                    imageVector = when {
                                        !permissionsGranted -> Icons.Default.Lock
                                        connectionState is ConnectionState.Scanning -> Icons.Default.Stop
                                        else -> Icons.Default.Bluetooth
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(when {
                                    !permissionsGranted -> "Allow"
                                    connectionState is ConnectionState.Scanning -> "Stop"
                                    else -> "Scan"
                                })
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        when {
                            connectionState is ConnectionState.Scanning -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MedicalGreen,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Scanning for devices...", fontSize = 14.sp, color = MedicalTextSecondary)
                                }
                            }
                            connectionState is ConnectionState.Error -> {
                                Text(
                                    (connectionState as ConnectionState.Error).message,
                                    fontSize = 13.sp,
                                    color = MedicalRed
                                )
                            }
                            discoveredDevices.isEmpty() -> {
                                Text("No devices found. Tap Scan to search.", fontSize = 14.sp, color = MedicalTextSecondary)
                            }
                            else -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    discoveredDevices.forEach { device ->
                                        DeviceItem(
                                            device = device,
                                            isConnected  = (connectionState as? ConnectionState.Connected)?.device == device,
                                            isConnecting = connectingAddress == device.address && connectionState is ConnectionState.Connecting,
                                            onClick = {
                                                connectingAddress = device.address   // ← record which one was tapped
                                                viewModel.connectToDevice(device)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MedicalCardBackground),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("About", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(12.dp))
                        SettingRow("Version", "1.0.0")
                        SettingRow("Sampling Rate", "250 Hz")
                        SettingRow("Connection", "Bluetooth LE")
                    }
                }
            }
            item { CloudSyncCard(firebaseRepository = firebaseRepository) }
            item {
                DataPrivacyCard(consentManager = consentManager, syncManager = syncManager)
            }
        }
    }
}

@Composable
private fun PermissionCard(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MedicalOrange.copy(alpha = 0.12f)),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.BluetoothDisabled,
                contentDescription = null,
                tint = MedicalOrange,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Bluetooth Permission Required",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MedicalOrange
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (permanentlyDenied)
                    "Permission was permanently denied. Open app settings to grant Bluetooth access manually."
                else
                    "Bluetooth access is required to scan and connect to your ECG device.",
                fontSize = 13.sp,
                color = MedicalTextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = if (permanentlyDenied) onOpenSettings else onRequest,
                colors = ButtonDefaults.buttonColors(containerColor = MedicalOrange)
            ) {
                Text(if (permanentlyDenied) "Open App Settings" else "Grant Permission")
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(connectionState: ConnectionState, onDisconnect: () -> Unit) {
    val (text, color, icon) = when (connectionState) {
        is ConnectionState.Disconnected  -> Triple("Not Connected", MedicalTextSecondary, Icons.Default.BluetoothDisabled)
        is ConnectionState.Scanning      -> Triple("Scanning...", MedicalCyan, Icons.Default.BluetoothSearching)
        is ConnectionState.Connecting    -> Triple("Connecting...", MedicalOrange, Icons.Default.Bluetooth)
        is ConnectionState.Connected     -> Triple("Connected to ${connectionState.device.name}", MedicalGreen, Icons.Default.BluetoothConnected)
        is ConnectionState.Error         -> Triple(connectionState.message, MedicalRed, Icons.Default.Error)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(30.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Device Status", fontSize = 12.sp, color = MedicalTextSecondary)
                    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
                }
            }
            if (connectionState is ConnectionState.Connected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MedicalRed)
                ) { Text("Disconnect") }
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
        modifier = Modifier.fillMaxWidth().clickable(enabled = !isConnecting, onClick = onClick),
        color = if (isConnected) MedicalGreen.copy(alpha = 0.1f) else Color.Transparent,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(device.name ?: "Unknown Device", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                Text(device.address, fontSize = 12.sp, color = MedicalTextSecondary)
                Text("${device.rssi} dBm", fontSize = 11.sp, color = MedicalTextSecondary)
            }
            when {
                isConnected  -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MedicalGreen)
                isConnecting -> CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MedicalOrange, strokeWidth = 2.dp)
                else         -> Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MedicalTextSecondary)
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = MedicalTextSecondary)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

private fun isBluetoothEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    return manager?.adapter?.isEnabled == true
}

@Composable
fun DataPrivacyCard(consentManager: ConsentManager, syncManager: SyncManager) {
    val coroutineScope = rememberCoroutineScope()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showWithdrawConfirm by remember { mutableStateOf(false) }
    var actionComplete by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MedicalCardBackground),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = MedicalCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Data & Privacy", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MedicalRed)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete All Recordings")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { showWithdrawConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MedicalOrange)
            ) {
                Text("Withdraw Consent")
            }

            actionComplete?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, color = MedicalGreen)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = MedicalCardBackground,
            title = { Text("Delete all recordings?", color = Color.White) },
            text = {
                Text(
                    "Permanently deletes all ECG recordings, arrhythmia events, and statistics from this device. Cannot be undone. Your consent record is retained as required by law.",
                    color = MedicalTextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        coroutineScope.launch {
                            syncManager.deleteEverywhere()
                            actionComplete = "All recordings deleted"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MedicalRed)
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showWithdrawConfirm) {
        AlertDialog(
            onDismissRequest = { showWithdrawConfirm = false },
            containerColor = MedicalCardBackground,
            title = { Text("Withdraw consent?", color = Color.White) },
            text = {
                Text(
                    "Withdrawing consent will delete all recordings and disable data storage. The app will require re-consent to function. You can re-consent at any time.",
                    color = MedicalTextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWithdrawConfirm = false
                        coroutineScope.launch {
                            syncManager.deleteEverywhere()
                            consentManager.recordConsent(granted = false)
                            actionComplete = "Consent withdrawn. Please restart the app."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MedicalOrange)
                ) { Text("Withdraw") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showWithdrawConfirm = false }) { Text("Cancel") }
            }
        )
    }
}