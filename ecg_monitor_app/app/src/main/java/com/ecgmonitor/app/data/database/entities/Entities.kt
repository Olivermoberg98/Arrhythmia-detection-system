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
    val notes: String? = null,
    /** Timestamp of a successful cloud upload; null means the session is still pending sync. */
    val uploadedAt: Long? = null,

    // region Model health metrics
    //
    // Aggregate classifier-health scalars for this session. All nullable: sessions
    // recorded before schema v4, and MIT-BIH imports, have no monitoring data.

    /** Model release that produced this session's classifications. */
    val modelVersion: String? = null,
    /** Mean top-class confidence across all classified beats. */
    val meanConfidence: Float? = null,
    /** Fraction of beats falling below the model's own per-class threshold. */
    val lowConfidenceRate: Float? = null,
    /** Mean normalised prediction entropy (0 = decisive, 1 = uniform guess). */
    val meanEntropy: Float? = null,
    /** Fraction of beats whose signal quality failed the usability gate. */
    val poorSignalRate: Float? = null,
    /** Abnormal classifications withheld because signal quality was too poor to trust. */
    val suppressedEvents: Int? = null,
    /** Mean absolute z-distance of inputs from the training scaler, in training sigmas. */
    val driftScore: Float? = null,
    /** Mean beats classified per minute across the session. */
    val classificationsPerMin: Float? = null,
    /** Plausibility verdict on the classification rate (see `RateFlag`). */
    val rateFlag: String? = null,
    /** Mean TFLite inference time per beat. */
    val meanInferenceMs: Float? = null
    // endregion
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