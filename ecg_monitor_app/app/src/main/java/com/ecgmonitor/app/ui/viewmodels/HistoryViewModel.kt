package com.ecgmonitor.app.ui.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.database.entities.EcgSessionEntity
import com.ecgmonitor.app.data.mitbih.MitBihDataLoader
import com.ecgmonitor.app.data.repository.EcgRepository
import com.ecgmonitor.app.ui.screens.SortOrder
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val database = EcgDatabase.getDatabase(application)
    private val mitBihLoader = MitBihDataLoader(application)
    private val repository = EcgRepository(database, mitBihLoader)

    private val _sortOrder = MutableStateFlow(SortOrder.MOST_RECENT)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    // All sessions from database, sorted based on current sort order
    val sessions: StateFlow<List<EcgSessionEntity>> = repository.getAllSessions()
        .combine(_sortOrder) { sessions, order ->
            when (order) {
                SortOrder.MOST_RECENT -> sessions.sortedByDescending { it.startTime }
                SortOrder.OLDEST -> sessions.sortedBy { it.startTime }
                SortOrder.MOST_ARRHYTHMIAS -> sessions.sortedByDescending {
                    it.pvcCount + it.afibCount + it.otherArrhythmiaCount
                }
                SortOrder.LONGEST -> sessions.sortedByDescending { it.durationSeconds }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        // Initialize MIT-BIH records on first launch
        viewModelScope.launch {
            repository.initializeMitBihRecords()
            Timber.i("History screen initialized")
        }
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        Timber.d("Sort order changed to: $order")
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            Timber.i("Deleted session: $sessionId")
        }
    }
}