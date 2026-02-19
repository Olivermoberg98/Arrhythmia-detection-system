package com.ecgmonitor.app.data.firebase

import com.ecgmonitor.app.data.consent.ConsentManager
import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.database.entities.AuditAction
import timber.log.Timber

class SyncManager(
    private val database: EcgDatabase,
    private val firebaseRepository: FirebaseRepository,
    private val consentManager: ConsentManager
) {

    suspend fun initialise() {
        firebaseRepository.ensureSignedIn()
    }

    /**
     * Uploads session metadata and events to Firestore after a local save.
     * Raw readings are intentionally excluded — Firestore only, no Cloud Storage needed.
     */
    suspend fun syncSession(sessionId: String) {
        if (!firebaseRepository.isAuthenticated) {
            Timber.d("Sync skipped — not authenticated")
            return
        }
        if (!consentManager.hasValidConsent()) {
            Timber.d("Sync skipped — no valid consent")
            return
        }
        try {
            val session = database.sessionDao().getSession(sessionId) ?: return
            val events = database.eventDao().getEventsForSession(sessionId)

            firebaseRepository.uploadSession(session, events)

            consentManager.log(
                action = AuditAction.SESSION_UPLOADED_CLOUD,
                sessionId = sessionId
            )
        } catch (e: Exception) {
            Timber.e(e, "Sync failed for session $sessionId")
        }
    }

    suspend fun deleteEverywhere() {
        consentManager.deleteAllUserData()
        try {
            if (firebaseRepository.isAuthenticated) {
                firebaseRepository.deleteAllCloudData()
            }
        } catch (e: Exception) {
            Timber.e(e, "Cloud deletion failed — local data already deleted")
        }
    }
}