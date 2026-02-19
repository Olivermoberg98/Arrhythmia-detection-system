package com.ecgmonitor.app.data.firebase

import com.ecgmonitor.app.data.database.entities.ArrhythmiaEventEntity
import com.ecgmonitor.app.data.database.entities.EcgSessionEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber

class FirebaseRepository {

    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: Flow<UploadState> = _uploadState.asStateFlow()

    val isAuthenticated: Boolean get() = auth.currentUser != null

    suspend fun ensureSignedIn() {
        if (auth.currentUser != null) return
        try {
            auth.signInAnonymously().await()
            Timber.i("Anonymous sign-in OK. UID: ${auth.currentUser?.uid}")
        } catch (e: Exception) {
            Timber.e(e, "Anonymous sign-in failed")
        }
    }

    /**
     * Uploads session metadata and arrhythmia events to Firestore.
     * Raw waveform readings are intentionally excluded — they remain in the
     * local Room database only, keeping this free-tier compatible.
     */
    suspend fun uploadSession(
        session: EcgSessionEntity,
        events: List<ArrhythmiaEventEntity>
    ) {
        val uid = auth.currentUser?.uid ?: run {
            _uploadState.value = UploadState.NotAuthenticated
            return
        }

        _uploadState.value = UploadState.Uploading
        Timber.i("Uploading session ${session.id}")

        try {
            firestore
                .collection("users").document(uid)
                .collection("sessions").document(session.id)
                .set(mapOf(
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
                ))
                .await()

            // Arrhythmia events written in batches (Firestore limit: 500 per batch)
            if (events.isNotEmpty()) {
                events.chunked(500).forEach { chunk ->
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

            _uploadState.value = UploadState.Success(session.id)
            Timber.i("Session ${session.id} uploaded successfully")

        } catch (e: Exception) {
            Timber.e(e, "Upload failed for session ${session.id}")
            _uploadState.value = UploadState.Error(e.message ?: "Upload failed")
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