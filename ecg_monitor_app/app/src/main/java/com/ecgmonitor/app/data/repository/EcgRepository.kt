package com.ecgmonitor.app.data.repository

import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.database.entities.ArrhythmiaEventEntity
import com.ecgmonitor.app.data.database.entities.EcgReadingEntity
import com.ecgmonitor.app.data.database.entities.EcgSessionEntity
import com.ecgmonitor.app.data.database.entities.HealthStatus
import com.ecgmonitor.app.data.database.entities.SessionWithData
import com.ecgmonitor.app.data.mitbih.MitBihDataLoader
import com.ecgmonitor.app.data.models.ArrhythmiaEvent
import com.ecgmonitor.app.data.models.ArrhythmiaType
import com.ecgmonitor.app.data.models.DataSource
import com.ecgmonitor.app.data.models.EcgReading
import com.ecgmonitor.app.data.models.EcgSession
import com.ecgmonitor.app.data.ml.MonitoringSummary
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

class EcgRepository(
    database: EcgDatabase,
    private val mitBihLoader: MitBihDataLoader
) {

    private val sessionDao = database.sessionDao()
    private val readingDao = database.readingDao()
    private val eventDao = database.eventDao()

    /**
     * Heart-rate statistics supplied by the ViewModel (derived from real beat detection),
     * avoiding the incorrect fallback of computing RR intervals from ESP32 inference
     * events, which are rhythm classifications rather than individual beat markers.
     */
    data class HeartRateStats(val avg: Int, val min: Int, val max: Int)

    /**
     * Derived per-session measurements supplied by the ViewModel: heart-rate statistics
     * from beat detection, and classifier-health metrics from the monitoring layer.
     * Both are absent for MIT-BIH imports, which are replayed rather than measured.
     */
    data class SessionMetrics(
        val heartRate: HeartRateStats? = null,
        val monitoring: MonitoringSummary? = null
    )

    fun getAllSessions(): Flow<List<EcgSessionEntity>> = sessionDao.getAllSessions()

    suspend fun getSessionWithData(sessionId: String): SessionWithData? {
        val session = sessionDao.getSession(sessionId) ?: return null
        val readings = readingDao.getReadingsForSession(sessionId)
        val events = eventDao.getEventsForSession(sessionId)
        return SessionWithData(session, readings, events)
    }

    suspend fun saveSession(
        readings: List<EcgReading>,
        events: List<ArrhythmiaEvent>,
        sessionMetadata: EcgSession,
        sessionName: String? = null,
        metrics: SessionMetrics = SessionMetrics()
    ): String {
        val sessionId = sessionMetadata.id
        val monitoring = metrics.monitoring
        val stats = calculateSessionStatistics(events, metrics.heartRate, monitoring)

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
            supraventricularCount = stats.supraventricularCount,
            otherArrhythmiaCount = stats.otherArrhythmiaCount,
            healthStatus = determineHealthStatus(stats),
            modelVersion = monitoring?.modelVersion,
            meanConfidence = monitoring?.meanConfidence,
            lowConfidenceRate = monitoring?.lowConfidenceRate,
            meanEntropy = monitoring?.meanEntropy,
            poorSignalRate = monitoring?.poorSignalRate,
            suppressedEvents = monitoring?.suppressedEvents,
            driftScore = monitoring?.driftScore,
            classificationsPerMin = monitoring?.classificationsPerMin,
            rateFlag = monitoring?.rateFlag?.name,
            meanInferenceMs = monitoring?.meanInferenceMs
        )

        val readingEntities = readings.map { reading ->
            EcgReadingEntity(
                sessionId = sessionId,
                sampleIndex = reading.sampleIndex,
                timestamp = reading.timestamp,
                voltage = reading.voltage
            )
        }

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

        sessionDao.insertSession(sessionEntity)
        readingDao.insertReadings(readingEntities)
        eventDao.insertEvents(eventEntities)

        Timber.i("Saved session $sessionId: ${readings.size} readings, ${events.size} events")
        return sessionId
    }

    suspend fun deleteSession(sessionId: String) {
        sessionDao.deleteSessionById(sessionId)
        readingDao.deleteReadingsForSession(sessionId)
        eventDao.deleteEventsForSession(sessionId)
        Timber.i("Deleted session $sessionId")
    }

    suspend fun updateSessionName(sessionId: String, newName: String) {
        val session = sessionDao.getSession(sessionId) ?: return
        sessionDao.updateSession(session.copy(name = newName))
    }

    suspend fun updateSessionNotes(sessionId: String, notes: String) {
        val session = sessionDao.getSession(sessionId) ?: return
        sessionDao.updateSession(session.copy(notes = notes))
    }

    suspend fun loadAndSaveMitBihRecord(recordNumber: String): Result<String> {
        return try {
            mitBihLoader.loadRecord(recordNumber).fold(
                onSuccess = { recordData ->
                    val sessionId = saveSession(
                        readings = recordData.readings,
                        events = recordData.arrhythmiaEvents,
                        sessionMetadata = recordData.session,
                        sessionName = "MIT-BIH Record $recordNumber"
                    )
                    Result.success(sessionId)
                },
                onFailure = { error -> Result.failure(error) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun initializeMitBihRecords() {
        if (sessionDao.getSessionCount() < MitBihDataLoader.AVAILABLE_RECORDS.size) {
            Timber.i("Loading MIT-BIH records into database...")
            MitBihDataLoader.AVAILABLE_RECORDS.forEach { loadAndSaveMitBihRecord(it) }
        }
    }

    // region Private helpers

    private fun generateSessionName(metadata: EcgSession): String {
        val formatter = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
        val date = formatter.format(java.util.Date(metadata.startTime))
        return when (metadata.source) {
            DataSource.MIT_BIH_DATABASE -> "MIT-BIH - $date"
            DataSource.ESP32_DEVICE     -> "Recording - $date"
            DataSource.SIMULATION       -> "Simulation - $date"
        }
    }

    private data class SessionStatistics(
        val averageHeartRate: Int,
        val minHeartRate: Int,
        val maxHeartRate: Int,
        val totalBeats: Int,
        val normalBeats: Int,
        val pvcCount: Int,
        val supraventricularCount: Int,
        val otherArrhythmiaCount: Int,
        /** Beats classified but withheld by the quality gate, so left unattributed. */
        val unattributedBeats: Int
    )

    private fun calculateSessionStatistics(
        events: List<ArrhythmiaEvent>,
        providedHrStats: HeartRateStats? = null,
        monitoring: MonitoringSummary? = null
    ): SessionStatistics {
        // `events` only ever holds abnormal beats — the live path returns early for
        // normal ones — so it cannot stand in for the beat count. The monitor is the
        // only source that saw every classified beat. MIT-BIH imports have no monitor,
        // and there each event *is* a beat annotation, so events.size is correct.
        val totalBeats = monitoring?.totalClassified ?: events.size

        // Prefer ViewModel-supplied HR stats (accurate beat detection).
        // Fall back to RR-from-events only for MIT-BIH imports where events
        // represent individual beat annotations, not rhythm-classification intervals.
        val (avgHR, minHR, maxHR) = if (providedHrStats != null) {
            Triple(providedHrStats.avg, providedHrStats.min, providedHrStats.max)
        } else {
            val rrIntervals = (1 until events.size).map { i ->
                events[i].timestamp - events[i - 1].timestamp
            }
            val avg = if (rrIntervals.isNotEmpty()) {
                (60000.0 / rrIntervals.average()).toInt().coerceIn(40, 200)
            } else {
                70
            }
            val hrValues = rrIntervals.map { (60000.0 / it).toInt() }
            Triple(
                avg,
                hrValues.minOrNull()?.coerceIn(40, 200) ?: avg,
                hrValues.maxOrNull()?.coerceIn(40, 200) ?: avg
            )
        }

        val pvcCount = events.count { it.type == ArrhythmiaType.VENTRICULAR }
        val supraventricularCount = events.count { it.type == ArrhythmiaType.SUPRAVENTRICULAR }
        // The model is 3-class N/S/V, so there is no "other" bucket to fill.
        val other = 0

        // Suppressed beats were classified abnormal but failed the quality gate, so
        // they have no trustworthy label and must not be counted as normal.
        val unattributed = monitoring?.suppressedEvents ?: 0
        val normalBeats = if (monitoring != null) {
            (totalBeats - pvcCount - supraventricularCount - unattributed).coerceAtLeast(0)
        } else {
            events.count { it.type == ArrhythmiaType.NORMAL }
        }

        return SessionStatistics(
            averageHeartRate = avgHR,
            minHeartRate = minHR,
            maxHeartRate = maxHR,
            totalBeats = totalBeats,
            normalBeats = normalBeats,
            pvcCount = pvcCount,
            supraventricularCount = supraventricularCount,
            otherArrhythmiaCount = other,
            unattributedBeats = unattributed
        )
    }

    private fun determineHealthStatus(stats: SessionStatistics): HealthStatus {
        val pvcPercentage = if (stats.totalBeats > 0) {
            (stats.pvcCount.toFloat() / stats.totalBeats) * 100
        } else 0f

        val svebPercentage = if (stats.totalBeats > 0) {
            (stats.supraventricularCount.toFloat() / stats.totalBeats) * 100
        } else 0f

        // Was "any afibCount > 0 -> CONCERNING", which made sense for atrial
        // fibrillation but not for the class S ectopics this actually counts —
        // occasional SVEBs are common in healthy rhythm. Graded like PVCs instead.
        return when {
            pvcPercentage > 20 || svebPercentage > 20                    -> HealthStatus.CONCERNING
            pvcPercentage > 10 || svebPercentage > 10                    -> HealthStatus.ATTENTION
            stats.averageHeartRate > 100 || stats.averageHeartRate < 50 -> HealthStatus.ATTENTION
            else                                                         -> HealthStatus.NORMAL
        }
    }

    // endregion
}