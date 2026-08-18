package com.ecgmonitor.app.data.firebase

import com.ecgmonitor.app.data.consent.ConsentManager
import com.ecgmonitor.app.data.database.EcgDatabase
import com.ecgmonitor.app.data.database.entities.AuditAction
import com.ecgmonitor.app.data.database.entities.EcgSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

class SyncManager(
    private val database: EcgDatabase,
    private val firebaseRepository: FirebaseRepository,
    private val consentManager: ConsentManager
) {

    /** Outlives any ViewModel so navigating away cannot cancel an in-flight upload. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Signs in and flushes anything that never reached the cloud in an earlier run. */
    suspend fun initialise() {
        firebaseRepository.ensureSignedIn()
        syncPending()
    }

    /** Retries every locally saved session that has no successful upload recorded. */
    suspend fun syncPending() {
        val pending = database.sessionDao().getPendingUploads()
        if (pending.isEmpty()) return
        Timber.i("Retrying ${pending.size} pending session upload(s)")
        pending.forEach { upload(it) }
    }

    /**
     * Uploads a session without blocking the caller — used from the recording save path
     * so a slow or offline upload cannot stall the UI.
     */
    fun syncSessionAsync(sessionId: String) {
        scope.launch { syncSession(sessionId) }
    }

    /**
     * Uploads session metadata and events to Firestore after a local save.
     * Raw readings are intentionally excluded — Firestore only, no Cloud Storage needed.
     */
    suspend fun syncSession(sessionId: String) {
        val session = database.sessionDao().getSession(sessionId)
        if (session == null) {
            Timber.w("Sync skipped — session $sessionId not found")
            return
        }
        upload(session)
    }

    /**
     * Marks the session uploaded only on a server-acknowledged write, so a failure here
     * leaves it in the pending set for [syncPending] to retry at the next launch.
     */
    private suspend fun upload(session: EcgSessionEntity) {
        if (!consentManager.hasValidConsent()) {
            Timber.d("Sync skipped — no valid consent")
            return
        }
        if (!firebaseRepository.ensureSignedIn()) {
            Timber.w("Sync deferred — sign-in unavailable, will retry later")
            return
        }
        try {
            val events = database.eventDao().getEventsForSession(session.id)
            if (firebaseRepository.uploadSession(session, events)) {
                // Re-read so a rename made during the upload is not overwritten.
                database.sessionDao().getSession(session.id)?.let {
                    database.sessionDao().updateSession(
                        it.copy(uploadedAt = System.currentTimeMillis())
                    )
                }
                consentManager.log(
                    action = AuditAction.SESSION_UPLOADED_CLOUD,
                    sessionId = session.id
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Sync failed for session ${session.id}")
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