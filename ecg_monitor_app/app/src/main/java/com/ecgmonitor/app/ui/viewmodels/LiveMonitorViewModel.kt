package com.ecgmonitor.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecgmonitor.app.data.ble.BleManager
import com.ecgmonitor.app.data.ble.ConnectionState
import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.mitbih.MitBihDataLoader
import com.ecgmonitor.app.data.models.*
import com.ecgmonitor.app.data.repository.EcgRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * LiveMonitorViewModel - Updated for BLE streaming and recording
 */
class LiveMonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val database = EcgDatabase.getDatabase(application)
    private val mitBihLoader = MitBihDataLoader(application)
    private val repository = EcgRepository(database, mitBihLoader)
    private val bleManager = BleManager(application)

    // BLE connection state
    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState

    // UI State
    private val _uiState = MutableStateFlow<LiveMonitorUiState>(LiveMonitorUiState.Idle)
    val uiState: StateFlow<LiveMonitorUiState> = _uiState.asStateFlow()

    // Real-time ECG Data (visible on screen)
    private val _ecgReadings = MutableStateFlow<List<EcgReading>>(emptyList())
    val ecgReadings: StateFlow<List<EcgReading>> = _ecgReadings.asStateFlow()

    // Arrhythmia Events (visible markers)
    private val _visibleArrhythmiaEvents = MutableStateFlow<List<ArrhythmiaEvent>>(emptyList())
    val visibleArrhythmiaEvents: StateFlow<List<ArrhythmiaEvent>> = _visibleArrhythmiaEvents.asStateFlow()

    // Current Session (for metadata)
    private val _currentSession = MutableStateFlow<EcgSession?>(null)
    val currentSession: StateFlow<EcgSession?> = _currentSession.asStateFlow()

    // Heart Rate
    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    // Recording state
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Recording buffer (stores data while recording)
    private val recordingBuffer = mutableListOf<EcgReading>()
    private val recordingEventsBuffer = mutableListOf<ArrhythmiaEvent>()
    private var recordingStartTime = 0L
    private var recordingSampleIndex = 0

    // Heart rate calculation
    private val recentBeatTimestamps = mutableListOf<Long>()

    init {
        // Listen to BLE data stream
        viewModelScope.launch {
            bleManager.ecgDataFlow
                .filterNotNull()
                .collect { ecgData ->
                    processIncomingEcgData(ecgData.timestamp, ecgData.voltage)
                }
        }

        // Update UI state based on connection
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        _uiState.value = LiveMonitorUiState.Connected(state.device.name ?: "ESP32")
                    }
                    is ConnectionState.Disconnected -> {
                        if (_isRecording.value) {
                            stopRecording() // Auto-stop if disconnected while recording
                        }
                        _uiState.value = LiveMonitorUiState.Idle
                    }
                    is ConnectionState.Error -> {
                        _uiState.value = LiveMonitorUiState.Error(state.message)
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Process incoming ECG data from BLE
     */
    private fun processIncomingEcgData(timestamp: Long, voltage: Float) {
        val reading = EcgReading(
            timestamp = timestamp,
            voltage = voltage,
            sampleIndex = recordingSampleIndex++
        )

        // Add to visible window
        val currentReadings = _ecgReadings.value.toMutableList()
        currentReadings.add(reading)

        // Keep window limited (3000 samples ~8 seconds)
        if (currentReadings.size > 3000) {
            currentReadings.removeAt(0)
        }

        _ecgReadings.value = currentReadings

        // If recording, add to buffer
        if (_isRecording.value) {
            recordingBuffer.add(reading)
        }

        // Simple peak detection for demo (would use ML in production)
        detectBeat(reading, currentReadings)
    }

    /**
     * Simple R-peak detection (placeholder for ML model)
     */
    private fun detectBeat(reading: EcgReading, recentReadings: List<EcgReading>) {
        // Very simple threshold-based detection
        // In production, use QRS detection algorithm or ML model

        if (recentReadings.size < 10) return

        val recent = recentReadings.takeLast(10)
        val avgVoltage = recent.map { it.voltage }.average()

        // If current reading is significantly above average, might be R-peak
        if (reading.voltage > avgVoltage + 0.5f) {
            val timeSinceLastBeat = if (recentBeatTimestamps.isNotEmpty()) {
                reading.timestamp - recentBeatTimestamps.last()
            } else 1000L

            // Avoid double-counting (beats should be >300ms apart)
            if (timeSinceLastBeat > 300) {
                recordBeat(reading)
            }
        }
    }

    /**
     * Record a detected beat
     */
    private fun recordBeat(reading: EcgReading) {
        recentBeatTimestamps.add(reading.timestamp)

        // Keep only last 10 beats
        if (recentBeatTimestamps.size > 10) {
            recentBeatTimestamps.removeAt(0)
        }

        // Calculate heart rate
        if (recentBeatTimestamps.size >= 2) {
            val intervals = mutableListOf<Long>()
            for (i in 1 until recentBeatTimestamps.size) {
                intervals.add(recentBeatTimestamps[i] - recentBeatTimestamps[i - 1])
            }

            val avgInterval = intervals.average()
            val bpm = (60000.0 / avgInterval).toInt().coerceIn(40, 200)
            _heartRate.value = bpm
        }

        // Create arrhythmia event (would use ML classification in production)
        val event = ArrhythmiaEvent(
            timestamp = reading.timestamp,
            type = ArrhythmiaType.NORMAL, // Placeholder - would be ML-classified
            confidence = 0.95f,
            sampleIndex = reading.sampleIndex,
            description = "Detected beat"
        )

        // Add to visible events
        val visibleEvents = _visibleArrhythmiaEvents.value.toMutableList()
        visibleEvents.add(event)

        // Keep limited
        if (visibleEvents.size > 50) {
            visibleEvents.removeAt(0)
        }

        _visibleArrhythmiaEvents.value = visibleEvents

        // If recording, add to buffer
        if (_isRecording.value) {
            recordingEventsBuffer.add(event)
        }
    }

    /**
     * Start recording session
     */
    fun startRecording() {
        if (_isRecording.value) return

        if (connectionState.value !is ConnectionState.Connected) {
            _uiState.value = LiveMonitorUiState.Error("Not connected to device")
            return
        }

        _isRecording.value = true
        recordingStartTime = System.currentTimeMillis()
        recordingBuffer.clear()
        recordingEventsBuffer.clear()
        recordingSampleIndex = 0

        // Create session metadata
        _currentSession.value = EcgSession(
            id = UUID.randomUUID().toString(),
            startTime = recordingStartTime,
            endTime = null,
            samplingRate = 360, // ESP32 sampling rate
            source = DataSource.ESP32_DEVICE,
            totalSamples = 0,
            duration = 0f
        )

        _uiState.value = LiveMonitorUiState.Recording
        Timber.i("Started recording session")
    }

    /**
     * Stop recording and save session
     */
    fun stopRecording() {
        if (!_isRecording.value) return

        _isRecording.value = false
        val endTime = System.currentTimeMillis()
        val duration = (endTime - recordingStartTime) / 1000f

        val session = _currentSession.value?.copy(
            endTime = endTime,
            totalSamples = recordingBuffer.size,
            duration = duration
        )

        if (session != null && recordingBuffer.isNotEmpty()) {
            // Save to database
            viewModelScope.launch {
                try {
                    val sessionId = repository.saveSession(
                        readings = recordingBuffer.toList(),
                        events = recordingEventsBuffer.toList(),
                        sessionMetadata = session,
                        sessionName = null // Will auto-generate
                    )

                    Timber.i("Saved recording: $sessionId (${recordingBuffer.size} samples, ${duration}s)")
                    _uiState.value = LiveMonitorUiState.RecordingSaved(sessionId)

                    // Clear buffers
                    recordingBuffer.clear()
                    recordingEventsBuffer.clear()

                } catch (e: Exception) {
                    Timber.e(e, "Failed to save recording")
                    _uiState.value = LiveMonitorUiState.Error("Failed to save recording: ${e.message}")
                }
            }
        }

        _currentSession.value = null
    }

    /**
     * Clear all data
     */
    fun clearData() {
        _ecgReadings.value = emptyList()
        _visibleArrhythmiaEvents.value = emptyList()
        _heartRate.value = 0
        recentBeatTimestamps.clear()

        if (!_isRecording.value) {
            _currentSession.value = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (_isRecording.value) {
            stopRecording()
        }
    }
}

/**
 * UI State for Live Monitor
 */
sealed class LiveMonitorUiState {
    object Idle : LiveMonitorUiState()
    data class Connected(val deviceName: String) : LiveMonitorUiState()
    object Recording : LiveMonitorUiState()
    data class RecordingSaved(val sessionId: String) : LiveMonitorUiState()
    data class Error(val message: String) : LiveMonitorUiState()
}