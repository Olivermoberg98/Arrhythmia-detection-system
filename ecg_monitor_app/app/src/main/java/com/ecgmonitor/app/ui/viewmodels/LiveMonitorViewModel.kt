package com.ecgmonitor.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecgmonitor.app.EcgMonitorApp
import com.ecgmonitor.app.data.ble.BleManager
import com.ecgmonitor.app.data.ble.ConnectionState
import com.ecgmonitor.app.data.ble.EcgPacket
import com.ecgmonitor.app.data.ble.InferenceResult
import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.mitbih.MitBihDataLoader
import com.ecgmonitor.app.data.models.*
import com.ecgmonitor.app.data.repository.EcgRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

class LiveMonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val database    = EcgDatabase.getDatabase(application)
    private val mitBihLoader = MitBihDataLoader(application)
    private val repository  = EcgRepository(database, mitBihLoader)
    private val bleManager = (application as EcgMonitorApp).bleManager

    // -------------------------------------------------------------------------
    // Public state flows
    // -------------------------------------------------------------------------

    /** Raw BLE connection lifecycle. */
    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState

    /**
     * True while the ECG lead-off flag (byte 5 = 0x03) is set in incoming packets.
     * The UI should overlay a "Leads off — reattach electrodes" warning when true.
     * Exposed directly from BleManager so no extra mapping is needed.
     */
    val leadsOff: StateFlow<Boolean> = bleManager.leadOffState

    /** Screen-level UI state (connection lifecycle + recording lifecycle). */
    private val _uiState = MutableStateFlow<LiveMonitorUiState>(LiveMonitorUiState.Idle)
    val uiState: StateFlow<LiveMonitorUiState> = _uiState.asStateFlow()

    /**
     * Sliding window of the most recent [VISIBLE_WINDOW_SAMPLES] individual ECG readings.
     * Each reading has its own millisecond timestamp computed from the packet header,
     * so the chart can detect gaps by checking for timestamp jumps > ~80 ms between
     * consecutive readings (= 2× the 40 ms notification interval).
     */
    private val _ecgReadings = MutableStateFlow<List<EcgReading>>(emptyList())
    val ecgReadings: StateFlow<List<EcgReading>> = _ecgReadings.asStateFlow()

    /**
     * Classification events received from the ESP32's inference characteristic.
     * Each entry maps directly to one [InferenceResult] — no local placeholder logic.
     */
    private val _visibleArrhythmiaEvents = MutableStateFlow<List<ArrhythmiaEvent>>(emptyList())
    val visibleArrhythmiaEvents: StateFlow<List<ArrhythmiaEvent>> = _visibleArrhythmiaEvents.asStateFlow()

    /** The most recent raw [InferenceResult]. Useful for a persistent classification badge. */
    private val _lastInferenceResult = MutableStateFlow<InferenceResult?>(null)
    val lastInferenceResult: StateFlow<InferenceResult?> = _lastInferenceResult.asStateFlow()

    /** Cumulative number of BLE packets dropped since the last connect() call. */
    private val _droppedPacketCount = MutableStateFlow(0)
    val droppedPacketCount: StateFlow<Int> = _droppedPacketCount.asStateFlow()

    /** Current recording session metadata. Non-null while a session is active. */
    private val _currentSession = MutableStateFlow<EcgSession?>(null)
    val currentSession: StateFlow<EcgSession?> = _currentSession.asStateFlow()

    /** Beats-per-minute derived from locally detected R-peaks. Updated in real time. */
    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // -------------------------------------------------------------------------
    // Recording buffers
    // -------------------------------------------------------------------------

    private val recordingBuffer       = mutableListOf<EcgReading>()
    private val recordingEventsBuffer = mutableListOf<ArrhythmiaEvent>()
    private var recordingStartTime    = 0L

    /**
     * Monotonically increasing sample counter across the current live session.
     * Reset on every startRecording() call so saved sessions always start at 0.
     */
    private var recordingSampleIndex  = 0

    // -------------------------------------------------------------------------
    // Beat detection — adaptive threshold on raw int16 samples
    // -------------------------------------------------------------------------

    /**
     * Circular buffer of the last [ROLLING_WINDOW_SAMPLES] raw sample values.
     * Used to compute a dynamic R-peak threshold that adapts to signal amplitude.
     */
    private val rollingWindow = ArrayDeque<Float>(ROLLING_WINDOW_SAMPLES)

    /**
     * Timestamps (ms) of the last [MAX_BEAT_HISTORY] detected R-peaks.
     * Used to compute the rolling average RR interval → heart rate.
     */
    private val recentBeatTimestamps = ArrayDeque<Long>(MAX_BEAT_HISTORY)

    // -------------------------------------------------------------------------
    // init — wire all BLE flows
    // -------------------------------------------------------------------------

    init {
        // 1. ECG stream packets — the primary data path.
        //    Each 26-byte packet delivers 10 samples; we expand them individually.
        viewModelScope.launch {
            bleManager.ecgPacketFlow
                .filterNotNull()
                .collect { packet -> processIncomingPacket(packet) }
        }

        // 2. Inference events — fired by the ESP32 only when a classification occurs.
        //    These replace the old local ArrhythmiaEvent placeholder entirely.
        viewModelScope.launch {
            bleManager.inferenceResultFlow
                .filterNotNull()
                .collect { result -> processInferenceResult(result) }
        }

        // 3. Lead-off transitions — reset detection state on reconnect to prevent
        //    the noisy reconnect transient from triggering phantom R-peaks.
        viewModelScope.launch {
            bleManager.leadOffState.collect { isOff ->
                if (!isOff) {
                    Timber.i("Leads reconnected — resetting beat detector")
                    rollingWindow.clear()
                    recentBeatTimestamps.clear()
                }
            }
        }

        // 4. Connection lifecycle — manage uiState transitions.
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        _droppedPacketCount.value = 0
                        // Don't clobber "Recording" state if a session is already active
                        if (!_isRecording.value) {
                            _uiState.value = LiveMonitorUiState.Connected(
                                state.device.name ?: "ESP32"
                            )
                        }
                    }
                    is ConnectionState.Disconnected -> {
                        if (_isRecording.value) stopRecording()   // auto-save on drop
                        _uiState.value = LiveMonitorUiState.Idle
                    }
                    is ConnectionState.Error -> {
                        _uiState.value = LiveMonitorUiState.Error(state.message)
                    }
                    else -> { /* Scanning / Connecting — no uiState change needed */ }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // ECG packet processing
    // -------------------------------------------------------------------------

    /**
     * Expands a single 26-byte [EcgPacket] into [BleManager.ECG_SAMPLES_PER_PACKET]
     * individual [EcgReading]s and feeds them into the visible window, recording
     * buffer, and beat detector.
     *
     * Gap handling: when [EcgPacket.droppedBefore] > 0 the beat detector is reset
     * so it does not connect R-peak detection across the discontinuity.  The chart
     * can independently detect gaps by observing timestamp jumps between consecutive
     * readings that exceed twice the notification interval (~80 ms).
     */
    private fun processIncomingPacket(packet: EcgPacket) {
        // --- Dropped-packet bookkeeping ---
        if (packet.droppedBefore > 0) {
            _droppedPacketCount.value += packet.droppedBefore
            // Clear detector state so the discontinuity doesn't produce fake beats
            rollingWindow.clear()
            recentBeatTimestamps.clear()
            Timber.w("Gap: ${packet.droppedBefore} packet(s) missing before seq=${packet.seqNum}")
        }

        // Skip sample processing while leads are disconnected.
        // The leadsOff StateFlow already notifies the UI separately.
        //if (packet.leadsOff) return

        // --- Expand 10 samples → EcgReading list ---
        // Each sample is separated by SAMPLE_INTERVAL_MS (4 ms at 250 Hz).
        // packet.timestampMs is the ESP32 millis() at the START of the batch.
        val newReadings = packet.samples.mapIndexed { i, rawSample ->
            EcgReading(
                timestamp    = packet.timestampMs + (i.toLong() * BleManager.SAMPLE_INTERVAL_MS),
                voltage      = rawSample.toFloat(),   // raw int16 centred at 0; chart auto-scales
                sampleIndex  = recordingSampleIndex++
            )
        }

        // --- Update visible window ---
        val updated = _ecgReadings.value.toMutableList()
        updated.addAll(newReadings)
        // Drop oldest samples to stay within the rolling display window
        if (updated.size > VISIBLE_WINDOW_SAMPLES) {
            updated.subList(0, updated.size - VISIBLE_WINDOW_SAMPLES).clear()
        }
        _ecgReadings.value = updated

        // --- Recording buffer ---
        if (_isRecording.value) {
            recordingBuffer.addAll(newReadings)
        }

        // --- Beat detection (for local heart-rate display) ---
        // Process each sample individually so the threshold window stays accurate.
        newReadings.forEach { reading ->
            pushToRollingWindow(reading.voltage)
            detectBeat(reading)
        }
    }

    // -------------------------------------------------------------------------
    // Inference result processing
    // -------------------------------------------------------------------------

    /**
     * Converts a raw [InferenceResult] from the ESP32 into an [ArrhythmiaEvent]
     * and appends it to the visible event list and (if recording) the save buffer.
     *
     * The class_id mapping mirrors the ESP32 firmware's label order:
     *   0 = Normal, 1 = AFib, 2 = Bradycardia, 3 = Tachycardia, 4 = Other
     */
    private fun processInferenceResult(result: InferenceResult) {
        _lastInferenceResult.value = result

        val arrhythmiaType = when (result.classId) {
            0    -> ArrhythmiaType.NORMAL
            1    -> ArrhythmiaType.ATRIAL_FIBRILLATION
            2    -> ArrhythmiaType.BRADYCARDIA
            3    -> ArrhythmiaType.TACHYCARDIA
            else -> ArrhythmiaType.UNKNOWN
        }

        // Approximate sample index from wall-clock difference so the event marker
        // lines up correctly on the chart's sample-index axis.
        val approxSampleIndex: Int = if (recordingStartTime > 0) {
            ((result.timestampMs - recordingStartTime) * TARGET_FS / 1_000L)
                .toInt().coerceAtLeast(0)
        } else {
            recordingSampleIndex   // Best guess when not recording
        }

        val confidencePct = String.format("%.0f%%", result.confidence * 100f)
        val event = ArrhythmiaEvent(
            timestamp   = result.timestampMs,
            type        = arrhythmiaType,
            confidence  = result.confidence,
            sampleIndex = approxSampleIndex,
            description = "${result.className} – $confidencePct confidence"
        )

        // Update visible event list
        val updated = _visibleArrhythmiaEvents.value.toMutableList()
        updated.add(event)
        if (updated.size > MAX_VISIBLE_EVENTS) updated.removeAt(0)
        _visibleArrhythmiaEvents.value = updated

        if (_isRecording.value) recordingEventsBuffer.add(event)

        Timber.i("Inference → ${event.description}  t=${result.timestampMs} ms")
    }

    // -------------------------------------------------------------------------
    // Beat detection — adaptive threshold
    // -------------------------------------------------------------------------

    /** Append one sample to the rolling window, dropping the oldest when full. */
    private fun pushToRollingWindow(value: Float) {
        if (rollingWindow.size >= ROLLING_WINDOW_SAMPLES) rollingWindow.removeFirst()
        rollingWindow.addLast(value)
    }

    /**
     * Adaptive R-peak detector for raw int16 samples.
     *
     * Threshold = midpoint + [BEAT_THRESHOLD_FACTOR] × peak-to-peak amplitude
     * of the last [ROLLING_WINDOW_SAMPLES] samples (1 second at 250 Hz).
     *
     * This self-calibrates to any ADC gain or electrode placement without
     * needing a hard-coded millivolt threshold.
     *
     * Only used for local heart-rate display. Classification comes from the
     * ESP32's inference characteristic via [processInferenceResult].
     */
    private fun detectBeat(reading: EcgReading) {
        // Need at least half a window of context before we trust the threshold
        if (rollingWindow.size < ROLLING_WINDOW_SAMPLES / 2) return

        val windowMax = rollingWindow.max()
        val windowMin = rollingWindow.min()
        val amplitude = windowMax - windowMin

        // Reject flat signal (noise / leads-off transient)
        if (amplitude < MIN_SIGNAL_AMPLITUDE) return

        // Threshold = bottom of range + (amplitude × factor).
        // This means "sample must be in the top (1-factor) portion of the observed range".
        // The old formula used midpoint+factor×amplitude which placed the threshold
        // ABOVE the R-peak for a symmetric signal, causing zero beats to be detected.
        val threshold = windowMin + (amplitude * BEAT_THRESHOLD_FACTOR)

        if (reading.voltage <= threshold) return

        val timeSinceLastBeat = if (recentBeatTimestamps.isNotEmpty())
            reading.timestamp - recentBeatTimestamps.last()
        else
            MIN_BEAT_INTERVAL_MS + 1L   // First beat — always allow

        if (timeSinceLastBeat >= MIN_BEAT_INTERVAL_MS) {
            recordBeat(reading.timestamp)
        }
    }

    /**
     * Register a confirmed R-peak and update the rolling heart-rate estimate.
     * Deliberately does NOT create [ArrhythmiaEvent]s — those come exclusively
     * from [processInferenceResult] now.
     */
    private fun recordBeat(timestampMs: Long) {
        if (recentBeatTimestamps.size >= MAX_BEAT_HISTORY) recentBeatTimestamps.removeFirst()
        recentBeatTimestamps.addLast(timestampMs)

        if (recentBeatTimestamps.size >= 2) {
            val rrIntervals = (1 until recentBeatTimestamps.size).map { i ->
                recentBeatTimestamps[i] - recentBeatTimestamps[i - 1]
            }
            val avgRrMs = rrIntervals.average()
            _heartRate.value = (60_000.0 / avgRrMs).toInt().coerceIn(30, 250)
        }
    }

    // -------------------------------------------------------------------------
    // Recording controls
    // -------------------------------------------------------------------------

    fun startRecording() {
        if (_isRecording.value) return
        if (connectionState.value !is ConnectionState.Connected) {
            _uiState.value = LiveMonitorUiState.Error("Not connected to device")
            return
        }

        recordingBuffer.clear()
        recordingEventsBuffer.clear()
        recordingSampleIndex = 0
        recordingStartTime   = System.currentTimeMillis()
        _isRecording.value   = true

        _currentSession.value = EcgSession(
            id           = UUID.randomUUID().toString(),
            startTime    = recordingStartTime,
            endTime      = null,
            samplingRate = TARGET_FS,
            source       = DataSource.ESP32_DEVICE,
            totalSamples = 0,
            duration     = 0f
        )

        _uiState.value = LiveMonitorUiState.Recording
        Timber.i("Recording started")
    }

    fun stopRecording() {
        if (!_isRecording.value) return
        _isRecording.value = false

        val endTime  = System.currentTimeMillis()
        val duration = (endTime - recordingStartTime) / 1_000f

        val session = _currentSession.value?.copy(
            endTime      = endTime,
            totalSamples = recordingBuffer.size,
            duration     = duration
        )

        if (session != null && recordingBuffer.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    val sessionId = repository.saveSession(
                        readings        = recordingBuffer.toList(),
                        events          = recordingEventsBuffer.toList(),
                        sessionMetadata = session,
                        sessionName     = null   // Auto-generated by repository
                    )
                    Timber.i("Saved session $sessionId — ${recordingBuffer.size} samples, ${duration}s")
                    _uiState.value = LiveMonitorUiState.RecordingSaved(sessionId)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to save recording")
                    _uiState.value = LiveMonitorUiState.Error("Failed to save: ${e.message}")
                } finally {
                    recordingBuffer.clear()
                    recordingEventsBuffer.clear()
                }
            }
        }

        _currentSession.value = null
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Called by the UI after it has successfully navigated to SessionDetailScreen in response
     * to a [LiveMonitorUiState.RecordingSaved] event.  Resets uiState back to [Connected] or
     * [Idle] so that pressing back from SessionDetailScreen does not re-trigger navigation.
     */
    fun acknowledgeRecordingSaved() {
        val cs = connectionState.value
        _uiState.value = if (cs is ConnectionState.Connected) {
            LiveMonitorUiState.Connected(cs.device.name ?: "ESP32")
        } else {
            LiveMonitorUiState.Idle
        }
    }

    /**
     * Wipe all live display state without touching an active recording.
     * Resets the beat-detector rolling state so threshold recalibrates cleanly.
     */
    fun clearData() {
        _ecgReadings.value          = emptyList()
        _visibleArrhythmiaEvents.value = emptyList()
        _lastInferenceResult.value  = null
        _heartRate.value            = 0
        _droppedPacketCount.value   = 0
        recentBeatTimestamps.clear()
        rollingWindow.clear()
        recordingSampleIndex = 0

        if (!_isRecording.value) _currentSession.value = null
    }

    override fun onCleared() {
        super.onCleared()
        if (_isRecording.value) stopRecording()
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        /** Sampling rate the ESP32 ADC runs at. Must match BleManager and the trained model. */
        private const val TARGET_FS = 250

        /**
         * Number of samples kept in the live display buffer.
         * 3 000 samples ÷ 250 Hz = 12 seconds of waveform on screen.
         */
        private const val VISIBLE_WINDOW_SAMPLES = 2_000

        /** Maximum inference/arrhythmia events shown in the event overlay. */
        private const val MAX_VISIBLE_EVENTS = 50

        /**
         * Rolling window for adaptive beat detection: 1 second of samples.
         * Threshold = midpoint + BEAT_THRESHOLD_FACTOR × peak-to-peak amplitude.
         */
        private const val ROLLING_WINDOW_SAMPLES  = 250      // 1 s @ 250 Hz
        private const val BEAT_THRESHOLD_FACTOR   = 0.70f    // R-peak must be in top 30% of signal range; prevents T-wave triggers
        private const val MIN_BEAT_INTERVAL_MS    = 300L     // ~200 bpm max; rejects double-triggers
        private const val MAX_BEAT_HISTORY        = 10       // RR intervals averaged for HR

        /**
         * Minimum peak-to-peak amplitude (raw int16 units) below which we treat the
         * signal as flat (leads transitioning, noise).  Tune for your ADC gain.
         * A value of 50 corresponds to ~1–2 mV depending on the AD8232 gain setting.
         */
        private const val MIN_SIGNAL_AMPLITUDE    = 50f
    }
}

// =============================================================================
// UI State
// =============================================================================

sealed class LiveMonitorUiState {
    /** Not connected, idle. */
    object Idle : LiveMonitorUiState()

    /** Connected, stream flowing, not recording. */
    data class Connected(val deviceName: String) : LiveMonitorUiState()

    /** Recording session in progress. */
    object Recording : LiveMonitorUiState()

    /** Session was successfully saved; [sessionId] identifies it in the DB. */
    data class RecordingSaved(val sessionId: String) : LiveMonitorUiState()

    /** An unrecoverable error occurred. */
    data class Error(val message: String) : LiveMonitorUiState()
}