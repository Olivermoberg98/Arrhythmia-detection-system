package com.ecgmonitor.app.data.firebase

import com.ecgmonitor.app.data.database.entities.ArrhythmiaEventEntity
import com.ecgmonitor.app.data.database.entities.EcgSessionEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

class FirebaseRepository {

    private companion object {
        /** Firestore hard limit on operations per write batch. */
        const val BATCH_LIMIT = 500

        /**
         * Firestore write Tasks only complete on server ack — offline they never do,
         * so every network call is bounded to avoid suspending the caller forever.
         */
        const val UPLOAD_TIMEOUT_MS = 20_000L
        const val SIGN_IN_TIMEOUT_MS = 15_000L
    }

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: Flow<UploadState> = _uploadState.asStateFlow()

    val isAuthenticated: Boolean get() = auth.currentUser != null

    /**
     * Signs in anonymously if not already signed in. Safe to call before every upload —
     * a failed attempt (no network, provider disabled) is retried on the next call rather
     * than leaving the app permanently unauthenticated.
     *
     * @return true if a signed-in user is available afterwards.
     */
    suspend fun ensureSignedIn(): Boolean {
        if (auth.currentUser != null) return true
        return try {
            val result = withTimeoutOrNull(SIGN_IN_TIMEOUT_MS) { auth.signInAnonymously().await() }
            if (result == null) {
                Timber.w("Anonymous sign-in timed out")
                false
            } else {
                Timber.i("Anonymous sign-in OK. UID: ${result.user?.uid}")
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Anonymous sign-in failed")
            false
        }
    }

    /**
     * Uploads session metadata and arrhythmia events to Firestore.
     * Raw waveform readings are intentionally excluded — they remain in the
     * local Room database only, keeping this free-tier compatible.
     *
     * Writes are keyed by session id, so retrying an upload is idempotent.
     *
     * @return true only if the write was acknowledged by the server.
     */
    suspend fun uploadSession(
        session: EcgSessionEntity,
        events: List<ArrhythmiaEventEntity>
    ): Boolean {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            _uploadState.value = UploadState.NotAuthenticated
            return false
        }

        _uploadState.value = UploadState.Uploading
        Timber.i("Uploading session ${session.id}")

        return try {
            val acknowledged = withTimeoutOrNull(UPLOAD_TIMEOUT_MS) {
                firestore
                    .collection("users").document(uid)
                    .collection("sessions").document(session.id)
                    .set(sessionFields(session))
                    .await()
                uploadEvents(uid, events)
                true
            }
            if (acknowledged == null) {
                // The SDK keeps the write queued locally; the next launch retries it.
                Timber.w("Upload timed out for session ${session.id}")
                _uploadState.value = UploadState.Error("Upload timed out — check connection")
                false
            } else {
                _uploadState.value = UploadState.Success(session.id)
                Timber.i("Session ${session.id} uploaded successfully")
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Upload failed for session ${session.id}")
            _uploadState.value = UploadState.Error(e.message ?: "Upload failed")
            false
        }
    }

    private fun sessionFields(session: EcgSessionEntity): Map<String, Any?> = mapOf(
        "name"                 to session.name,
        "startTime"            to session.startTime,
        "endTime"              to session.endTime,
        "samplingRate"         to session.samplingRate,
        "durationSeconds"      to session.durationSeconds,
        "averageHeartRate"     to session.averageHeartRate,
        "minHeartRate"         to session.minHeartRate,
        "maxHeartRate"         to session.maxHeartRate,
        "totalBeats"           to session.totalBeats,
        "normalBeats"          to session.normalBeats,
        "pvcCount"             to session.pvcCount,
        "afibCount"            to session.afibCount,
        "otherArrhythmiaCount" to session.otherArrhythmiaCount,
        "healthStatus"         to session.healthStatus.name,
        "totalSamples"         to session.totalSamples,
        "uploadedAt"           to System.currentTimeMillis()
    )

    /** Arrhythmia events written in batches (Firestore limit: 500 per batch). */
    private suspend fun uploadEvents(uid: String, events: List<ArrhythmiaEventEntity>) {
        events.chunked(BATCH_LIMIT).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { event ->
                val ref = firestore
                    .collection("users").document(uid)
                    .collection("events")
                    .document("${event.sessionId}_${event.sampleIndex}")
                batch.set(ref, mapOf(
                    "sessionId"   to event.sessionId,
                    "sampleIndex" to event.sampleIndex,
                    "timestamp"   to event.timestamp,
                    "type"        to event.type,
                    "confidence"  to event.confidence,
                    "description" to event.description
                ))
            }
            batch.commit().await()
        }
    }

    suspend fun deleteAllCloudData() {
        val uid = auth.currentUser?.uid ?: return
        try {
            val batch = firestore.batch()

            firestore.collection("users").document(uid)
                .collection("sessions").get().await()
                .documents.forEach { batch.delete(it.reference) }

            firestore.collection("users").document(uid)
                .collection("events").get().await()
                .documents.forEach { batch.delete(it.reference) }

            batch.commit().await()
            firestore.collection("users").document(uid).delete().await()

            Timber.i("All cloud data deleted for UID $uid")
        } catch (e: Exception) {
            Timber.e(e, "Cloud deletion failed")
            throw e
        }
    }

    @Suppress("unused")
    fun resetUploadState() {
        _uploadState.value = UploadState.Idle
    }
}