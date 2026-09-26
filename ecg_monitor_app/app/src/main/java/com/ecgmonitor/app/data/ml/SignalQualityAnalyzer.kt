package com.ecgmonitor.app.data.ml

import com.ecgmonitor.app.data.ble.BeatWindow
import kotlin.math.abs

/** Individual reasons a beat window was judged unusable. */
enum class QualityFlag { FLAT, SATURATED, NOISY, BASELINE_WANDER }

/**
 * Per-beat signal quality assessment.
 *
 * [score] is a 0..1 composite and [isUsable] is the gate applied before an
 * arrhythmia event is raised from a classification.
 *
 * [distanceFromTraining] is a drift metric rather than a quality metric: it is the
 * mean absolute z-distance of the window from the scaler the model was trained with,
 * expressed in training standard deviations. A persistently huge value means the
 * inputs are not on the scale the model expects, which no amount of clean signal fixes.
 */
data class SignalQuality(
    val score: Float,
    val peakToPeak: Float,
    val baselineDrift: Float,
    val noiseRatio: Float,
    val saturatedFraction: Float,
    val distanceFromTraining: Float,
    val flags: Set<QualityFlag>
) {
    val isUsable: Boolean get() = score >= SignalQualityAnalyzer.USABLE_SCORE
}

/**
 * Scores a beat window on morphology sanity before its classification is trusted.
 *
 * Deliberately unit-agnostic: every quality metric is normalised by the window's own
 * peak-to-peak amplitude, so the analyzer behaves the same whether the ESP32 sends ADC
 * counts or millivolts. Only [distanceFromTraining] is scale-sensitive, and that is
 * reported separately precisely so a scale mismatch shows up instead of hiding.
 *
 * @param ecgMean training-set mean from `model_metadata.json`.
 * @param ecgStd training-set standard deviation from `model_metadata.json`.
 * @param minPeakToPeak amplitude below which the window is treated as flat line.
 * @param saturationLevel magnitude at which a sample counts as rail-clipped. Defaults
 *   to the 12-bit ADC excursion, since stream samples are documented as offsets from
 *   the ADC midpoint (2048).
 */
class SignalQualityAnalyzer(
    private val ecgMean: Float,
    private val ecgStd: Float,
    private val minPeakToPeak: Float = MIN_PEAK_TO_PEAK,
    private val saturationLevel: Float = SATURATION_LEVEL
) {

    fun analyze(window: BeatWindow): SignalQuality {
        val samples = window.ecgSamples
        if (samples.isEmpty()) return UNUSABLE

        val p2p = peakToPeak(samples)
        val saturated = saturatedFraction(samples)
        val drift = baselineDrift(samples, p2p)
        val noise = noiseRatio(samples, p2p)

        val flags = buildSet {
            if (p2p < minPeakToPeak) add(QualityFlag.FLAT)
            if (saturated > MAX_SATURATED_FRACTION) add(QualityFlag.SATURATED)
            if (noise > MAX_NOISE_RATIO) add(QualityFlag.NOISY)
            if (drift > MAX_BASELINE_DRIFT) add(QualityFlag.BASELINE_WANDER)
        }

        val score = (p2p / minPeakToPeak).coerceAtMost(1f) *
                falloff(noise, MAX_NOISE_RATIO) *
                falloff(drift, MAX_BASELINE_DRIFT) *
                falloff(saturated, MAX_SATURATED_FRACTION)

        return SignalQuality(
            score = score,
            peakToPeak = p2p,
            baselineDrift = drift,
            noiseRatio = noise,
            saturatedFraction = saturated,
            distanceFromTraining = distanceFromTraining(samples),
            flags = flags
        )
    }

    /**
     * Maps a "lower is better" metric to a 0..1 sub-score: 1 at zero, 0.5 at the
     * threshold, 0 at twice the threshold.
     */
    private fun falloff(value: Float, threshold: Float): Float =
        (1f - value / (2f * threshold)).coerceIn(0f, 1f)

    private fun peakToPeak(samples: ShortArray): Float {
        var min = Short.MAX_VALUE.toInt()
        var max = Short.MIN_VALUE.toInt()
        for (s in samples) {
            val v = s.toInt()
            if (v < min) min = v
            if (v > max) max = v
        }
        return (max - min).toFloat()
    }

    private fun saturatedFraction(samples: ShortArray): Float =
        samples.count { abs(it.toInt()) >= saturationLevel }.toFloat() / samples.size

    /**
     * Baseline shift between the leading and trailing edges of the window, relative to
     * signal amplitude. The window is R-peak centred, so both edges sit on baseline and
     * a clean beat leaves them at roughly the same level.
     */
    private fun baselineDrift(samples: ShortArray, p2p: Float): Float {
        if (p2p <= 0f) return 0f
        val edge = (samples.size / EDGE_DIVISOR).coerceAtLeast(1)
        var head = 0f
        var tail = 0f
        for (i in 0 until edge) {
            head += samples[i].toInt()
            tail += samples[samples.size - 1 - i].toInt()
        }
        return abs(head - tail) / edge / p2p
    }

    /**
     * High-frequency noise proxy: the *median* absolute second difference, relative to
     * amplitude. The median rather than the mean is what makes this usable — the QRS
     * complex is a legitimately sharp deflection but occupies only a few samples, so it
     * moves the mean while leaving the median dominated by the baseline segments.
     */
    private fun noiseRatio(samples: ShortArray, p2p: Float): Float {
        if (p2p <= 0f || samples.size < MIN_SAMPLES_FOR_NOISE) return 0f
        val diffs = FloatArray(samples.size - 2) { i ->
            abs(samples[i].toInt() - 2 * samples[i + 1].toInt() + samples[i + 2].toInt()).toFloat()
        }
        diffs.sort()
        return diffs[diffs.size / 2] / p2p
    }

    /** Mean |z| against the training scaler, mirroring `ArrhythmiaClassifier.normalizeECG`. */
    private fun distanceFromTraining(samples: ShortArray): Float {
        var sum = 0.0
        for (s in samples) {
            sum += abs((s.toFloat() - ecgMean) / (ecgStd + EPSILON))
        }
        return (sum / samples.size).toFloat()
    }

    companion object {
        /** Composite score at or above which a classification is trusted. */
        const val USABLE_SCORE = 0.5f

        /**
         * Minimum peak-to-peak amplitude in raw sample units. Matches the flat-signal
         * floor the live beat detector already uses (`MIN_SIGNAL_AMPLITUDE`).
         */
        const val MIN_PEAK_TO_PEAK = 50f

        /** 12-bit ADC excursion from the 2048 midpoint, less a small margin. */
        const val SATURATION_LEVEL = 2040f

        /** Above this fraction of rail-clipped samples the morphology is unrecoverable. */
        const val MAX_SATURATED_FRACTION = 0.02f

        /** Median second difference above 8% of amplitude reads as noise, not ECG. */
        const val MAX_NOISE_RATIO = 0.08f

        /** Edge-to-edge baseline shift above 30% of amplitude reads as wander. */
        const val MAX_BASELINE_DRIFT = 0.30f

        /** Fraction of the window averaged at each edge for the drift estimate. */
        private const val EDGE_DIVISOR = 8

        private const val MIN_SAMPLES_FOR_NOISE = 3
        private const val EPSILON = 1e-6f

        private val UNUSABLE = SignalQuality(
            score = 0f,
            peakToPeak = 0f,
            baselineDrift = 0f,
            noiseRatio = 0f,
            saturatedFraction = 0f,
            distanceFromTraining = 0f,
            flags = setOf(QualityFlag.FLAT)
        )
    }
}
