package com.ecgmonitor.app.data.repository

import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.database.entities.*
import com.ecgmonitor.app.data.mitbih.MitBihDataLoader
import com.ecgmonitor.app.data.models.*
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

class EcgRepository(
    private val database: EcgDatabase,
    private val mitBihLoader: MitBihDataLoader
) {

    private val sessionDao = database.sessionDao()
    private val readingDao = database.readingDao()
    private val eventDao = database.eventDao()

    /**
     * Get all sessions as Flow
     */
    fun getAllSessions(): Flow<List<EcgSessionEntity>> {
        return sessionDao.getAllSessions()
    }

    /**
     * Get specific session with all data
     */
    suspend fun getSessionWithData(sessionId: String): SessionWithData? {
        val session = sessionDao.getSession(sessionId) ?: return null
        val readings = readingDao.getReadingsForSession(sessionId)
        val events = eventDao.getEventsForSession(sessionId)

        return SessionWithData(session, readings, events)
    }

    /**
     * Pre-computed heart-rate statistics passed in from the ViewModel.
     * Avoids the broken fallback that tried to compute RR intervals from
     * ESP32 inference events (which are rhythm classifications, not beat markers).
     */
    data class HeartRateStats(val avg: Int, val min: Int, val max: Int)

    /**
     * Save a complete ECG session
     */
    suspend fun saveSession(
        readings: List<EcgReading>,
        events: List<ArrhythmiaEvent>,
        sessionMetadata: EcgSession,
        sessionName: String? = null,
        heartRateStats: HeartRateStats? = null
    ): String {
        val sessionId = sessionMetadata.id

        // Calculate statistics — use ViewModel-supplied HR stats when available
        // (those come from real beat detection).  Fall back to event-based calculation
        // only for MIT-BIH sessions where individual beat events ARE available.
        val stats = calculateSessionStatistics(events, sessionMetadata, heartRateStats)

        // Create session entity
        val sessionEntity = EcgSessionEntity(
            id = sessionId,
            name = sessionName ?: generateSessionName(sessionMetadata),
            startTime = sessionMetadata.startTime,
            endTime = sessionMetadata.endTime ?: System.currentTimeMillis(),
            samplingRate = sessionMetadata.samplingRate,
            source = sessionMetadata.source,
            totalSamples = readings.size,
            durationSeconds = sessionMetadata.duration,
            averageHeartRate = stats.averageHeartRate,
            minHeartRate = stats.minHeartRate,
            maxHeartRate = stats.maxHeartRate,
            totalBeats = stats.totalBeats,
            normalBeats = stats.normalBeats,
            pvcCount = stats.pvcCount,
            afibCount = stats.afibCount,
            otherArrhythmiaCount = stats.otherArrhythmiaCount,
            healthStatus = determineHealthStatus(stats)
        )

        // Convert readings to entities
        val readingEntities = readings.map { reading ->
            EcgReadingEntity(
                sessionId = sessionId,
                sampleIndex = reading.sampleIndex,
                timestamp = reading.timestamp,
                voltage = reading.voltage
            )
        }

        // Convert events to entities
        val eventEntities = events.map { event ->
            ArrhythmiaEventEntity(
                sessionId = sessionId,
                sampleIndex = event.sampleIndex,
                timestamp = event.timestamp,
                type = event.type.name,
                confidence = event.confidence,
                description = event.description
            )
        }

        // Save to database
        sessionDao.insertSession(sessionEntity)
        readingDao.insertReadings(readingEntities)
        eventDao.insertEvents(eventEntities)

        Timber.i("Saved session $sessionId: ${readings.size} readings, ${events.size} events")

        return sessionId
    }

    /**
     * Delete a session and all associated data
     */
    suspend fun deleteSession(sessionId: String) {
        sessionDao.deleteSessionById(sessionId)
        readingDao.deleteReadingsForSession(sessionId)
        eventDao.deleteEventsForSession(sessionId)
        Timber.i("Deleted session $sessionId")
    }

    /**
     * Update session name
     */
    suspend fun updateSessionName(sessionId: String, newName: String) {
        val session = sessionDao.getSession(sessionId) ?: return
        sessionDao.updateSession(session.copy(name = newName))
    }

    /**
     * Update session notes
     */
    suspend fun updateSessionNotes(sessionId: String, notes: String) {
        val session = sessionDao.getSession(sessionId) ?: return
        sessionDao.updateSession(session.copy(notes = notes))
    }

    /**
     * Load MIT-BIH data and save to database
     */
    suspend fun loadAndSaveMitBihRecord(recordNumber: String): Result<String> {
        return try {
            val result = mitBihLoader.loadRecord(recordNumber)
            result.fold(
                onSuccess = { recordData ->
                    val sessionId = saveSession(
                        readings = recordData.readings,
                        events = recordData.arrhythmiaEvents,
                        sessionMetadata = recordData.session,
                        sessionName = "MIT-BIH Record $recordNumber"
                    )
                    Result.success(sessionId)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if MIT-BIH records are already in database
     */
    suspend fun areMitBihRecordsLoaded(): Boolean {
        val sessionCount = sessionDao.getSessionCount()
        return sessionCount >= 2 // Assuming we have 2 MIT-BIH records
    }

    /**
     * Initialize database with MIT-BIH records if empty
     */
    suspend fun initializeMitBihRecords() {
        if (!areMitBihRecordsLoaded()) {
            Timber.i("Loading MIT-BIH records into database...")
            MitBihDataLoader.AVAILABLE_RECORDS.forEach { recordNumber ->
                loadAndSaveMitBihRecord(recordNumber)
            }
        }
    }

    // Helper functions

    private fun generateSessionName(metadata: EcgSession): String {
        val dateFormat = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
        val date = dateFormat.format(java.util.Date(metadata.startTime))
        return when (metadata.source) {
            DataSource.MIT_BIH_DATABASE -> "MIT-BIH - $date"
            DataSource.ESP32_DEVICE -> "Recording - $date"
            DataSource.SIMULATION -> "Simulation - $date"
        }
    }

    private data class SessionStatistics(
        val averageHeartRate: Int,
        val minHeartRate: Int,
        val maxHeartRate: Int,
        val totalBeats: Int,
        val normalBeats: Int,
        val pvcCount: Int,
        val afibCount: Int,
        val otherArrhythmiaCount: Int
    )

    private fun calculateSessionStatistics(
        events: List<ArrhythmiaEvent>,
        metadata: EcgSession,
        providedHrStats: HeartRateStats? = null
    ): SessionStatistics {
        val totalBeats = events.size

        // Prefer ViewModel-supplied HR stats (accurate beat detection).
        // Fall back to RR-from-events only when not provided (e.g. MIT-BIH import).
        val avgHR: Int
        val minHR: Int
        val maxHR: Int

        if (providedHrStats != null) {
            avgHR = providedHrStats.avg
            minHR = providedHrStats.min
            maxHR = providedHrStats.max
        } else {
            // Legacy path: compute from event timestamps (only valid when events are
            // individual beat annotations, not rhythm-classification intervals).
            val rrIntervals = mutableListOf<Long>()
            for (i in 1 until events.size) {
                rrIntervals.add(events[i].timestamp - events[i - 1].timestamp)
            }
            avgHR = if (rrIntervals.isNotEmpty()) {
                (60000.0 / rrIntervals.average()).toInt().coerceIn(40, 200)
            } else {
                70  // neutral default — still better than always showing 75
            }
            val hrValues = rrIntervals.map { (60000.0 / it).toInt() }
            minHR = hrValues.minOrNull()?.coerceIn(40, 200) ?: avgHR
            maxHR = hrValues.maxOrNull()?.coerceIn(40, 200) ?: avgHR
        }

        // Count arrhythmia types
        val normalBeats = events.count { it.type == ArrhythmiaType.NORMAL }
        val pvcCount = events.count { it.type == ArrhythmiaType.VENTRICULAR }
        val afibCount = events.count { it.type == ArrhythmiaType.SUPRAVENTRICULAR }
        val otherCount = totalBeats - normalBeats - pvcCount - afibCount

        return SessionStatistics(
            averageHeartRate = avgHR,
            minHeartRate = minHR,
            maxHeartRate = maxHR,
            totalBeats = totalBeats,
            normalBeats = normalBeats,
            pvcCount = pvcCount,
            afibCount = afibCount,
            otherArrhythmiaCount = otherCount
        )
    }

    private fun determineHealthStatus(stats: SessionStatistics): HealthStatus {
        val pvcPercentage = if (stats.totalBeats > 0) {
            (stats.pvcCount.toFloat() / stats.totalBeats) * 100
        } else 0f

        return when {
            stats.afibCount > 0 -> HealthStatus.CONCERNING
            pvcPercentage > 20 -> HealthStatus.CONCERNING
            pvcPercentage > 10 -> HealthStatus.ATTENTION
            stats.averageHeartRate > 100 || stats.averageHeartRate < 50 -> HealthStatus.ATTENTION
            else -> HealthStatus.NORMAL
        }
    }
}