package com.ecgmonitor.app.data.models

/**
 * Represents a single ECG data point
 * @param timestamp Time in milliseconds since recording start
 * @param voltage ECG voltage in millivolts (mV)
 * @param sampleIndex Sequential sample number for ordering
 */
data class EcgReading(
    val timestamp: Long,
    val voltage: Float,
    val sampleIndex: Int = 0
)

/**
 * Complete ECG recording session
 */
data class EcgSession(
    val id: String,
    val patientId: String? = null,
    val startTime: Long,
    val endTime: Long? = null,
    val samplingRate: Int, // Hz (e.g., 360 for MIT-BIH, 300 for your ESP32)
    val source: DataSource,
    val totalSamples: Int = 0,
    val duration: Float = 0f // seconds
)

enum class DataSource {
    MIT_BIH_DATABASE,
    ESP32_DEVICE,
    SIMULATION
}

/**
 * Arrhythmia event marker
 */
data class ArrhythmiaEvent(
    val timestamp: Long,
    val type: ArrhythmiaType,
    val confidence: Float, // 0.0 to 1.0
    val sampleIndex: Int,
    val description: String = ""
)

enum class ArrhythmiaType(val displayName: String, val color: Long) {
    NORMAL("Normal Beat", 0xFF2ED573),
    PREMATURE_VENTRICULAR("PVC", 0xFFFF6B6B),
    ATRIAL_FIBRILLATION("AFib", 0xFFFF4757),
    VENTRICULAR_TACHYCARDIA("V-Tach", 0xFFEE5A6F),
    BRADYCARDIA("Bradycardia", 0xFFFFA502),
    TACHYCARDIA("Tachycardia", 0xFFFD79A8),
    UNKNOWN("Unknown", 0xFF95A5A6);

    companion object {
        fun fromMitBihAnnotation(annotation: String): ArrhythmiaType {
            return when (annotation) {
                "N" -> NORMAL
                "V" -> PREMATURE_VENTRICULAR
                "A" -> ATRIAL_FIBRILLATION
                "F" -> ATRIAL_FIBRILLATION
                else -> UNKNOWN
            }
        }
    }
}

/**
 * MIT-BIH Database record metadata
 */
data class MitBihRecord(
    val recordNumber: String, // e.g., "100", "203"
    val patientInfo: String,
    val annotations: List<MitBihAnnotation>
)

data class MitBihAnnotation(
    val sampleIndex: Int,
    val type: String, // N, V, A, F, etc.
    val description: String
)
