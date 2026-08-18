package com.ecgmonitor.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecgmonitor.app.EcgMonitorApp
import com.ecgmonitor.app.data.ble.BleManager
import com.ecgmonitor.app.data.ble.ConnectionState
import com.ecgmonitor.app.data.ble.EcgPacket
import com.ecgmonitor.app.data.ble.BeatWindow
import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.mitbih.MitBihDataLoader
import com.ecgmonitor.app.data.ml.ArrhythmiaClassifier
import com.ecgmonitor.app.data.models.ClassificationResult
import com.ecgmonitor.app.data.models.ArrhythmiaEvent
import com.ecgmonitor.app.data.models.ArrhythmiaType
import com.ecgmonitor.app.data.models.DataSource
import com.ecgmonitor.app.data.models.EcgReading
import com.ecgmonitor.app.data.models.EcgSession
import com.ecgmonitor.app.data.repository.EcgRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import java.util.UUID

class LiveMonitorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = EcgRepository(
        database     = EcgDatabase.getDatabase(application),
        mitBihLoader = MitBihDataLoader(application)
    )
    private val bleManager  = (application as EcgMonitorApp).bleManager
    private val syncManager = (application as EcgMonitorApp).syncManager

    // region Public state

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState
    private val classifier = ArrhythmiaClassifier(getApplication())
    private val _classificationResults = MutableStateFlow<ClassificationResult?>(null)
    val classificationResults: StateFlow<ClassificationResult?> = _classificationResults.asStateFlow()


    /**
     * True while the lead-off flag (byte 5 = 0x03) is set in incoming packets.
     * Collected by LiveMonitorScreen to drive the leads status indicator.
     */
    val leadsOff: StateFlow<Boolean> = bleManager.leadOffState

    private val _uiState = MutableStateFlow<LiveMonitorUiState>(LiveMonitorUiState.Idle)
    val uiState: StateFlow<LiveMonitorUiState> = _uiState.asStateFlow()

    /**
     * Sliding window of the most recent [VISIBLE_WINDOW_SAMPLES] ECG readings.
     *
     * Backed by a pre-allocated [ArrayDeque] rather than a copied [MutableList],
     * so the hot path never allocates beyond the fixed capacity.
     */
    private val _ecgReadings = MutableStateFlow<List<EcgReading>>(emptyList())
    val ecgReadings: StateFlow<List<EcgReading>> = _ecgReadings.asStateFlow()

    /** Classification events received from app-side TFLite inference. */
    private val _visibleArrhythmiaEvents = MutableStateFlow<List<ArrhythmiaEvent>>(emptyList())
    val visibleArrhythmiaEvents: StateFlow<List<ArrhythmiaEvent>> = _visibleArrhythmiaEvents.asStateFlow()

    /** Most recent classification result — used for the persistent classification badge. */
    private val _lastClassificationResult = MutableStateFlow<ClassificationResult?>(null)
    val lastClassificationResult: StateFlow<ClassificationResult?> = _lastClassificationResult.asStateFlow()

    /** Cumulative BLE packets dropped since the last connect() call. */
    private val _droppedPacketCount = MutableStateFlow(0)
    val droppedPacketCount: StateFlow<Int> = _droppedPacketCount.asStateFlow()

    private val _currentSession = MutableStateFlow<EcgSession?>(null)
    val currentSession: StateFlow<EcgSession?> = _currentSession.asStateFlow()

    /** BPM derived from locally detected R-peaks, updated in real time. */
    private val _heartRate = MutableStateFlow(0)
    val heartRate: StateFlow<Int> = _heartRate.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // endregion

    // region Recording buffers (accessed only from the Default dispatcher)

    private val recordingBuffer       = mutableListOf<EcgReading>()
    private val recordingEventsBuffer = mutableListOf<ArrhythmiaEvent>()
    private var recordingStartTime    = 0L

    /**
     * Heart-rate samples collected during recording. Each confirmed beat appends
     * the current smoothed BPM, enabling accurate min/max/avg for the saved session.
     */
    private val recordingHrSamples = mutableListOf<Int>()

    /**
     * EMA state for the heart-rate display.
     * Prevents the BPM badge from flickering on every R-peak.
     * Reset to 0f on lead disconnect or data clear.
     */
    private var smoothedHeartRate = 0f

    /**
     * Monotonically increasing sample counter across the current live session.
     * Reset on every [startRecording] call so saved sessions always start at index 0.
     */
    private var recordingSampleIndex = 0

    // endregion

    // region Beat detection state

    /**
     * Circular buffer of the last [ROLLING_WINDOW_SAMPLES] raw sample values.
     *
     * We maintain a running min and max alongside this deque so [detectBeat] is O(1)
     * per sample rather than O(n).
     * The min/max values are recomputed from scratch only when the evicted element
     * was the current extremum (O(n) at most twice per packet, amortised O(1)).
     */
    private val rollingWindow          = ArrayDeque<Float>(ROLLING_WINDOW_SAMPLES)
    private var rollingMin             = Float.MAX_VALUE
    private var rollingMax             = Float.MIN_VALUE

    /**
     * Timestamps (ms) of the last [MAX_BEAT_HISTORY] detected R-peaks,
     * used to compute the rolling average RR interval → heart rate.
     */
    private val recentBeatTimestamps = ArrayDeque<Long>(MAX_BEAT_HISTORY)

    // endregion

    // region Init — wire BLE flows

    init {
        // ── ECG stream: primary data path ─────────────────────────────────────────
        // Collected on Dispatchers.Default so all packet processing — list management,
        // beat detection, recording buffer — runs off the main thread.
        // Previously this ran on Dispatchers.Main (viewModelScope default), which meant
        // every packet directly competed with UI rendering for the main thread.
        viewModelScope.launch(Dispatchers.Default) {
            bleManager.ecgPacketFlow.collect { packet ->
                processIncomingPacket(packet)
            }
        }

        // ── Beat window classification: separate collector ───────────────────────
        // Classification runs on beat windows from ESP32. Each beat window contains
        // 250 ECG samples + 3 RR features. TFLite runs on the Android device (~20-50ms).
        viewModelScope.launch(Dispatchers.Default) {
            bleManager.beatWindowFlow.collect { beatWindow ->
                try {
                    val result = classifier.classify(beatWindow)

                    // Update last classification result for UI badge
                    _lastClassificationResult.value = result
                    _classificationResults.value = result

                    // Create arrhythmia event if valid and abnormal
                    if (result.isValid && result.classId != 0) {
                        val eventType = when (result.classId) {
                            1 -> ArrhythmiaType.SUPRAVENTRICULAR
                            2 -> ArrhythmiaType.VENTRICULAR
                            else -> return@collect
                        }

                        val event = ArrhythmiaEvent(
                            timestamp   = result.timestampMs,
                            sampleIndex = recordingSampleIndex,
                            type        = eventType,
                            confidence  = result.confidence
                        )

                        // Add to visible events (limit to MAX_VISIBLE_EVENTS)
                        val currentEvents = _visibleArrhythmiaEvents.value.toMutableList()
                        currentEvents.add(event)
                        if (currentEvents.size > MAX_VISIBLE_EVENTS) {
                            currentEvents.removeAt(0)
                        }
                        _visibleArrhythmiaEvents.value = currentEvents

                        // Add to recording buffer if recording
                        if (_isRecording.value) {
                            recordingEventsBuffer.add(event)
                        }

                        Timber.i(
                            "Arrhythmia detected: ${result.className} " +
                                    "(${(result.confidence * 100).toInt()}%) at ${result.timestampMs}ms"
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Classification failed for beat #${beatWindow.beatNum}")
                }
            }
        }

        // ── Lead-off transitions ──────────────────────────────────────────────────
        // Resets detector on reconnect to prevent the transient voltage spike at
        // reconnection from triggering phantom R-peaks.
        viewModelScope.launch(Dispatchers.Default) {
            bleManager.leadOffState.collect { isOff ->
                if (!isOff) {
                    Timber.i("Leads reconnected — resetting beat detector")
                    resetBeatDetector()
                }
            }
        }

        // ── Connection lifecycle ──────────────────────────────────────────────────
        // StateFlow updates are cheap; this stays on Main via viewModelScope default
        // so _uiState writes are immediately visible to the UI without a post().
        viewModelScope.launch {
            connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        _droppedPacketCount.value = 0
                        if (!_isRecording.value) {
                            _uiState.value = LiveMonitorUiState.Connected(
                                state.device.name ?: "ESP32"
                            )
                        }
                    }
                    is ConnectionState.Disconnected -> {
                        if (_isRecording.value) stopRecording()
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

    // endregion

    // region ECG packet processing

    /**
     * Expands a single [EcgPacket] into [BleManager.ECG_SAMPLES_PER_PACKET] individual
     * [EcgReading]s and feeds them into the visible window, recording buffer, and beat detector.
     *
     * Runs on [Dispatchers.Default]. StateFlow emissions from here are thread-safe.
     * The UI thread only reads the emitted [List] snapshots — it never touches the
     * mutable internal state.
     *
     * When [EcgPacket.droppedBefore] > 0 the beat detector is reset to avoid bridging
     * R-peak detection across a packet gap.
     */
    private fun processIncomingPacket(packet: EcgPacket) {
        if (packet.droppedBefore > 0) {
            _droppedPacketCount.value += packet.droppedBefore
            resetBeatDetector()
            Timber.w("Gap: ${packet.droppedBefore} packet(s) missing before seq=${packet.seqNum}")
        }

        // packet.timestampMs is the ESP32 millis() at the START of the 10-sample batch.
        val newReadings = Array(packet.samples.size) { i ->
            EcgReading(
                timestamp   = packet.timestampMs + (i.toLong() * BleManager.SAMPLE_INTERVAL_MS),
                voltage     = packet.samples[i].toFloat(),
                sampleIndex = recordingSampleIndex++
            )
        }

        // ── Visible window: use a pre-allocated ArrayDeque, never copy the full list ──
        // Previously: _ecgReadings.value.toMutableList() on every packet — a 3 000-element
        // copy 25×/s, creating constant GC pressure. Now we mutate the same deque in-place
        // and only snapshot it for the StateFlow emit.
        for (reading in newReadings) {
            if (readingDeque.size >= VISIBLE_WINDOW_SAMPLES) readingDeque.removeFirst()
            readingDeque.addLast(reading)
        }
        _ecgReadings.value = readingDeque.toList()

        if (_isRecording.value) recordingBuffer.addAll(newReadings)

        for (reading in newReadings) {
            pushToRollingWindow(reading.voltage)
            detectBeat(reading)
        }
    }

    // endregion

    // region Beat detection

    /** Appends one sample to the rolling window, maintaining running min/max in O(1). */
    private fun pushToRollingWindow(value: Float) {
        if (rollingWindow.size >= ROLLING_WINDOW_SAMPLES) {
            val evicted = rollingWindow.removeFirst()
            // If the evicted value was the extremum, recompute from scratch (O(n)).
            // This is rare — only when the global min/max falls off the window edge.
            if (evicted <= rollingMin) rollingMin = if (rollingWindow.isEmpty()) Float.MAX_VALUE else rollingWindow.min()
            if (evicted >= rollingMax) rollingMax = if (rollingWindow.isEmpty()) Float.MIN_VALUE else rollingWindow.max()
        }
        rollingWindow.addLast(value)
        if (value < rollingMin) rollingMin = value
        if (value > rollingMax) rollingMax = value
    }

    /**
     * Adaptive R-peak detector for raw int16 samples.
     *
     * Threshold = windowMin + [BEAT_THRESHOLD_FACTOR] × peak-to-peak amplitude of
     * the last [ROLLING_WINDOW_SAMPLES] samples (1 second at 250 Hz).
     * Self-calibrates to any ADC gain or electrode placement.
     *
     * Used only for the local heart-rate display. Classification comes from the
     * ESP32 inference characteristic via [processInferenceResult].
     */
    private fun detectBeat(reading: EcgReading) {
        if (rollingWindow.size < ROLLING_WINDOW_SAMPLES / 2) return

        val amplitude = rollingMax - rollingMin
        if (amplitude < MIN_SIGNAL_AMPLITUDE) return

        val threshold = rollingMin + (amplitude * BEAT_THRESHOLD_FACTOR)
        if (reading.voltage <= threshold) return

        val timeSinceLastBeat = if (recentBeatTimestamps.isNotEmpty())
            reading.timestamp - recentBeatTimestamps.last()
        else
            MIN_BEAT_INTERVAL_MS + 1L

        if (timeSinceLastBeat >= MIN_BEAT_INTERVAL_MS) {
            recordBeat(reading.timestamp)
        }
    }

    /**
     * Registers a confirmed R-peak and applies EMA smoothing to the BPM display.
     *
     * EMA: new = α × instant + (1 – α) × previous
     * With α = [HR_EMA_ALPHA] = 0.25 the display settles in ~3–4 beats.
     */
    private fun recordBeat(timestampMs: Long) {
        if (recentBeatTimestamps.size >= MAX_BEAT_HISTORY) recentBeatTimestamps.removeFirst()
        recentBeatTimestamps.addLast(timestampMs)

        if (recentBeatTimestamps.size >= 2) {
            val rrIntervals = (1 until recentBeatTimestamps.size).map { i ->
                recentBeatTimestamps[i] - recentBeatTimestamps[i - 1]
            }
            val instantHr = (60_000.0 / rrIntervals.average()).toFloat().coerceIn(30f, 250f)

            smoothedHeartRate = if (smoothedHeartRate == 0f) {
                instantHr
            } else {
                HR_EMA_ALPHA * instantHr + (1f - HR_EMA_ALPHA) * smoothedHeartRate
            }

            val displayHr = smoothedHeartRate.toInt().coerceIn(30, 250)
            _heartRate.value = displayHr

            if (_isRecording.value) recordingHrSamples.add(displayHr)
        }
    }

    /** Clears all detector state — called on lead reconnect and packet gaps. */
    private fun resetBeatDetector() {
        rollingWindow.clear()
        rollingMin         = Float.MAX_VALUE
        rollingMax         = Float.MIN_VALUE
        recentBeatTimestamps.clear()
        smoothedHeartRate  = 0f
    }

    // endregion

    // region Recording controls

    fun startRecording() {
        if (_isRecording.value) return
        if (connectionState.value !is ConnectionState.Connected) {
            _uiState.value = LiveMonitorUiState.Error("Not connected to device")
            return
        }

        recordingBuffer.clear()
        recordingEventsBuffer.clear()
        recordingHrSamples.clear()
        recordingSampleIndex = 0
        recordingStartTime   = System.currentTimeMillis()
        _isRecording.value   = true
        _ecgReadings.value   = emptyList()
        readingDeque.clear()

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
        val session  = _currentSession.value?.copy(
            endTime      = endTime,
            totalSamples = recordingBuffer.size,
            duration     = duration
        )

        if (session != null && recordingBuffer.isNotEmpty()) {
            val hrSnapshot = recordingHrSamples.toList()
            val heartRateStats = if (hrSnapshot.isNotEmpty()) {
                EcgRepository.HeartRateStats(
                    avg = hrSnapshot.average().toInt().coerceIn(30, 250),
                    min = hrSnapshot.min().coerceIn(30, 250),
                    max = hrSnapshot.max().coerceIn(30, 250)
                )
            } else null

            viewModelScope.launch {
                try {
                    val sessionId = repository.saveSession(
                        readings        = recordingBuffer.toList(),
                        events          = recordingEventsBuffer.toList(),
                        sessionMetadata = session,
                        sessionName     = null,
                        heartRateStats  = heartRateStats
                    )
                    syncManager.syncSessionAsync(sessionId)
                    Timber.i(
                        "Saved session $sessionId — ${recordingBuffer.size} samples, " +
                                "${duration}s, HR avg=${heartRateStats?.avg}"
                    )
                    _uiState.value = LiveMonitorUiState.RecordingSaved(sessionId)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to save recording")
                    _uiState.value = LiveMonitorUiState.Error("Failed to save: ${e.message}")
                } finally {
                    recordingBuffer.clear()
                    recordingEventsBuffer.clear()
                    recordingHrSamples.clear()
                }
            }
        }

        _currentSession.value = null
    }

    // endregion

    // region Utility

    /**
     * Call after navigating to SessionDetailScreen in response to a
     * [LiveMonitorUiState.RecordingSaved] event. Resets [uiState] to
     * [LiveMonitorUiState.Connected] or [LiveMonitorUiState.Idle] so that pressing
     * back from the detail screen does not re-trigger navigation.
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
     * Wipes all live display state without touching an active recording.
     * Resets the beat-detector rolling state so the threshold recalibrates cleanly.
     */
    fun clearData() {
        readingDeque.clear()
        _ecgReadings.value             = emptyList()
        _visibleArrhythmiaEvents.value = emptyList()
        _lastClassificationResult.value = null
        _classificationResults.value   = null
        _heartRate.value               = 0
        _droppedPacketCount.value      = 0
        resetBeatDetector()
        recordingSampleIndex = 0
        if (!_isRecording.value) _currentSession.value = null
    }

    override fun onCleared() {
        super.onCleared()
        classifier.close()
        if (_isRecording.value) stopRecording()
    }

    // endregion

    companion object {
        /** Sampling rate the ESP32 ADC runs at. Must match firmware and the trained model. */
        private const val TARGET_FS = 250

        /**
         * Samples kept in the live display buffer.
         * 3 000 samples ÷ 250 Hz = 12 seconds of waveform on screen.
         */
        private const val VISIBLE_WINDOW_SAMPLES = 3_000

        /** Maximum inference events shown in the event overlay. */
        private const val MAX_VISIBLE_EVENTS = 50

        /**
         * Rolling window for adaptive beat detection: 1 second of samples.
         * Threshold = windowMin + BEAT_THRESHOLD_FACTOR × peak-to-peak amplitude.
         */
        private const val ROLLING_WINDOW_SAMPLES = 250

        /**
         * R-peak threshold factor. A sample must be in the top (1 – 0.70) = 30% of the
         * signal range. R-peaks typically occupy the top 15–20%, T-waves ~40–50%.
         */
        private const val BEAT_THRESHOLD_FACTOR = 0.70f

        /** Minimum interval between beats (~200 bpm max). Rejects double-triggers. */
        private const val MIN_BEAT_INTERVAL_MS  = 300L

        /** Number of recent RR intervals averaged for heart-rate calculation. */
        private const val MAX_BEAT_HISTORY      = 10

        /**
         * EMA alpha for heart-rate display smoothing.
         * 0.25 → ~3–4 beats to fully adapt, suppressing single noisy RR intervals
         * without making the display feel laggy.
         */
        private const val HR_EMA_ALPHA = 0.25f

        /**
         * Minimum peak-to-peak amplitude (raw int16 units) below which the signal
         * is treated as flat noise. Corresponds to ~1–2 mV depending on AD8232 gain.
         */
        private const val MIN_SIGNAL_AMPLITUDE = 50f
    }

    /**
     * Pre-allocated deque for the visible ECG window.
     * Declared as an instance property (not inside a function) so it is never
     * reallocated. The companion object constant is accessible at declaration site.
     */
    private val readingDeque = ArrayDeque<EcgReading>(VISIBLE_WINDOW_SAMPLES)
}

// region UI state

sealed class LiveMonitorUiState {
    object Idle                                       : LiveMonitorUiState()
    object Recording                                  : LiveMonitorUiState()
    data class Connected(val deviceName: String)      : LiveMonitorUiState()
    data class RecordingSaved(val sessionId: String)  : LiveMonitorUiState()
    data class Error(val message: String)             : LiveMonitorUiState()
}

// endregion