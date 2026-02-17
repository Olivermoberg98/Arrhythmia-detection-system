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
    val samplingRate: Int, // Hz
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
 * Arrhythmia event marker from BLE InferencePacket.
 *
 * Wire protocol (InferencePacket byte 5 = class_id):
 *   0 = Normal
 *   1 = Supraventricular (SVEB / APB)
 *   2 = Ventricular (VEB / PVC)
 *
 * valid = false when the model's confidence was below the per-class
 * threshold on the ESP32. Still displayed but visually de-emphasised.
 */
data class ArrhythmiaEvent(
    val timestamp: Long,
    val type: ArrhythmiaType,
    val confidence: Float,  // 0.0 to 1.0 — softmax score of winning class
    val sampleIndex: Int,
    val valid: Boolean = true,  // false = below confidence threshold on device
    val description: String = ""
)

/**
 * Three output classes matching the ESP32 model (MLCfg::Classification).
 *
 * class_id → ArrhythmiaType mapping must stay in sync with config.h:
 *   0 = NORMAL
 *   1 = SUPRAVENTRICULAR
 *   2 = VENTRICULAR
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
         * Map the class_id byte from the BLE InferencePacket directly to a type.
         * Returns NORMAL for any unrecognised id so the app never crashes on a
         * firmware/app version mismatch.
         */
        fun fromClassId(classId: Int): ArrhythmiaType =
            entries.firstOrNull { it.classId == classId } ?: NORMAL

        /**
         * Map raw MIT-BIH annotation symbols to the same 3-class scheme
         * used during training (mirrors AAMI_CLASSES in preprocess.py).
         */
        fun fromMitBihAnnotation(annotation: String): ArrhythmiaType =
            when (annotation) {
                // Normal / non-ectopic
                "N", "L", "R", "e", "j" -> NORMAL
                // Supraventricular ectopic
                "A", "a", "J", "S"      -> SUPRAVENTRICULAR
                // Ventricular ectopic (F merged into V per training convention)
                "V", "E", "F"           -> VENTRICULAR
                else                    -> NORMAL
            }
    }
}

/**
 * MIT-BIH Database record metadata — unchanged.
 */
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