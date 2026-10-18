package com.ecgmonitor.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecgmonitor.app.EcgMonitorApp
import com.ecgmonitor.app.data.database.entities.AuditAction
import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.database.entities.SessionWithData
import com.ecgmonitor.app.data.mitbih.MitBihDataLoader
import com.ecgmonitor.app.data.models.ArrhythmiaEvent
import com.ecgmonitor.app.data.models.ArrhythmiaType
import com.ecgmonitor.app.data.models.EcgReading
import com.ecgmonitor.app.data.repository.EcgRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

class SessionDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = EcgRepository(
        database = EcgDatabase.getDatabase(application),
        mitBihLoader = MitBihDataLoader(application)
    )

    private val _sessionData = MutableStateFlow<SessionWithData?>(null)
    val sessionData: StateFlow<SessionWithData?> = _sessionData.asStateFlow()

    private val _currentReadings = MutableStateFlow<List<EcgReading>>(emptyList())
    val currentReadings: StateFlow<List<EcgReading>> = _currentReadings.asStateFlow()

    private val _visibleEvents = MutableStateFlow<List<ArrhythmiaEvent>>(emptyList())
    val visibleEvents: StateFlow<List<ArrhythmiaEvent>> = _visibleEvents.asStateFlow()

    private val _allEvents = MutableStateFlow<List<ArrhythmiaEvent>>(emptyList())
    val allEvents: StateFlow<List<ArrhythmiaEvent>> = _allEvents.asStateFlow()

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Stopped)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _eventFilter = MutableStateFlow(EventFilter.ALL)
    val eventFilter: StateFlow<EventFilter> = _eventFilter.asStateFlow()

    private var isPlaying = false
    private var currentPlaybackIndex = 0
    private var allReadings: List<EcgReading> = emptyList()

    companion object {
        private const val PLAYBACK_WINDOW_SIZE = 2_000
        private const val PLAYBACK_BATCH_SIZE  = 10
    }

    /** Audit-logs a successful PDF/CSV export (FR-AUD-01). */
    fun logExport(sessionId: String, action: AuditAction) {
        val consentManager = getApplication<EcgMonitorApp>().consentManager
        viewModelScope.launch { consentManager.log(action = action, sessionId = sessionId) }
    }

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val data = repository.getSessionWithData(sessionId)
            if (data != null) {
                _sessionData.value = data

                allReadings = data.readings.map { entity ->
                    EcgReading(
                        timestamp   = entity.timestamp,
                        voltage     = entity.voltage,
                        sampleIndex = entity.sampleIndex
                    )
                }

                val events = data.events.map { entity ->
                    ArrhythmiaEvent(
                        timestamp   = entity.timestamp,
                        type        = ArrhythmiaType.valueOf(entity.type),
                        confidence  = entity.confidence,
                        sampleIndex = entity.sampleIndex,
                        description = entity.description
                    )
                }

                _allEvents.value = events
                showInitialWindow()

                Timber.i("Loaded session $sessionId: ${allReadings.size} readings, ${events.size} events")
            } else {
                Timber.e("Session $sessionId not found")
            }
        }
    }

    private fun showInitialWindow() {
        val initial = allReadings.take(PLAYBACK_WINDOW_SIZE)
        _currentReadings.value = initial
        updateVisibleEvents(initial)
        currentPlaybackIndex = 0
        _playbackProgress.value = 0f
    }

    fun startPlayback() {
        if (isPlaying) return
        isPlaying = true
        _playbackState.value = PlaybackState.Playing

        viewModelScope.launch {
            val samplingRate = _sessionData.value?.session?.samplingRate ?: 250
            val delayMs = (1000L / (samplingRate * _playbackSpeed.value)).toLong()

            while (isPlaying && currentPlaybackIndex < allReadings.size) {
                val endIndex = minOf(currentPlaybackIndex + PLAYBACK_BATCH_SIZE, allReadings.size)
                currentPlaybackIndex = endIndex

                // Right-edge window: the current position is always the rightmost sample,
                // so the window is consistently full throughout playback.
                val windowStart = maxOf(0, endIndex - PLAYBACK_WINDOW_SIZE)
                val visibleWindow = allReadings.subList(windowStart, endIndex)

                _currentReadings.value = visibleWindow
                updateVisibleEvents(visibleWindow)
                _playbackProgress.value = endIndex.toFloat() / allReadings.size

                delay(delayMs * PLAYBACK_BATCH_SIZE)
            }

            if (currentPlaybackIndex >= allReadings.size) {
                val windowStart = maxOf(0, allReadings.size - PLAYBACK_WINDOW_SIZE)
                _currentReadings.value = allReadings.subList(windowStart, allReadings.size)
                _playbackState.value = PlaybackState.Completed
                isPlaying = false
            }
        }
    }

    fun pausePlayback() {
        isPlaying = false
        _playbackState.value = PlaybackState.Paused
    }

    fun stopPlayback() {
        isPlaying = false
        _playbackState.value = PlaybackState.Stopped
        showInitialWindow()
    }

    fun seekToProgress(progress: Float) {
        val targetIndex = (progress * allReadings.size).toInt().coerceIn(0, allReadings.size - 1)
        currentPlaybackIndex = targetIndex
        _playbackProgress.value = progress

        val windowStart = maxOf(0, targetIndex - PLAYBACK_WINDOW_SIZE)
        val readings = allReadings.subList(windowStart, targetIndex)
        _currentReadings.value = readings
        updateVisibleEvents(readings)
    }

    fun jumpToEvent(event: ArrhythmiaEvent) {
        seekToProgress(event.sampleIndex.toFloat() / allReadings.size)
        Timber.d("Jumped to event at sample ${event.sampleIndex}")
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
    }

    fun setEventFilter(filter: EventFilter) {
        _eventFilter.value = filter
    }

    fun getFilteredEvents(): List<ArrhythmiaEvent> =
        when (_eventFilter.value) {
            EventFilter.ALL           -> _allEvents.value
            EventFilter.PVC_ONLY      -> _allEvents.value.filter { it.type == ArrhythmiaType.VENTRICULAR }
            EventFilter.AFIB_ONLY     -> _allEvents.value.filter { it.type == ArrhythmiaType.SUPRAVENTRICULAR }
            EventFilter.ABNORMAL_ONLY -> _allEvents.value.filter { it.type != ArrhythmiaType.NORMAL }
        }

    private fun updateVisibleEvents(currentWindow: List<EcgReading>) {
        if (currentWindow.isEmpty()) {
            _visibleEvents.value = emptyList()
            return
        }
        val minTs = currentWindow.first().timestamp
        val maxTs = currentWindow.last().timestamp
        _visibleEvents.value = _allEvents.value.filter { it.timestamp in minTs..maxTs }
    }

    @Suppress("unused")
    fun updateSessionName(sessionId: String, newName: String) {
        viewModelScope.launch { repository.updateSessionName(sessionId, newName) }
    }

    @Suppress("unused")
    fun updateSessionNotes(sessionId: String, notes: String) {
        viewModelScope.launch { repository.updateSessionNotes(sessionId, notes) }
    }
}

sealed class PlaybackState {
    object Stopped   : PlaybackState()
    object Playing   : PlaybackState()
    object Paused    : PlaybackState()
    object Completed : PlaybackState()
}

enum class EventFilter {
    ALL,
    PVC_ONLY,
    AFIB_ONLY,
    ABNORMAL_ONLY
}