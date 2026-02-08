package com.ecgmonitor.app.data.mitbih

import android.content.Context
import com.ecgmonitor.app.data.models.*
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

/**
 * Loader for MIT-BIH Arrhythmia Database records
 * Loads ECG data from JSON files in assets/mitbih/
 */
class MitBihDataLoader(private val context: Context) {

    private val gson = Gson()

    companion object {
        val AVAILABLE_RECORDS = listOf("100", "203")
    }

    /**
     * Load a specific MIT-BIH record
     */
    suspend fun loadRecord(recordNumber: String): Result<EcgRecordData> = withContext(Dispatchers.IO) {
        try {
            val fileName = "mitbih/mitbih_$recordNumber.json"
            val jsonString = context.assets.open(fileName).bufferedReader().use { it.readText() }

            val mitBihRecord = gson.fromJson(jsonString, MitBihRecordJson::class.java)

            // Convert to domain models
            val sessionId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis()

            // Create EcgReading objects
            val readings = mitBihRecord.samples.mapIndexed { index, voltage ->
                EcgReading(
                    timestamp = (index * 1000L) / mitBihRecord.samplingRate,
                    voltage = voltage,
                    sampleIndex = index
                )
            }

            // Create ArrhythmiaEvent objects
            val events = mitBihRecord.annotations.map { annotation ->
                ArrhythmiaEvent(
                    timestamp = (annotation.sample * 1000L) / mitBihRecord.samplingRate,
                    type = mapAnnotationType(annotation.type),
                    confidence = 1.0f, // MIT-BIH annotations are ground truth
                    sampleIndex = annotation.sample,
                    description = "MIT-BIH annotation: ${annotation.type}"
                )
            }

            // Create session metadata
            val duration = readings.size.toFloat() / mitBihRecord.samplingRate
            val session = EcgSession(
                id = sessionId,
                startTime = startTime,
                endTime = startTime + (duration * 1000).toLong(),
                samplingRate = mitBihRecord.samplingRate,
                source = DataSource.MIT_BIH_DATABASE,
                totalSamples = readings.size,
                duration = duration
            )

            val recordData = EcgRecordData(
                session = session,
                readings = readings,
                arrhythmiaEvents = events,
                patientInfo = mitBihRecord.patientInfo
            )

            Timber.i("Loaded MIT-BIH record $recordNumber: ${readings.size} samples, ${events.size} events")
            Result.success(recordData)

        } catch (e: Exception) {
            Timber.e(e, "Failed to load MIT-BIH record $recordNumber")
            Result.failure(e)
        }
    }

    /**
     * Get list of available records
     */
    fun getAvailableRecords(): List<String> {
        return AVAILABLE_RECORDS
    }

    /**
     * Map MIT-BIH annotation types to our ArrhythmiaType enum
     */
    private fun mapAnnotationType(type: String): ArrhythmiaType {
        return when (type.uppercase()) {
            "N" -> ArrhythmiaType.NORMAL
            "V", "PVC" -> ArrhythmiaType.PREMATURE_VENTRICULAR
            "A", "AFIB" -> ArrhythmiaType.ATRIAL_FIBRILLATION
            "VTACH" -> ArrhythmiaType.VENTRICULAR_TACHYCARDIA
            else -> ArrhythmiaType.UNKNOWN
        }
    }
}

/**
 * JSON structure for MIT-BIH records
 */
private data class MitBihRecordJson(
    @SerializedName("record") val record: String,
    @SerializedName("samplingRate") val samplingRate: Int,
    @SerializedName("patientInfo") val patientInfo: String,
    @SerializedName("samples") val samples: List<Float>,
    @SerializedName("annotations") val annotations: List<AnnotationJson>
)

private data class AnnotationJson(
    @SerializedName("sample") val sample: Int,
    @SerializedName("type") val type: String
)

/**
 * Complete ECG record with all data
 */
data class EcgRecordData(
    val session: EcgSession,
    val readings: List<EcgReading>,
    val arrhythmiaEvents: List<ArrhythmiaEvent>,
    val patientInfo: String
)