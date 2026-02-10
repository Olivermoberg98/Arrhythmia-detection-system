package com.ecgmonitor.app.data.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import com.juul.kable.logs.Logging
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber

class BleManager(private val context: Context) {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDevice>> = _discoveredDevices.asStateFlow()

    private val _ecgDataFlow = MutableStateFlow<EcgData?>(null)
    val ecgDataFlow: StateFlow<EcgData?> = _ecgDataFlow.asStateFlow()

    private var currentDevice: BleDevice? = null
    private var peripheral: Peripheral? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun startScanning() {
        if (!BlePermissions.allGranted(context)) {
            _connectionState.value = ConnectionState.Error("Bluetooth permissions not granted")
            return
        }
        if (!isBluetoothEnabled()) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
            return
        }

        Timber.i("Starting BLE scan")
        _connectionState.value = ConnectionState.Scanning
        _discoveredDevices.value = emptyList()

        try {
            Scanner().advertisements
                .catch { cause ->
                    Timber.e(cause, "Scan failed")
                    _connectionState.value = ConnectionState.Error("Scan failed: ${cause.message}")
                }
                .collect { advertisement ->
                    val name = advertisement.name
                    if (name.isNullOrBlank()) return@collect
                    val device = BleDevice(
                        address = advertisement.address,
                        name = advertisement.name,
                        rssi = advertisement.rssi
                    )
                    val current = _discoveredDevices.value.toMutableList()
                    if (current.none { it.address == device.address }) {
                        current.add(device)
                        _discoveredDevices.value = current
                        Timber.d("Found: ${device.name} (${device.address})")
                    }
                }
        } catch (e: Exception) {
            Timber.e(e, "Scan error")
            _connectionState.value = ConnectionState.Error("Scan failed: ${e.message}")
        }
    }

    fun stopScanning() {
        _connectionState.value = ConnectionState.Disconnected
        Timber.i("Scan stopped")
    }

    suspend fun connect(device: BleDevice) {
        if (!BlePermissions.allGranted(context)) {
            _connectionState.value = ConnectionState.Error("Bluetooth permissions not granted")
            return
        }
        if (!isBluetoothEnabled()) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
            return
        }

        Timber.i("Connecting to ${device.name}")
        _connectionState.value = ConnectionState.Connecting

        try {
            peripheral = scope.peripheral(device.address) {
                logging { level = Logging.Level.Events }
            }

            peripheral?.connect()

            peripheral?.services?.forEach { service ->
                Timber.d("Service: ${service.serviceUuid}")
                service.characteristics.forEach { char ->
                    Timber.d("  Char: ${char.characteristicUuid}")
                }
            }

            currentDevice = device
            _connectionState.value = ConnectionState.Connected(device)

            startEcgDataCollection()

        } catch (e: Exception) {
            Timber.e(e, "Connection failed")
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
        }
    }

    suspend fun disconnect() {
        peripheral?.disconnect()
        currentDevice = null
        _connectionState.value = ConnectionState.Disconnected
        Timber.i("Disconnected")
    }

    private fun startEcgDataCollection() {
        scope.launch {
            try {
                val characteristic = characteristicOf(
                    service = ECG_SERVICE_UUID,
                    characteristic = ECG_DATA_CHARACTERISTIC_UUID
                )
                peripheral?.observe(characteristic)
                    ?.catch { cause ->
                        Timber.e(cause, "Data stream failed")
                        _connectionState.value = ConnectionState.Error("Data stream failed")
                    }
                    ?.collect { parseEcgData(it) }
            } catch (e: Exception) {
                Timber.e(e, "Data collection error")
            }
        }
    }

    private fun parseEcgData(bytes: ByteArray) {
        try {
            for (i in bytes.indices step 2) {
                if (i + 1 < bytes.size) {
                    val adcValue = ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i].toInt() and 0xFF)
                    val voltage = ((adcValue / 4095.0) * 3.3 - 1.65).toFloat()
                    _ecgDataFlow.value = EcgData(timestamp = System.currentTimeMillis(), voltage = voltage)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Parse error")
        }
    }

    fun setError(message: String) {
        _connectionState.value = ConnectionState.Error(message)
    }

    fun isBluetoothEnabled(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    fun hasRequiredPermissions(): Boolean = BlePermissions.allGranted(context)

    companion object {
        private const val ECG_SERVICE_UUID = "12345678-1234-1234-1234-123456789012"
        private const val ECG_DATA_CHARACTERISTIC_UUID = "12345678-1234-1234-1234-123456789013"
    }
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val device: BleDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

data class BleDevice(val address: String, val name: String?, val rssi: Int)

data class EcgData(val timestamp: Long, val voltage: Float)