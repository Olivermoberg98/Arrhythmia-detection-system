package com.ecgmonitor.app.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.ecgmonitor.app.data.database.converters.Converters
import com.ecgmonitor.app.data.models.DataSource

/**
 * Room entity for ECG recording sessions
 */
@Entity(tableName = "ecg_sessions")
data class EcgSessionEntity(
    @PrimaryKey
    val id: String,
    val name: String? = null, // User-provided name or auto-generated
    val startTime: Long,
    val endTime: Long,
    val samplingRate: Int,
    val source: DataSource,
    val totalSamples: Int,
    val durationSeconds: Float,

    // Statistics
    val averageHeartRate: Int,
    val minHeartRate: Int,
    val maxHeartRate: Int,
    val totalBeats: Int,
    val normalBeats: Int,
    val pvcCount: Int,
    val afibCount: Int,
    val otherArrhythmiaCount: Int,

    // Health status
    val healthStatus: HealthStatus,
    val notes: String? = null
)

enum class HealthStatus {
    NORMAL,      // All within healthy ranges
    ATTENTION,   // Some elevated values
    CONCERNING   // Medical attention recommended
}

/**
 * Room entity for individual ECG readings
 * Stored separately for efficiency
 */
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

/**
 * Room entity for arrhythmia events
 */
@Entity(
    tableName = "arrhythmia_events",
    primaryKeys = ["sessionId", "sampleIndex"]
)
data class ArrhythmiaEventEntity(
    val sessionId: String,
    val sampleIndex: Int,
    val timestamp: Long,
    val type: String, // ArrhythmiaType as string
    val confidence: Float,
    val description: String
)

/**
 * Session with complete data (for queries)
 */
data class SessionWithData(
    val session: EcgSessionEntity,
    val readings: List<EcgReadingEntity>,
    val events: List<ArrhythmiaEventEntity>
)