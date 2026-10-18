package com.ecgmonitor.app.data.consent

import android.content.Context
import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.database.entities.AuditAction
import com.ecgmonitor.app.data.database.entities.AuditLogEntity
import com.ecgmonitor.app.data.database.entities.ConsentEntity
import kotlinx.coroutines.flow.Flow
import timber.log.Timber

/**
 * Single point of access for all consent and audit operations.
 *
 * Regulatory basis:
 * - GDPR Article 7 & 9: conditions for explicit consent on health data
 * - MDR Annex I Section 17: data protection requirements
 * - ISO 14971: audit trail supports incident investigation
 *
 * [CONSENT_VERSION] must be incremented whenever the consent text changes.
 * Users who consented under an older version will be re-prompted.
 */
class ConsentManager(context: Context) {

    companion object {
        const val CONSENT_VERSION = "3.0"
    }

    private val db = EcgDatabase.getDatabase(context)
    private val consentDao = db.consentDao()
    private val auditDao = db.auditLogDao()

    /** Returns true if the user has an active, current-version consent on record. */
    suspend fun hasValidConsent(): Boolean {
        val latest = consentDao.getLatest() ?: return false
        return latest.granted && latest.consentVersion == CONSENT_VERSION
    }

    /** Records a consent grant or withdrawal and logs the corresponding audit event. */
    suspend fun recordConsent(granted: Boolean) {
        consentDao.insert(
            ConsentEntity(
                timestamp = System.currentTimeMillis(),
                consentVersion = CONSENT_VERSION,
                granted = granted
            )
        )
        log(
            action = if (granted) AuditAction.CONSENT_GRANTED else AuditAction.CONSENT_WITHDRAWN,
            detail = "Consent version: $CONSENT_VERSION"
        )
        Timber.i("Consent recorded: granted=$granted version=$CONSENT_VERSION")
    }

    /** Appends a single entry to the audit log. */
    suspend fun log(
        action: AuditAction,
        sessionId: String? = null,
        detail: String? = null
    ) {
        auditDao.insert(
            AuditLogEntity(
                timestamp = System.currentTimeMillis(),
                action = action,
                sessionId = sessionId,
                detail = detail
            )
        )
    }

    /**
     * Deletes all user-generated data: sessions, readings, events, and the audit log.
     * Consent records are retained as a legal record of what was agreed to.
     * Fulfils GDPR Article 17 (Right to Erasure).
     */
    suspend fun deleteAllUserData() {
        db.sessionDao().deleteAll()
        db.readingDao().deleteAll()
        db.eventDao().deleteAll()
        auditDao.deleteAll()
        log(action = AuditAction.ALL_DATA_DELETED, detail = "User-initiated full data deletion")
        Timber.i("All user data deleted")
    }

    @Suppress("unused")
    fun getAuditLog(): Flow<List<AuditLogEntity>> = auditDao.getAll()

    @Suppress("unused")
    fun getConsentHistory(): Flow<List<ConsentEntity>> = consentDao.getAll()
}