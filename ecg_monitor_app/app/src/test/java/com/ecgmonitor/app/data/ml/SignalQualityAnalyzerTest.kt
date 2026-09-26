package com.ecgmonitor.app.data.ml

import com.ecgmonitor.app.data.ble.BeatWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Unit tests for the signal-quality gate.
 *
 * The analyzer is deliberately scale-agnostic, so these fixtures are built in raw
 * sample units (offsets from the ADC midpoint) exactly as the ESP32 sends them.
 */
class SignalQualityAnalyzerTest {

    private val analyzer = SignalQualityAnalyzer(ecgMean = ECG_MEAN, ecgStd = ECG_STD)

    @Test
    fun `clean synthetic beat is usable`() {
        val quality = analyzer.analyze(window(cleanBeat()))

        assertTrue("expected usable, score=${quality.score}", quality.isUsable)
        assertTrue(quality.flags.isEmpty())
    }

    @Test
    fun `flat line is rejected as FLAT`() {
        val quality = analyzer.analyze(window(ShortArray(WINDOW) { 0 }))

        assertFalse(quality.isUsable)
        assertTrue(QualityFlag.FLAT in quality.flags)
        assertEquals(0f, quality.peakToPeak, 0f)
    }

    @Test
    fun `low amplitude signal below the flat floor is rejected`() {
        val faint = ShortArray(WINDOW) { i -> (sin(i * TWO_PI / WINDOW) * 10).toInt().toShort() }

        assertFalse(analyzer.analyze(window(faint)).isUsable)
    }

    @Test
    fun `high frequency noise is flagged as NOISY and rejected`() {
        val rng = Random(SEED)
        val noisy = ShortArray(WINDOW) { (rng.nextInt(-400, 400)).toShort() }

        val quality = analyzer.analyze(window(noisy))

        assertTrue(QualityFlag.NOISY in quality.flags)
        assertFalse(quality.isUsable)
    }

    @Test
    fun `rail clipped window is flagged as SATURATED`() {
        val clipped = cleanBeat().copyOf()
        // Clip well above the 2% tolerance so this is unambiguous.
        for (i in 0 until WINDOW / 4) clipped[i] = 2100
        val quality = analyzer.analyze(window(clipped))

        assertTrue(QualityFlag.SATURATED in quality.flags)
        assertFalse(quality.isUsable)
    }

    @Test
    fun `baseline wander is flagged`() {
        // A clean beat with a large linear ramp added across the window.
        val base = cleanBeat()
        val drifting = ShortArray(WINDOW) { i ->
            (base[i] + (i * DRIFT_SLOPE)).toInt().toShort()
        }

        val quality = analyzer.analyze(window(drifting))

        assertTrue(
            "drift=${quality.baselineDrift}",
            QualityFlag.BASELINE_WANDER in quality.flags
        )
    }

    @Test
    fun `QRS sharpness alone does not trip the noise flag`() {
        // Guards the median-vs-mean choice: a genuine sharp QRS on an otherwise quiet
        // baseline must not read as noise.
        val quality = analyzer.analyze(window(cleanBeat()))

        assertTrue(
            "noiseRatio=${quality.noiseRatio}",
            quality.noiseRatio < SignalQualityAnalyzer.MAX_NOISE_RATIO
        )
    }

    @Test
    fun `distanceFromTraining reports raw sigma distance without rescaling`() {
        // Raw ADC-unit samples are far outside a scaler fitted on millivolts. The
        // analyzer must report that honestly rather than normalising it away, since
        // this is the metric that reveals a unit mismatch between firmware and model.
        val quality = analyzer.analyze(window(cleanBeat()))

        assertTrue(
            "expected a large drift score, got ${quality.distanceFromTraining}",
            quality.distanceFromTraining > LARGE_DRIFT
        )
    }

    @Test
    fun `empty window is unusable rather than crashing`() {
        val quality = analyzer.analyze(
            BeatWindow(0, 0L, ShortArray(0), FloatArray(RR_FEATURES))
        )

        assertFalse(quality.isUsable)
        assertTrue(QualityFlag.FLAT in quality.flags)
    }

    // region Fixtures

    /**
     * A crude but recognisable beat: quiet baseline, a sharp QRS spike at the centre,
     * and a small T-wave. Amplitude is comfortably above the flat-signal floor.
     */
    private fun cleanBeat(): ShortArray = ShortArray(WINDOW) { i ->
        val qrs = when (i) {
            QRS_CENTRE - 2 -> -60
            QRS_CENTRE - 1 -> 200
            QRS_CENTRE     -> 600
            QRS_CENTRE + 1 -> 180
            QRS_CENTRE + 2 -> -70
            else -> 0
        }
        val tWave = if (i in T_START until T_END) {
            (sin((i - T_START) * PI / (T_END - T_START)) * 60).toInt()
        } else 0
        (qrs + tWave).toShort()
    }

    private fun window(samples: ShortArray) =
        BeatWindow(
            beatNum = 1,
            timestampMs = 1_000L,
            ecgSamples = samples,
            rrFeatures = FloatArray(RR_FEATURES)
        )

    // endregion

    private companion object {
        const val WINDOW = 128
        const val QRS_CENTRE = 64
        const val T_START = 80
        const val T_END = 104
        const val RR_FEATURES = 3

        /** Values from the checked-in `model_metadata.json` ecg_scaler. */
        const val ECG_MEAN = 0.019062543f
        const val ECG_STD = 0.41106507f

        const val DRIFT_SLOPE = 4

        /**
         * Correctly scaled input would sit around 1 sigma. Raw ADC units land orders of
         * magnitude out, so anything above this confirms the metric is not rescaling.
         */
        const val LARGE_DRIFT = 10f
        const val TWO_PI = 2 * PI
        const val SEED = 42
    }
}
