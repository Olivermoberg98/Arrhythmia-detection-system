package com.ecgmonitor.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private val database = EcgDatabase.getDatabase(application)
    private val mitBihLoader = MitBihDataLoader(application)
    private val repository = EcgRepository(database, mitBihLoader)

    // Session data
    private val _sessionData = MutableStateFlow<SessionWithData?>(null)
    val sessionData: StateFlow<SessionWithData?> = _sessionData.asStateFlow()

    // Current ECG readings being displayed
    private val _currentReadings = MutableStateFlow<List<EcgReading>>(emptyList())
    val currentReadings: StateFlow<List<EcgReading>> = _currentReadings.asStateFlow()

    // Visible arrhythmia events
    private val _visibleEvents = MutableStateFlow<List<ArrhythmiaEvent>>(emptyList())
    val visibleEvents: StateFlow<List<ArrhythmiaEvent>> = _visibleEvents.asStateFlow()

    // All arrhythmia events
    private val _allEvents = MutableStateFlow<List<ArrhythmiaEvent>>(emptyList())
    val allEvents: StateFlow<List<ArrhythmiaEvent>> = _allEvents.asStateFlow()

    // Playback state
    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Stopped)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f) // 0.0 to 1.0
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f) // 0.5x, 1x, 2x
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    // Filter state
    private val _eventFilter = MutableStateFlow(EventFilter.ALL)
    val eventFilter: StateFlow<EventFilter> = _eventFilter.asStateFlow()

    // Playback control
    private var isPlaying = false
    private var currentPlaybackIndex = 0
    private var allReadings: List<EcgReading> = emptyList()

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val data = repository.getSessionWithData(sessionId)
            if (data != null) {
                _sessionData.value = data

                // Convert entities to domain models
                allReadings = data.readings.map { entity ->
                    EcgReading(
                        timestamp = entity.timestamp,
                        voltage = entity.voltage,
                        sampleIndex = entity.sampleIndex
                    )
                }

                val events = data.events.map { entity ->
                    ArrhythmiaEvent(
                        timestamp = entity.timestamp,
                        type = ArrhythmiaType.valueOf(entity.type),
                        confidence = entity.confidence,
                        sampleIndex = entity.sampleIndex,
                        description = entity.description
                    )
                }

                _allEvents.value = events

                // Show initial window
                showInitialWindow()

                Timber.i("Loaded session $sessionId: ${allReadings.size} readings, ${events.size} events")
            } else {
                Timber.e("Session $sessionId not found")
            }
        }
    }

    private fun showInitialWindow() {
        val windowSize = 3000 // Show first 3000 samples (~8 seconds)
        val initialReadings = allReadings.take(windowSize)
        _currentReadings.value = initialReadings
        updateVisibleEvents(initialReadings)
        currentPlaybackIndex = 0
        _playbackProgress.value = 0f
    }

    fun startPlayback() {
        if (isPlaying) return

        isPlaying = true
        _playbackState.value = PlaybackState.Playing

        viewModelScope.launch {
            val samplingRate = _sessionData.value?.session?.samplingRate ?: 360
            val delayMs = (1000L / (samplingRate * _playbackSpeed.value)).toLong()
            val batchSize = 10

            while (isPlaying && currentPlaybackIndex < allReadings.size) {
                val endIndex = minOf(currentPlaybackIndex + batchSize, allReadings.size)
                val batch = allReadings.subList(currentPlaybackIndex, endIndex)

                // Update visible window
                val currentWindow = _currentReadings.value.toMutableList()
                currentWindow.addAll(batch)

                // Keep window size limited
                if (currentWindow.size > 3000) {
                    val toRemove = currentWindow.size - 3000
                    repeat(toRemove) { currentWindow.removeAt(0) }
                }

                _currentReadings.value = currentWindow
                updateVisibleEvents(currentWindow)

                currentPlaybackIndex = endIndex
                _playbackProgress.value = currentPlaybackIndex.toFloat() / allReadings.size

                delay(delayMs * batchSize)
            }

            if (currentPlaybackIndex >= allReadings.size) {
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

        // Show window around target index
        val windowSize = 3000
        val startIndex = maxOf(0, targetIndex - windowSize / 2)
        val endIndex = minOf(allReadings.size, startIndex + windowSize)
        val readings = allReadings.subList(startIndex, endIndex)

        _currentReadings.value = readings
        updateVisibleEvents(readings)
    }

    fun jumpToEvent(event: ArrhythmiaEvent) {
        val targetIndex = event.sampleIndex
        seekToProgress(targetIndex.toFloat() / allReadings.size)
        Timber.d("Jumped to event at sample ${event.sampleIndex}")
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        Timber.d("Playback speed set to ${speed}x")
    }

    fun setEventFilter(filter: EventFilter) {
        _eventFilter.value = filter
    }

    fun getFilteredEvents(): List<ArrhythmiaEvent> {
        return when (_eventFilter.value) {
            EventFilter.ALL -> _allEvents.value
            EventFilter.PVC_ONLY -> _allEvents.value.filter {
                it.type == ArrhythmiaType.PREMATURE_VENTRICULAR
            }
            EventFilter.AFIB_ONLY -> _allEvents.value.filter {
                it.type == ArrhythmiaType.ATRIAL_FIBRILLATION
            }
            EventFilter.ABNORMAL_ONLY -> _allEvents.value.filter {
                it.type != ArrhythmiaType.NORMAL
            }
        }
    }

    private fun updateVisibleEvents(currentWindow: List<EcgReading>) {
        if (currentWindow.isEmpty()) {
            _visibleEvents.value = emptyList()
            return
        }

        val minTimestamp = currentWindow.first().timestamp
        val maxTimestamp = currentWindow.last().timestamp

        val visible = _allEvents.value.filter { event ->
            event.timestamp in minTimestamp..maxTimestamp
        }

        _visibleEvents.value = visible
    }

    fun updateSessionName(sessionId: String, newName: String) {
        viewModelScope.launch {
            repository.updateSessionName(sessionId, newName)
        }
    }

    fun updateSessionNotes(sessionId: String, notes: String) {
        viewModelScope.launch {
            repository.updateSessionNotes(sessionId, notes)
        }
    }
}

sealed class PlaybackState {
    object Stopped : PlaybackState()
    object Playing : PlaybackState()
    object Paused : PlaybackState()
    object Completed : PlaybackState()
}

enum class EventFilter {
    ALL,
    PVC_ONLY,
    AFIB_ONLY,
    ABNORMAL_ONLY
}