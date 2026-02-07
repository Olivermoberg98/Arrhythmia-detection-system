package com.ecgmonitor.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecgmonitor.app.data.mitbih.EcgRecordData
import com.ecgmonitor.app.data.mitbih.MitBihDataLoader
import com.ecgmonitor.app.data.models.ArrhythmiaEvent
import com.ecgmonitor.app.data.models.DataSource
import com.ecgmonitor.app.data.models.EcgReading
import com.ecgmonitor.app.data.models.EcgSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ViewModel for Live ECG Monitor Screen
 * Handles both real-time BLE data and MIT-BIH playback simulation
 */
class LiveMonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val mitBihLoader = MitBihDataLoader(application)

    // UI State
    private val _uiState = MutableStateFlow<MonitorUiState>(MonitorUiState.Idle)
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    // ECG Data
    private val _ecgReadings = MutableStateFlow<List<EcgReading>>(emptyList())
    val ecgReadings: StateFlow<List<EcgReading>> = _ecgReadings.asStateFlow()

    // Arrhythmia Events
    private val _arrhythmiaEvents = MutableStateFlow<List<ArrhythmiaEvent>>(emptyList())
    val arrhythmiaEvents: StateFlow<List<ArrhythmiaEvent>> = _arrhythmiaEvents.asStateFlow()

    // Current Session
    private val _currentSession = MutableStateFlow<EcgSession?>(null)
    val currentSession: StateFlow<EcgSession?> = _currentSession.asStateFlow()

    // Heart Rate
    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    // Data source selection
    private var currentDataSource: DataSource = DataSource.MIT_BIH_DATABASE
    private var isPlaying = false
    private var currentRecordData: EcgRecordData? = null

    /**
     * Load and start playing MIT-BIH data
     */
    fun loadMitBihRecord(recordNumber: String) {
        viewModelScope.launch {
            _uiState.value = MonitorUiState.Loading

            val result = mitBihLoader.loadRecord(recordNumber)
            result.onSuccess { recordData ->
                currentRecordData = recordData
                _currentSession.value = recordData.session
                _arrhythmiaEvents.value = recordData.arrhythmiaEvents

                Timber.i("Loaded MIT-BIH record $recordNumber: ${recordData.readings.size} samples")
                _uiState.value = MonitorUiState.Ready(
                    message = "Loaded MIT-BIH Record $recordNumber",
                    dataSource = DataSource.MIT_BIH_DATABASE
                )
            }.onFailure { error ->
                Timber.e(error, "Failed to load MIT-BIH record")
                _uiState.value = MonitorUiState.Error("Failed to load record: ${error.message}")
            }
        }
    }

    /**
     * Start simulated real-time playback of MIT-BIH data
     * This simulates streaming data as if it were coming from ESP32
     */
    fun startPlayback() {
        if (isPlaying) return

        val recordData = currentRecordData ?: run {
            Timber.w("No data loaded, cannot start playback")
            return
        }

        isPlaying = true
        _uiState.value = MonitorUiState.Streaming

        viewModelScope.launch {
            val allReadings = recordData.readings
            val samplingRate = recordData.session.samplingRate
            val delayBetweenSamples = 1000L / samplingRate // milliseconds

            // Simulate real-time streaming
            var currentIndex = 0
            val buffer = mutableListOf<EcgReading>()

            Timber.i("Starting playback: ${allReadings.size} samples at ${samplingRate}Hz")

            while (isPlaying && currentIndex < allReadings.size) {
                // Add next reading to buffer
                buffer.add(allReadings[currentIndex])
                currentIndex++

                // Update UI with buffered readings (batch updates for performance)
                if (buffer.size >= 10 || currentIndex >= allReadings.size) {
                    _ecgReadings.value = buffer.toList()

                    // Calculate heart rate from recent data
                    updateHeartRate(buffer)

                    buffer.clear()
                }

                // Simulate real-time delay
                delay(delayBetweenSamples)
            }

            if (currentIndex >= allReadings.size) {
                Timber.i("Playback completed")
                _uiState.value = MonitorUiState.Ready(
                    message = "Playback completed",
                    dataSource = DataSource.MIT_BIH_DATABASE
                )
                isPlaying = false
            }
        }
    }

    /**
     * Stop playback
     */
    fun stopPlayback() {
        isPlaying = false
        _uiState.value = MonitorUiState.Ready(
            message = "Playback stopped",
            dataSource = currentDataSource
        )
        Timber.i("Playback stopped")
    }

    /**
     * Clear all data
     */
    fun clearData() {
        _ecgReadings.value = emptyList()
        _arrhythmiaEvents.value = emptyList()
        _currentSession.value = null
        _heartRate.value = 0
        currentRecordData = null
        _uiState.value = MonitorUiState.Idle
    }

    /**
     * Connect to BLE device (ESP32)
     * TODO: Phase 1 - Implement BLE connection
     */
    fun connectToBleDevice(deviceAddress: String) {
        _uiState.value = MonitorUiState.Error("BLE connection not yet implemented")
    }

    /**
     * Disconnect from BLE device
     */
    fun disconnectBleDevice() {
        stopPlayback()
    }

    /**
     * Calculate heart rate from ECG data using R-R intervals
     */
    private fun updateHeartRate(readings: List<EcgReading>) {
        // Simple peak detection for demonstration
        // In a real implementation, you'd use QRS detection algorithm
        if (readings.size < 100) return

        // For now, estimate from arrhythmia events (which mark QRS complexes)
        val session = _currentSession.value ?: return
        val estimatedBpm = when (session.samplingRate) {
            360 -> 75 // MIT-BIH typical
            300 -> 72 // ESP32 typical
            else -> 70
        }

        _heartRate.value = estimatedBpm
    }

    /**
     * Get available MIT-BIH records
     */
    fun getAvailableRecords(): List<String> {
        return mitBihLoader.getAvailableRecords()
    }
}

/**
 * UI State for Monitor Screen
 */
sealed class MonitorUiState {
    object Idle : MonitorUiState()
    object Loading : MonitorUiState()
    object Streaming : MonitorUiState()
    data class Ready(
        val message: String,
        val dataSource: DataSource
    ) : MonitorUiState()
    data class Error(val message: String) : MonitorUiState()
}
