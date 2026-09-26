package com.ecgmonitor.app.data.ml

import com.ecgmonitor.app.data.models.ClassificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the model-health accumulator and the rate plausibility rules. */
class ClassificationMonitorTest {

    private fun monitor() = ClassificationMonitor(modelVersion = MODEL_VERSION)

    // region Aggregate health metrics

    @Test
    fun `empty monitor reports a safe zero snapshot`() {
        val summary = monitor().snapshot()

        assertEquals(0, summary.totalClassified)
        assertEquals(RateFlag.INSUFFICIENT_DATA, summary.rateFlag)
        assertEquals(MODEL_VERSION, summary.modelVersion)
    }

    @Test
    fun `lowConfidenceRate counts beats below the model threshold`() {
        val m = monitor()
        // 4 beats, 1 of which the classifier marked invalid.
        m.record(result(beat = 1, valid = true), good(), false)
        m.record(result(beat = 2, valid = false), good(), false)
        m.record(result(beat = 3, valid = true), good(), false)
        m.record(result(beat = 4, valid = true), good(), false)

        assertEquals(0.25f, m.snapshot().lowConfidenceRate, TOLERANCE)
    }

    @Test
    fun `poorSignalRate and suppressedEvents track the quality gate`() {
        val m = monitor()
        m.record(result(beat = 1), good(), false)
        m.record(result(beat = 2), poor(), true)
        m.record(result(beat = 3), poor(), false)
        m.record(result(beat = 4), good(), false)

        val summary = m.snapshot()
        assertEquals(0.5f, summary.poorSignalRate, TOLERANCE)
        assertEquals(1, summary.suppressedEvents)
    }

    @Test
    fun `entropy is near zero for a decisive prediction`() {
        val m = monitor()
        m.record(result(beat = 1, scores = floatArrayOf(0.99f, 0.005f, 0.005f)), good(), false)

        assertTrue(m.snapshot().meanEntropy < 0.2f)
    }

    @Test
    fun `entropy is near one for a uniform guess`() {
        val m = monitor()
        val third = 1f / 3f
        m.record(result(beat = 1, scores = floatArrayOf(third, third, third)), good(), false)

        assertEquals(1f, m.snapshot().meanEntropy, 0.01f)
    }

    @Test
    fun `inference timing is averaged and peaked`() {
        val m = monitor()
        m.record(result(beat = 1, inferenceMs = 10L), good(), false)
        m.record(result(beat = 2, inferenceMs = 30L), good(), false)

        val summary = m.snapshot()
        assertEquals(20f, summary.meanInferenceMs, TOLERANCE)
        assertEquals(30L, summary.maxInferenceMs)
    }

    @Test
    fun `reset clears all accumulators`() {
        val m = monitor()
        repeat(BEATS) { i -> m.record(result(beat = i, inferenceMs = 5L), poor(), true) }
        m.reset()

        val summary = m.snapshot()
        assertEquals(0, summary.totalClassified)
        assertEquals(0, summary.suppressedEvents)
        assertEquals(0f, summary.poorSignalRate, TOLERANCE)
        assertEquals(RateFlag.INSUFFICIENT_DATA, summary.rateFlag)
    }

    // endregion

    // region Rate plausibility

    @Test
    fun `resting rate of 60 bpm is NORMAL`() {
        assertEquals(RateFlag.NORMAL, flagForRate(bpm = 60, poorSignal = false))
    }

    @Test
    fun `rate below 20 per min is attributed to the pipeline not bradycardia`() {
        assertEquals(
            RateFlag.LOW_PIPELINE_SUSPECT,
            flagForRate(bpm = 12, poorSignal = false)
        )
    }

    @Test
    fun `rate above 140 on clean signal escalates as possible tachyarrhythmia`() {
        // The safety-critical case: a real SVT run must not be dismissed as artefact.
        assertEquals(
            RateFlag.HIGH_TACHY_SUSPECT,
            flagForRate(bpm = 180, poorSignal = false)
        )
    }

    @Test
    fun `rate above 140 on poor signal is attributed to artefact`() {
        assertEquals(
            RateFlag.HIGH_ARTIFACT_SUSPECT,
            flagForRate(bpm = 180, poorSignal = true)
        )
    }

    @Test
    fun `rate exactly at the resting ceiling is still NORMAL`() {
        // Ceiling set to 120 so a 500 ms spacing lands on it exactly — 60000/140 is not
        // a whole number of milliseconds, which would put the fixture just over the line.
        val m = ClassificationMonitor(modelVersion = MODEL_VERSION, maxRestingRate = 120f)
        repeat(BEATS) { i ->
            m.record(result(beat = i, timestampMs = i * 500L), good(), false)
        }

        assertEquals(120f, m.snapshot().classificationsPerMin, TOLERANCE)
        assertEquals(RateFlag.NORMAL, m.snapshot().rateFlag)
    }

    @Test
    fun `too few beats yields INSUFFICIENT_DATA regardless of spacing`() {
        val m = monitor()
        m.record(result(beat = 1, timestampMs = 0L), good(), false)
        m.record(result(beat = 2, timestampMs = 1_000L), good(), false)

        assertEquals(RateFlag.INSUFFICIENT_DATA, m.snapshot().rateFlag)
    }

    @Test
    fun `session rate is computed from beat timestamps`() {
        // 21 beats spaced 500 ms apart = 120 bpm.
        val m = monitor()
        repeat(21) { i ->
            m.record(result(beat = i, timestampMs = i * 500L), good(), false)
        }

        assertEquals(120f, m.snapshot().classificationsPerMin, 1f)
    }

    @Test
    fun `rolling rate tracks only the trailing window`() {
        val m = monitor()
        // A long quiet stretch, then a burst — the rolling rate must reflect the burst.
        m.record(result(beat = 0, timestampMs = 0L), good(), false)
        val burstStart = ClassificationMonitor.RATE_WINDOW_MS * 2
        repeat(BEATS) { i ->
            m.record(result(beat = i + 1, timestampMs = burstStart + i * 500L), good(), false)
        }

        assertEquals(120f, m.rollingRatePerMin(), 5f)
    }

    // endregion

    // region Fixtures

    /** Drives the monitor at a fixed bpm long enough for a rate verdict. */
    private fun flagForRate(bpm: Int, poorSignal: Boolean): RateFlag {
        val m = monitor()
        val intervalMs = MS_PER_MIN / bpm
        repeat(BEATS) { i ->
            m.record(
                result(beat = i, timestampMs = i.toLong() * intervalMs),
                if (poorSignal) poor() else good(),
                false
            )
        }
        return m.snapshot().rateFlag
    }

    private fun result(
        beat: Int,
        timestampMs: Long = beat * 1_000L,
        valid: Boolean = true,
        scores: FloatArray = floatArrayOf(0.9f, 0.05f, 0.05f),
        inferenceMs: Long = 20L
    ) = ClassificationResult(
        beatNum = beat,
        timestampMs = timestampMs,
        classId = 0,
        className = "Normal",
        confidence = scores.max(),
        isValid = valid,
        scores = scores,
        inferenceTimeMs = inferenceMs
    )

    private fun good() = quality(score = 0.9f)

    private fun poor() = quality(score = 0.1f)

    private fun quality(score: Float) = SignalQuality(
        score = score,
        peakToPeak = 600f,
        baselineDrift = 0.01f,
        noiseRatio = 0.01f,
        saturatedFraction = 0f,
        distanceFromTraining = 100f,
        flags = emptySet()
    )

    // endregion

    private companion object {
        const val MODEL_VERSION = "1.0.0"
        const val TOLERANCE = 0.0001f

        /** Comfortably above `MIN_BEATS_FOR_RATE`. */
        const val BEATS = 30
        const val MS_PER_MIN = 60_000
    }
}
