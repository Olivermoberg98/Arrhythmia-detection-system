package com.ecgmonitor.app.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.ecgmonitor.app.data.models.DataSource

/** Room entity representing a single ECG recording session. */
@Entity(tableName = "ecg_sessions")
data class EcgSessionEntity(
    @PrimaryKey
    val id: String,
    val name: String? = null,
    val startTime: Long,
    val endTime: Long,
    val samplingRate: Int,
    val source: DataSource,
    val totalSamples: Int,
    val durationSeconds: Float,
    val averageHeartRate: Int,
    val minHeartRate: Int,
    val maxHeartRate: Int,
    val totalBeats: Int,
    val normalBeats: Int,
    val pvcCount: Int,
    val afibCount: Int,
    val otherArrhythmiaCount: Int,
    val healthStatus: HealthStatus,
    val notes: String? = null
)

enum class HealthStatus {
    NORMAL,
    ATTENTION,
    CONCERNING
}

/** Room entity for individual ECG voltage readings within a session. */
@Entity(
    tableName = "ecg_readings",
    primaryKeys = ["sessionId", "sampleIndex"]
)
data class EcgReadingEntity(
    val sessionId: String,
    val sampleIndex: Int,
    val timestamp: Long,
    val voltage: Float
)

/** Room entity for arrhythmia events detected during a session. */
@Entity(
    tableName = "arrhythmia_events",
    primaryKeys = ["sessionId", "sampleIndex"]
)
data class ArrhythmiaEventEntity(
    val sessionId: String,
    val sampleIndex: Int,
    val timestamp: Long,
    val type: String,
    val confidence: Float,
    val description: String
)

/** Aggregates a session with its full set of readings and events. */
data class SessionWithData(
    val session: EcgSessionEntity,
    val readings: List<EcgReadingEntity>,
    val events: List<ArrhythmiaEventEntity>
)