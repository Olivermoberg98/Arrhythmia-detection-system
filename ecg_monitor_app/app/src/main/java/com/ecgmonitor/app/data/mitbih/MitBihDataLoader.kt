package com.ecgmonitor.app.data.mitbih

import android.content.Context
import com.ecgmonitor.app.data.models.ArrhythmiaEvent
import com.ecgmonitor.app.data.models.ArrhythmiaType
import com.ecgmonitor.app.data.models.DataSource
import com.ecgmonitor.app.data.models.EcgReading
import com.ecgmonitor.app.data.models.EcgSession
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

class MitBihDataLoader(private val context: Context) {

    private val gson = Gson()

    companion object {
        val AVAILABLE_RECORDS = listOf("100", "203")
    }

    suspend fun loadRecord(recordNumber: String): Result<EcgRecordData> = withContext(Dispatchers.IO) {
        try {
            val json = context.assets
                .open("mitbih/mitbih_$recordNumber.json")
                .bufferedReader()
                .use { it.readText() }

            val record = gson.fromJson(json, MitBihRecordJson::class.java)
            val sessionId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis()

            val readings = record.samples.mapIndexed { index, voltage ->
                EcgReading(
                    timestamp = (index * 1000L) / record.samplingRate,
                    voltage = voltage,
                    sampleIndex = index
                )
            }

            val events = record.annotations.map { annotation ->
                ArrhythmiaEvent(
                    timestamp = (annotation.sample * 1000L) / record.samplingRate,
                    type = mapAnnotationType(annotation.type),
                    confidence = 1.0f,
                    sampleIndex = annotation.sample,
                    description = "MIT-BIH annotation: ${annotation.type}"
                )
            }

            val duration = readings.size.toFloat() / record.samplingRate
            val session = EcgSession(
                id = sessionId,
                startTime = startTime,
                endTime = startTime + (duration * 1000).toLong(),
                samplingRate = record.samplingRate,
                source = DataSource.MIT_BIH_DATABASE,
                totalSamples = readings.size,
                duration = duration
            )

            Timber.i("Loaded MIT-BIH record $recordNumber: ${readings.size} samples, ${events.size} events")
            Result.success(EcgRecordData(session, readings, events, record.patientInfo))

        } catch (e: Exception) {
            Timber.e(e, "Failed to load MIT-BIH record $recordNumber")
            Result.failure(e)
        }
    }

    @Suppress("unused")
    fun getAvailableRecords(): List<String> = AVAILABLE_RECORDS

    private fun mapAnnotationType(type: String): ArrhythmiaType =
        when (type.uppercase()) {
            "V", "PVC"   -> ArrhythmiaType.VENTRICULAR
            "A", "AFIB"  -> ArrhythmiaType.SUPRAVENTRICULAR
            else         -> ArrhythmiaType.NORMAL
        }
}

private data class MitBihRecordJson(
    @SerializedName("record")      val record: String,
    @SerializedName("samplingRate") val samplingRate: Int,
    @SerializedName("patientInfo") val patientInfo: String,
    @SerializedName("samples")     val samples: List<Float>,
    @SerializedName("annotations") val annotations: List<AnnotationJson>
)

private data class AnnotationJson(
    @SerializedName("sample") val sample: Int,
    @SerializedName("type")   val type: String
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