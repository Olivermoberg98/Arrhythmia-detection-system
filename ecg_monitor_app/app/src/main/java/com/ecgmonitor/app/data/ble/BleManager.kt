package com.ecgmonitor.app.data.ble

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.random.Random

/**
 * BLE Manager for ESP32 ECG device communication
 *
 * IMPLEMENTATION STATUS:
 * - ✅ State management (connection, devices, data)
 * - ✅ Simulated scanning (for testing without hardware)
 * - ✅ Simulated data streaming (for testing)
 * - ⏳ Real BLE scanning (requires Kable dependency)
 * - ⏳ Real BLE connection (requires ESP32 hardware)
 * - ⏳ Real data parsing (requires ESP32 protocol spec)
 *
 * TO USE REAL BLE:
 * 1. Add to build.gradle.kts:
 *    implementation("com.juul.kable:core:0.30.0")
 * 2. Uncomment Kable code sections below
 * 3. Add BLE permissions to AndroidManifest.xml
 * 4. Test with actual ESP32 device
 */
class BleManager(private val context: Context) {

    // Connection state
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Discovered devices
    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDevice>> = _discoveredDevices.asStateFlow()

    // ECG data stream
    private val _ecgDataFlow = MutableStateFlow<EcgData?>(null)
    val ecgDataFlow: StateFlow<EcgData?> = _ecgDataFlow.asStateFlow()

    // Current device
    private var currentDevice: BleDevice? = null

    // For real BLE (uncomment when Kable is added)
    // private var peripheral: Peripheral? = null
    // private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Start scanning for BLE devices
     */
    suspend fun startScanning() {
        Timber.i("Starting BLE scan...")
        _connectionState.value = ConnectionState.Scanning

        // OPTION 1: Simulated scanning (for testing without hardware)
        simulateDeviceScanning()

        // OPTION 2: Real BLE scanning with Kable (uncomment when ready)
        /*
        try {
            val scanner = Scanner {
                filters = listOf(
                    Filter.Name("ECG"),  // Filter for devices with "ECG" in name
                    Filter.Service(ECG_SERVICE_UUID)  // Or by service UUID
                )
            }

            scanner.advertisements
                .catch { cause ->
                    Timber.e(cause, "Scan failed")
                    _connectionState.value = ConnectionState.Error("Scan failed: ${cause.message}")
                }
                .collect { advertisement ->
                    val device = BleDevice(
                        address = advertisement.address,
                        name = advertisement.name,
                        rssi = advertisement.rssi
                    )

                    // Add to discovered devices if not already present
                    val currentDevices = _discoveredDevices.value.toMutableList()
                    if (currentDevices.none { it.address == device.address }) {
                        currentDevices.add(device)
                        _discoveredDevices.value = currentDevices
                        Timber.d("Discovered device: ${device.name} (${device.address})")
                    }
                }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start scan")
            _connectionState.value = ConnectionState.Error("Failed to start scan: ${e.message}")
        }
        */
    }

    /**
     * Simulated device scanning (for testing without hardware)
     */
    private suspend fun simulateDeviceScanning() {
        // Simulate finding devices after delay
        delay(2000)

        val mockDevices = listOf(
            BleDevice("AA:BB:CC:DD:EE:01", "ESP32-ECG-001", -45),
            BleDevice("AA:BB:CC:DD:EE:02", "ESP32-ECG-002", -62),
            BleDevice("AA:BB:CC:DD:EE:03", "Nordic_ECG", -78)
        )

        _discoveredDevices.value = mockDevices
        _connectionState.value = ConnectionState.Disconnected
        Timber.i("Simulated scan found ${mockDevices.size} devices")
    }

    /**
     * Stop scanning
     */
    fun stopScanning() {
        _connectionState.value = ConnectionState.Disconnected
        Timber.i("Stopped BLE scan")

        // For real BLE:
        // scanner?.cancel()
    }

    /**
     * Connect to a specific device
     */
    suspend fun connect(device: BleDevice) {
        Timber.i("Connecting to device: ${device.name}")
        _connectionState.value = ConnectionState.Connecting

        try {
            // OPTION 1: Simulated connection (for testing)
            simulateConnection(device)

            // OPTION 2: Real BLE connection with Kable (uncomment when ready)
            /*
            peripheral = scope.peripheral(
                Advertisement(
                    address = device.address,
                    name = device.name
                )
            ) {
                // Connection configuration
                autoConnectIf { it.status == Peripheral.Status.Disconnected }
            }

            peripheral?.connect()

            // Discover services
            peripheral?.services?.forEach { service ->
                Timber.d("Service discovered: ${service.serviceUuid}")
                service.characteristics.forEach { characteristic ->
                    Timber.d("  Characteristic: ${characteristic.characteristicUuid}")
                }
            }

            currentDevice = device
            _connectionState.value = ConnectionState.Connected(device)

            // Start listening for ECG data
            startEcgDataCollection()
            */

        } catch (e: Exception) {
            Timber.e(e, "Failed to connect to device")
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
        }
    }

    /**
     * Simulated connection (for testing without hardware)
     */
    private suspend fun simulateConnection(device: BleDevice) {
        delay(1500)  // Simulate connection time

        currentDevice = device
        _connectionState.value = ConnectionState.Connected(device)

        // Start simulated ECG data stream
        startSimulatedEcgDataCollection()

        Timber.i("Simulated connection established to ${device.name}")
    }

    /**
     * Disconnect from current device
     */
    suspend fun disconnect() {
        Timber.i("Disconnecting from device")

        // Real BLE:
        // peripheral?.disconnect()

        currentDevice = null
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Start collecting ECG data from connected device (REAL)
     */
    private suspend fun startEcgDataCollection() {
        // Real implementation with Kable:
        /*
        val ecgCharacteristic = characteristicOf(
            service = ECG_SERVICE_UUID,
            characteristic = ECG_DATA_CHARACTERISTIC_UUID
        )

        peripheral?.observe(ecgCharacteristic)
            ?.catch { cause ->
                Timber.e(cause, "Failed to observe ECG data")
                _connectionState.value = ConnectionState.Error("Data stream failed")
            }
            ?.collect { data ->
                parseEcgData(data)
            }
        */

        Timber.i("Started ECG data collection")
    }

    /**
     * Start simulated ECG data collection (for testing)
     */
    private fun startSimulatedEcgDataCollection() {
        // Simulate realistic ECG data stream at 360 Hz
        kotlinx.coroutines.GlobalScope.launch {
            var sampleIndex = 0
            val samplingRate = 360
            val delayMs = 1000L / samplingRate  // ~2.78ms per sample

            while (_connectionState.value is ConnectionState.Connected) {
                // Generate simulated ECG waveform
                val t = (sampleIndex % 360) / 360.0  // One heartbeat per second
                val voltage = generateSimulatedEcgSample(t)

                _ecgDataFlow.value = EcgData(
                    timestamp = System.currentTimeMillis(),
                    voltage = voltage
                )

                sampleIndex++
                delay(delayMs)
            }
        }
    }

    /**
     * Generate realistic simulated ECG sample
     */
    private fun generateSimulatedEcgSample(t: Double): Float {
        // Generate P-QRS-T complex
        val baseline = -0.15

        // P wave (0-0.12s)
        val pWave = if (t in 0.05..0.17) {
            0.15 * kotlin.math.sin((t - 0.05) / 0.12 * Math.PI)
        } else 0.0

        // QRS complex (0.28-0.38s)
        val qrs = when {
            t in 0.28..0.31 -> -0.2 * kotlin.math.sin((t - 0.28) / 0.03 * Math.PI)  // Q
            t in 0.31..0.34 -> 1.2 * kotlin.math.sin((t - 0.31) / 0.03 * Math.PI)   // R
            t in 0.34..0.38 -> -0.15 * kotlin.math.sin((t - 0.34) / 0.04 * Math.PI) // S
            else -> 0.0
        }

        // T wave (0.5-0.75s)
        val tWave = if (t in 0.5..0.75) {
            0.25 * kotlin.math.sin((t - 0.5) / 0.25 * Math.PI)
        } else 0.0

        // Add small noise
        val noise = Random.nextDouble(-0.01, 0.01)

        return (baseline + pWave + qrs + tWave + noise).toFloat()
    }

    /**
     * Parse incoming ECG data from ESP32
     */
    private fun parseEcgData(bytes: ByteArray) {
        // ESP32 data format (update based on actual protocol):
        // Assuming: 2 bytes per sample, little-endian, 12-bit ADC (0-4095)
        // Scale to voltage: (ADC / 4095) * 3.3V - 1.65V (centered at 1.65V)

        try {
            // Example parsing for 2-byte samples
            for (i in bytes.indices step 2) {
                if (i + 1 < bytes.size) {
                    val adcValue = ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                            (bytes[i].toInt() and 0xFF)

                    // Convert 12-bit ADC to voltage (0-3.3V range, centered at 1.65V)
                    val voltage = ((adcValue / 4095.0) * 3.3 - 1.65).toFloat()

                    _ecgDataFlow.value = EcgData(
                        timestamp = System.currentTimeMillis(),
                        voltage = voltage
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse ECG data")
        }
    }

    /**
     * Check if Bluetooth is enabled
     */
    fun isBluetoothEnabled(): Boolean {
        // Real implementation:
        /*
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bluetoothManager.adapter?.isEnabled == true
        */
        return true  // Simulated
    }

    /**
     * Check required permissions
     */
    fun hasRequiredPermissions(): Boolean {
        // Real implementation:
        /*
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        */
        return true  // Simulated
    }

    companion object {
        // ESP32 BLE UUIDs (update these to match your ESP32 firmware)
        private const val ECG_SERVICE_UUID = "12345678-1234-1234-1234-123456789012"
        private const val ECG_DATA_CHARACTERISTIC_UUID = "12345678-1234-1234-1234-123456789013"
    }
}

/**
 * Connection states
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val device: BleDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Represents a discovered BLE device
 */
data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int
)

/**
 * ECG data packet
 */
data class EcgData(
    val timestamp: Long,
    val voltage: Float
)