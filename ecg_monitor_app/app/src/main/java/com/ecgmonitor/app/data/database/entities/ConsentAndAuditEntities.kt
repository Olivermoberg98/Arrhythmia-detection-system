package com.ecgmonitor.app.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores each consent event — created when user grants or withdraws consent.
 * Never deleted, forms a permanent audit trail of consent decisions.
 */
@Entity(tableName = "consent_records")
data class ConsentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val consentVersion: String,   // e.g. "1.0" — bump when wording changes
    val granted: Boolean,
    val ipAddress: String? = null // optional, for future cloud use
)

/**
 * Audit log entry — one row per significant data access or modification.
 * Required by IEC 62304 Class B/C and supports ISO 14971 incident traceability.
 */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val action: AuditAction,
    val sessionId: String? = null,  // null for app-level actions
    val detail: String? = null      // freeform context
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