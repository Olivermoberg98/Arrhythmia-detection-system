package com.ecgmonitor.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecgmonitor.app.data.ble.BleDevice
import com.ecgmonitor.app.data.ble.BleManager
import com.ecgmonitor.app.data.ble.ConnectionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager(application)

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState
    val discoveredDevices: StateFlow<List<BleDevice>> = bleManager.discoveredDevices

    fun startScanning() {
        viewModelScope.launch {
            bleManager.startScanning()
        }
    }

    fun stopScanning() {
        bleManager.stopScanning()
    }

    fun connectToDevice(device: BleDevice) {
        viewModelScope.launch {
            bleManager.connect(device)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            bleManager.disconnect()
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return bleManager.isBluetoothEnabled()
    }

    fun hasRequiredPermissions(): Boolean {
        return bleManager.hasRequiredPermissions()
    }
}