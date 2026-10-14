package com.ecgmonitor.app.data.ml

import com.ecgmonitor.app.data.models.ClassificationResult
import kotlin.math.abs
import kotlin.math.ln

/**
 * Plausibility verdict on the rate at which beats are being classified.
 *
 * Rate alone never suppresses a classification. A high rate is ambiguous — motion
 * artefact double-counting R-peaks and a genuine tachyarrhythmia both present as a
 * doubled rate — so the signal-quality rate is what separates the two, and a high rate
 * on *clean* signal is escalated rather than dismissed.
 */
enum class RateFlag {
    /** Too few beats so far to judge. */
    INSUFFICIENT_DATA,

    /** Rate is within the plausible resting band. */
    NORMAL,

    /**
     * Implausibly low. Below roughly 20 beats/min the explanation is lead-off, BLE
     * packet loss, or a failing beat detector — not bradycardia, which would present
     * far higher and in a patient who could not be sitting up holding a phone.
     */
    LOW_PIPELINE_SUSPECT,

    /** High rate coinciding with poor signal quality — most likely double-counted peaks. */
    HIGH_ARTIFACT_SUSPECT,

    /** High rate on clean signal — treat as a possible real tachyarrhythmia. */
    HIGH_TACHY_SUSPECT
}

/**
 * Aggregate model-health metrics for one recording session.
 *
 * Every field is a scalar summary, never waveform data, so this is safe to sync under
 * the existing metadata-only Firebase split.
 */
data class MonitoringSummary(
    val modelVersion: String,
    val totalClassified: Int,
    val meanConfidence: Float,
    val lowConfidenceRate: Float,
    val meanEntropy: Float,
    val poorSignalRate: Float,
    val suppressedEvents: Int,
    val driftScore: Float,
    val classificationsPerMin: Float,
    val rateFlag: RateFlag,
    val meanInferenceMs: Float,
    val maxInferenceMs: Long
)

/**
 * Accumulates model-health statistics across a recording.
 *
 * There is no field ground truth, so nothing here measures accuracy. These are
 * unsupervised health signals: how confident the model is, how often it falls below its
 * own per-class thresholds, how far inputs sit from the training distribution, and
 * whether the beat rate is physiologically plausible. A rising [MonitoringSummary
 * .lowConfidenceRate] or [MonitoringSummary.driftScore] is the available proxy for
 * "the model is seeing data it was not trained on".
 *
 * Not thread-safe — the ViewModel confines it to the Default dispatcher.
 */
class ClassificationMonitor(
    private val modelVersion: String,
    private val minPlausibleRate: Float = MIN_PLAUSIBLE_RATE,
    private val maxRestingRate: Float = MAX_RESTING_RATE
) {

    private var total = 0
    private var rejected = 0
    private var poorSignal = 0
    private var suppressed = 0
    private var confidenceSum = 0.0
    private var entropySum = 0.0
    private var distanceSum = 0.0
    private var inferenceSum = 0L
    private var inferenceMax = 0L
    /**
     * Explicit flag rather than a 0L sentinel — a beat legitimately carrying
     * timestamp 0 would otherwise be skipped and the session span under-measured.
     */
    private var hasFirstTimestamp = false
    private var firstTimestamp = 0L
    private var lastTimestamp = 0L

    /** Recent R-peak timestamps, trimmed to [RATE_WINDOW_MS], for the live rate. */
    private val recentTimestamps = ArrayDeque<Long>()

    /**
     * Records one classified beat.
     *
     * @param eventSuppressed true when an abnormal classification was withheld because
     *   [quality] failed the usability gate.
     */
    fun record(
        result: ClassificationResult,
        quality: SignalQuality,
        eventSuppressed: Boolean
    ) {
        total++
        if (!result.isValid) rejected++
        if (!quality.isUsable) poorSignal++
        if (eventSuppressed) suppressed++

        confidenceSum += result.confidence
        entropySum += normalisedEntropy(result.scores)
        distanceSum += quality.distanceFromTraining
        inferenceSum += result.inferenceTimeMs
        if (result.inferenceTimeMs > inferenceMax) inferenceMax = result.inferenceTimeMs

        if (!hasFirstTimestamp) {
            firstTimestamp = result.timestampMs
            hasFirstTimestamp = true
        }
        lastTimestamp = result.timestampMs

        recentTimestamps.addLast(result.timestampMs)
        while (recentTimestamps.size > 1 &&
            result.timestampMs - recentTimestamps.first() > RATE_WINDOW_MS
        ) {
            recentTimestamps.removeFirst()
        }
    }

    fun snapshot(): MonitoringSummary {
        val n = total.coerceAtLeast(1)
        val poorRate = poorSignal.toFloat() / n
        val rate = sessionRatePerMin()
        return MonitoringSummary(
            modelVersion = modelVersion,
            totalClassified = total,
            meanConfidence = (confidenceSum / n).toFloat(),
            lowConfidenceRate = rejected.toFloat() / n,
            meanEntropy = (entropySum / n).toFloat(),
            poorSignalRate = poorRate,
            suppressedEvents = suppressed,
            driftScore = (distanceSum / n).toFloat(),
            classificationsPerMin = rate,
            rateFlag = rateFlagFor(rate, poorRate),
            meanInferenceMs = inferenceSum.toFloat() / n,
            maxInferenceMs = inferenceMax
        )
    }

    /** Beats per minute over the trailing [RATE_WINDOW_MS], for live display. */
    fun rollingRatePerMin(): Float {
        if (recentTimestamps.size < 2) return 0f
        val span = recentTimestamps.last() - recentTimestamps.first()
        return if (span <= 0L) 0f else (recentTimestamps.size - 1) * MS_PER_MIN / span.toFloat()
    }

    fun rollingRateFlag(): RateFlag = rateFlagFor(
        rollingRatePerMin(),
        poorSignal.toFloat() / total.coerceAtLeast(1)
    )

    fun reset() {
        total = 0
        rejected = 0
        poorSignal = 0
        suppressed = 0
        confidenceSum = 0.0
        entropySum = 0.0
        distanceSum = 0.0
        inferenceSum = 0L
        inferenceMax = 0L
        hasFirstTimestamp = false
        firstTimestamp = 0L
        lastTimestamp = 0L
        recentTimestamps.clear()
    }

    private fun sessionRatePerMin(): Float {
        val span = lastTimestamp - firstTimestamp
        if (total < 2 || span <= 0L) return 0f
        return (total - 1) * MS_PER_MIN / span.toFloat()
    }

    private fun rateFlagFor(ratePerMin: Float, poorSignalRate: Float): RateFlag = when {
        total < MIN_BEATS_FOR_RATE || ratePerMin <= 0f -> RateFlag.INSUFFICIENT_DATA
        ratePerMin < minPlausibleRate -> RateFlag.LOW_PIPELINE_SUSPECT
        ratePerMin <= maxRestingRate -> RateFlag.NORMAL
        poorSignalRate > ARTIFACT_SIGNAL_RATE -> RateFlag.HIGH_ARTIFACT_SUSPECT
        else -> RateFlag.HIGH_TACHY_SUSPECT
    }

    /**
     * Shannon entropy of the score vector scaled to 0..1, where 0 is a one-hot
     * prediction and 1 is a uniform guess. Scale-free across class counts, so it stays
     * comparable if the model gains a class.
     */
    private fun normalisedEntropy(scores: FloatArray): Double {
        val sum = scores.sumOf { abs(it).toDouble() }
        return when {
            scores.size < 2 -> 0.0
            sum <= 0.0 -> 1.0
            else -> {
                var entropy = 0.0
                for (s in scores) {
                    val p = abs(s).toDouble() / sum
                    if (p > 0.0) entropy -= p * ln(p)
                }
                (entropy / ln(scores.size.toDouble())).coerceIn(0.0, 1.0)
            }
        }
    }

    companion object {
        /**
         * Below this many beats/min the pipeline is suspect rather than the patient.
         * Severe bradycardia is ~40 bpm; 20 is near-agonal.
         */
        const val MIN_PLAUSIBLE_RATE = 20f

        /**
         * Above this many beats/min the reading is implausible for a resting subject
         * (clinical tachycardia starts at 100). Not a discard threshold — see [RateFlag].
         */
        const val MAX_RESTING_RATE = 140f

        /** Poor-signal fraction above which a high rate is attributed to artefact. */
        const val ARTIFACT_SIGNAL_RATE = 0.25f

        /** Minimum beats before any rate verdict is meaningful. */
        const val MIN_BEATS_FOR_RATE = 5

        /** Trailing window for the live rate estimate. */
        const val RATE_WINDOW_MS = 30_000L

        private const val MS_PER_MIN = 60_000f
    }
}
