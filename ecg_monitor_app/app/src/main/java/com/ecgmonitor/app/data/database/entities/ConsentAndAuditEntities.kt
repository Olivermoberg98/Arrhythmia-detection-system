package com.ecgmonitor.app.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Immutable record of a consent grant or withdrawal.
 * Forms a permanent audit trail — rows are never deleted.
 */
@Entity(tableName = "consent_records")
data class ConsentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val consentVersion: String,
    val granted: Boolean,
    val ipAddress: String? = null
)

/**
 * One entry per significant data access or modification.
 * Supports IEC 62304 Class B/C traceability and ISO 14971 incident review.
 */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val action: AuditAction,
    val sessionId: String? = null,
    val detail: String? = null
)

enum class AuditAction {
    SESSION_RECORDED,
    SESSION_VIEWED,
    SESSION_DELETED,
    SESSION_EXPORTED_PDF,
    SESSION_EXPORTED_CSV,
    SESSION_UPLOADED_CLOUD,
    ALL_DATA_DELETED,
    CONSENT_GRANTED,
    CONSENT_WITHDRAWN,
    APP_OPENED
}