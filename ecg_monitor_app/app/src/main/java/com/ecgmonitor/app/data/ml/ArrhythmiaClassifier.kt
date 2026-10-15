// NEW FILE: data/ml/ArrhythmiaClassifier.kt
package com.ecgmonitor.app.data.ml

import android.content.Context
import com.ecgmonitor.app.data.ble.BeatWindow
import com.ecgmonitor.app.data.models.ClassificationResult
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ArrhythmiaClassifier(context: Context) {

    private val interpreter: Interpreter
    private val metadata: ModelMetadata

    /** Exposed so the monitoring layer can score inputs against the training scaler. */
    val modelMetadata: ModelMetadata get() = metadata

    init {
        // Load TFLite model
        val modelBuffer = FileUtil.loadMappedFile(context, "arrhythmia_model.tflite")
        interpreter = Interpreter(modelBuffer, Interpreter.Options().apply {
            setNumThreads(4)
        })

        // Load metadata
        metadata = loadMetadata(context)

        Timber.i("ArrhythmiaClassifier initialized")
        Timber.i("  Model inputs: ${interpreter.inputTensorCount}")
        Timber.i("  Model outputs: ${interpreter.outputTensorCount}")
        Timber.i("  Beat length: ${metadata.beatLength}")
        Timber.i("  Classes: ${metadata.classes.joinToString { it.name }}")
    }

    private fun loadMetadata(context: Context): ModelMetadata {
        val jsonString = context.assets.open("model_metadata.json")
            .bufferedReader().use { it.readText() }
        val json = JSONObject(jsonString)

        val classes = json.getJSONArray("classes").let { arr ->
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ClassInfo(
                    id = obj.getInt("id"),
                    name = obj.getString("name"),
                    color = obj.getString("color")
                )
            }
        }

        val ecgScalerJson = json.getJSONObject("ecg_scaler")
        val rrScalerJson = json.getJSONObject("rr_scaler")

        return ModelMetadata(
            version = json.optString("version", UNKNOWN_VERSION),
            beatLength = json.getInt("beat_length"),
            nRrFeatures = json.getInt("n_rr_features"),
            classes = classes,
            thresholds = json.getJSONArray("thresholds").let { arr ->
                FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
            },
            ecgMean = ecgScalerJson.getDouble("mean").toFloat(),
            ecgStd = ecgScalerJson.getDouble("std").toFloat(),
            rrMean = rrScalerJson.getJSONArray("mean").let { arr ->
                FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
            },
            rrStd = rrScalerJson.getJSONArray("std").let { arr ->
                FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
            }
        )
    }

    /**
     * Classify a beat window from ESP32.
     */
    fun classify(beatWindow: BeatWindow): ClassificationResult {
        val startTime = System.currentTimeMillis()

        // ADC counts -> mV before normalising: the scaler was fit on MIT-BIH millivolts.
        val ecgFloat = FloatArray(beatWindow.ecgSamples.size) { i ->
            beatWindow.ecgSamples[i].toFloat() * ADC_COUNTS_TO_MV
        }

        // Normalize inputs
        val ecgNorm = normalizeECG(ecgFloat)
        val rrNorm = normalizeRR(beatWindow.rrFeatures)

        // Prepare tensors
        val ecgInput = Array(1) { ecgNorm }
        val rrInput = Array(1) { rrNorm }
        val output = Array(1) { FloatArray(metadata.classes.size) }

        // Run inference
        val inputs = arrayOf(ecgInput, rrInput)
        val outputs = mapOf(0 to output)
        interpreter.runForMultipleInputsOutputs(inputs, outputs)

        val scores = output[0]
        val classId = decide(scores, metadata.thresholds)
        val confidence = scores[classId]
        val isValid = confidence >= metadata.thresholds[classId]

        val inferenceTime = System.currentTimeMillis() - startTime

        return ClassificationResult(
            beatNum = beatWindow.beatNum,
            timestampMs = beatWindow.timestampMs,
            classId = classId,
            className = metadata.classes[classId].name,
            confidence = confidence,
            isValid = isValid,
            scores = scores,
            inferenceTimeMs = inferenceTime
        ).also {
            Timber.d(
                "Beat #${it.beatNum} classified: ${it.className} " +
                        "(${(confidence * 100).toInt()}%) in ${inferenceTime}ms"
            )
        }
    }

    private fun normalizeECG(ecg: FloatArray): FloatArray {
        return FloatArray(ecg.size) { i ->
            (ecg[i] - metadata.ecgMean) / (metadata.ecgStd + 1e-6f)
        }
    }

    private fun normalizeRR(rr: FloatArray): FloatArray {
        return FloatArray(rr.size) { i ->
            (rr[i] - metadata.rrMean[i]) / (metadata.rrStd[i] + 1e-6f)
        }
    }

    fun close() {
        interpreter.close()
    }

    companion object {
        private const val UNKNOWN_VERSION = "unknown"

        /**
         * The decision rule the thresholds were tuned for in `train_model.py`:
         * class = argmax(scores / thresholds). Plain argmax ignores them and
         * under-calls the minority classes.
         */
        fun decide(scores: FloatArray, thresholds: FloatArray): Int =
            scores.indices.maxByOrNull { scores[it] / thresholds[it] } ?: 0

        /**
         * Electrode-referred millivolts per raw ADC count, converting the firmware's
         * beat window into the units `model_metadata.json`'s scaler was fit on.
         *
         * The training scaler comes from `wfdb.rdrecord(...).p_signal`, which is in
         * millivolts, while the firmware ships `raw - ADC_MIDPOINT` in ADC counts
         * (`ECGSensor.cpp`). Feeding counts straight into a millivolt scaler put inputs
         * ~180 training sigmas out of range and saturated the network, which is what
         * `SignalQualityAnalyzer.distanceFromTraining` exists to surface.
         *
         * Value = (ADC full scale / resolution) / AD8232 gain
         *       = (3100 mV / 4096) / 1100 = 6.88e-4 mV per count
         *
         * 3100 mV is the ESP32-S3 12-bit ADC at 11 dB attenuation; 1100 is the SparkFun
         * AD8232 breakout's total gain (internal instrumentation amp 100 x output stage
         * 11). Gain is board-specific, so treat this as calibrated rather than exact:
         * a clean recording should report a session `driftScore` near
         * [EXPECTED_DRIFT_SCORE]. Persistently far off means this constant is wrong for
         * the hardware, not that the signal is bad.
         */
        const val ADC_COUNTS_TO_MV = (3100f / 4096f) / 1100f

        /**
         * Mean |z| a beat drawn from the training distribution produces, measured over
         * the 41,873 training beats (mean 0.55, 5th-95th percentile 0.23-1.04). The
         * reference point for reading `driftScore`.
         */
        const val EXPECTED_DRIFT_SCORE = 0.55f
    }
}

// Data classes
data class ModelMetadata(
    /** Model release from `model_metadata.json`, so health metrics attribute to a version. */
    val version: String,
    val beatLength: Int,
    val nRrFeatures: Int,
    val classes: List<ClassInfo>,
    val thresholds: FloatArray,
    val ecgMean: Float,
    val ecgStd: Float,
    val rrMean: FloatArray,
    val rrStd: FloatArray
)

data class ClassInfo(
    val id: Int,
    val name: String,
    val color: String
)