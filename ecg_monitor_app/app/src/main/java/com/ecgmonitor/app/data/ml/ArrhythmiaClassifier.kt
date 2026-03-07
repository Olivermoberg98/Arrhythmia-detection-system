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

        // Convert int16 ECG to float32
        val ecgFloat = FloatArray(250) { i ->
            if (i < 128) beatWindow.ecgSamples[i].toFloat()
            else 0f  // Zero-pad to 250
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
        val classId = scores.indices.maxByOrNull { scores[it] } ?: 0
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
}

// Data classes
data class ModelMetadata(
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