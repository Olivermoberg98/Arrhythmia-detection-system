package com.ecgmonitor.app.data.models

/**
 * A single ECG data point.
 *
 * @param timestamp Milliseconds since recording start.
 * @param voltage ECG voltage in millivolts (mV).
 * @param sampleIndex Sequential sample number for ordering.
 */
data class EcgReading(
    val timestamp: Long,
    val voltage: Float,
    val sampleIndex: Int = 0
)

/** A complete ECG recording session. */
data class EcgSession(
    val id: String,
    val patientId: String? = null,
    val startTime: Long,
    val endTime: Long? = null,
    val samplingRate: Int,
    val source: DataSource,
    val totalSamples: Int = 0,
    val duration: Float = 0f
)

enum class DataSource {
    MIT_BIH_DATABASE,
    ESP32_DEVICE,
    SIMULATION
}

/**
 * An arrhythmia event detected by the ESP32 model.
 *
 * [valid] is false when the model's confidence fell below the per-class
 * threshold on-device. Events are still displayed but visually de-emphasised.
 */
data class ArrhythmiaEvent(
    val timestamp: Long,
    val type: ArrhythmiaType,
    val confidence: Float,
    val sampleIndex: Int,
    val valid: Boolean = true,
    val description: String = ""
)

/**
 * The three output classes matching the ESP32 model (MLCfg::Classification).
 *
 * class_id mapping — must stay in sync with firmware config.h:
 * - 0 → NORMAL
 * - 1 → SUPRAVENTRICULAR
 * - 2 → VENTRICULAR
 */
enum class ArrhythmiaType(
    val classId: Int,
    val displayName: String,
    val color: Long
) {
    NORMAL(0, "Normal", 0xFF2ED573),
    SUPRAVENTRICULAR(1, "Supraventricular", 0xFFFFA502),
    VENTRICULAR(2, "Ventricular", 0xFFFF4757);

    companion object {
        /**
         * Maps the class_id byte from a BLE InferencePacket to an [ArrhythmiaType].
         * Falls back to [NORMAL] on any unrecognised id to handle firmware/app
         * version mismatches gracefully.
         */
        @Suppress("unused")
        fun fromClassId(classId: Int): ArrhythmiaType =
            entries.firstOrNull { it.classId == classId } ?: NORMAL

        /**
         * Maps raw MIT-BIH annotation symbols to the 3-class scheme used during
         * training (mirrors AAMI_CLASSES in preprocess.py).
         */
        @Suppress("unused")
        fun fromMitBihAnnotation(annotation: String): ArrhythmiaType =
            when (annotation) {
                "N", "L", "R", "e", "j" -> NORMAL
                "A", "a", "J", "S"      -> SUPRAVENTRICULAR
                "V", "E", "F"           -> VENTRICULAR
                else                    -> NORMAL
            }
    }
}

/** MIT-BIH database record metadata. */
@Suppress("unused")
data class MitBihRecord(
    val recordNumber: String,
    val patientInfo: String,
    val annotations: List<MitBihAnnotation>
)

data class MitBihAnnotation(
    val sampleIndex: Int,
    val type: String,
    val description: String
)