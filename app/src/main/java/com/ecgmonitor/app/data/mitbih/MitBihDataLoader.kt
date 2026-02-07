package com.ecgmonitor.app.data.mitbih

import android.content.Context
import com.ecgmonitor.app.data.models.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads and parses MIT-BIH Arrhythmia Database records
 *
 * Expected format in assets/mitbih/:
 * - mitbih_100.json: ECG data for record 100
 * - mitbih_203.json: ECG data for record 203
 *
 * JSON structure:
 * {
 *   "record": "100",
 *   "samplingRate": 360,
 *   "samples": [0.145, 0.150, ...],
 *   "annotations": [
 *     {"sample": 77, "type": "N"},
 *     {"sample": 370, "type": "V"}
 *   ]
 * }
 */
class MitBihDataLoader(private val context: Context) {

    companion object {
        private const val ASSETS_FOLDER = "mitbih"
        const val SAMPLING_RATE = 360 // Hz - MIT-BIH standard

        // Available MIT-BIH records (we'll include 2 for testing)
        val AVAILABLE_RECORDS = listOf("100", "203")
    }

    /**
     * Load a specific MIT-BIH record
     */
    suspend fun loadRecord(recordNumber: String): Result<EcgRecordData> = withContext(Dispatchers.IO) {
        try {
            val fileName = "mitbih_$recordNumber.json"
            val inputStream = context.assets.open("$ASSETS_FOLDER/$fileName")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.use { it.readText() }

            val gson = Gson()
            val recordData = gson.fromJson(jsonString, MitBihRecordJson::class.java)

            // Convert to our domain models
            val ecgReadings = recordData.samples.mapIndexed { index, voltage ->
                EcgReading(
                    timestamp = (index * 1000L / SAMPLING_RATE), // Convert to milliseconds
                    voltage = voltage.toFloat(),
                    sampleIndex = index
                )
            }

            val arrhythmiaEvents = recordData.annotations.map { annotation ->
                ArrhythmiaEvent(
                    timestamp = (annotation.sample * 1000L / SAMPLING_RATE),
                    type = ArrhythmiaType.fromMitBihAnnotation(annotation.type),
                    confidence = 1.0f, // MIT-BIH annotations are ground truth
                    sampleIndex = annotation.sample,
                    description = "MIT-BIH ${annotation.type}"
                )
            }

            val session = EcgSession(
                id = "mitbih_$recordNumber",
                patientId = "MIT-BIH-$recordNumber",
                startTime = System.currentTimeMillis(),
                endTime = System.currentTimeMillis() + (ecgReadings.size * 1000L / SAMPLING_RATE),
                samplingRate = SAMPLING_RATE,
                source = DataSource.MIT_BIH_DATABASE,
                totalSamples = ecgReadings.size,
                duration = ecgReadings.size.toFloat() / SAMPLING_RATE
            )

            Timber.d("Loaded MIT-BIH record $recordNumber: ${ecgReadings.size} samples, ${arrhythmiaEvents.size} annotations")

            Result.success(
                EcgRecordData(
                    session = session,
                    readings = ecgReadings,
                    arrhythmiaEvents = arrhythmiaEvents,
                    metadata = MitBihRecord(
                        recordNumber = recordNumber,
                        patientInfo = recordData.patientInfo ?: "MIT-BIH Record $recordNumber",
                        annotations = recordData.annotations.map {
                            MitBihAnnotation(it.sample, it.type, it.type)
                        }
                    )
                )
            )

        } catch (e: Exception) {
            Timber.e(e, "Failed to load MIT-BIH record $recordNumber")
            Result.failure(e)
        }
    }

    /**
     * Get list of available records
     */
    fun getAvailableRecords(): List<String> = AVAILABLE_RECORDS
}

// JSON parsing models
private data class MitBihRecordJson(
    val record: String,
    val samplingRate: Int,
    val samples: List<Double>,
    val annotations: List<AnnotationJson>,
    val patientInfo: String? = null
)

private data class AnnotationJson(
    val sample: Int,
    val type: String
)

/**
 * Complete loaded ECG record with all associated data
 */
data class EcgRecordData(
    val session: EcgSession,
    val readings: List<EcgReading>,
    val arrhythmiaEvents: List<ArrhythmiaEvent>,
    val metadata: MitBihRecord
)
