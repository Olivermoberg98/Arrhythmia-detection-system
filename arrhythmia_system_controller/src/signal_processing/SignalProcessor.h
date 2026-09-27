#pragma once
#include "config.h"
#include <stdint.h>
#include <stddef.h>

/**
 * @file  SignalProcessor.h
 * @brief ECG signal conditioning, R-peak detection, beat extraction, RR features.
 *
 * Pipeline per sample:
 *   raw int16 -> float -> HP -> LP -> notch -> Pan-Tompkins detector
 *                                           -> beat ring buffer
 *
 * On each detected R-peak the processor:
 *   1. Waits until BEAT_POST_SAMPLES more samples arrive so the full
 *      128-sample window (96 pre + 32 post) is available.
 *   2. Sets a "beat ready" flag consumable by the caller.
 *   3. Updates a circular RR-interval history for feature computation.
 *
 * Caller flow (main.cpp / sensorTask):
 *   float beat[BEAT_WINDOW_SAMPLES];
 *   float rr[N_RR_FEATURES];
 *   float filtered = processor.processSample(raw);
 *   if (processor.isBeatReady()) {
 *       processor.getBeat(beat);        // raw filtered values, NOT normalised
 *       processor.getRRFeatures(rr);    // [pre_rr_norm, post_rr_norm, ratio_rr]
 *       inference.runInference(beat, rr);
 *   }
 *
 * Normalisation is done inside Inference::runInference() using the
 * constants from model_config.h, not here.
 */
class SignalProcessor {
public:
    SignalProcessor();

    /** @brief Reset all filter + detector state. Safe to call on lead-off. */
    void reset();

    /**
     * @brief  Process one ECG sample through filters + R-peak detector.
     * @param  raw  Raw int16 from ECGSensor (already offset from ADC_MIDPOINT).
     * @return Filtered float value (bandpass + notch output).
     */
    float processSample(int16_t raw);

    /**
     * @brief  True once per detected beat, after the full post-R window
     *         has been collected.  Automatically clears on the next call
     *         to processSample() after being read.
     */
    bool isBeatReady() const;

    /**
     * @brief  Copy the 128-sample beat window into out[BEAT_WINDOW_SAMPLES].
     *         Samples are in chronological order (oldest first).
     *         Values are filtered floats — NOT normalised.
     *         Inference.cpp applies the scaler constants from model_config.h.
     * @note   Only valid when isBeatReady() is true.
     */
    void getBeat(float* out) const;

    /**
     * @brief  Samples elapsed since the R-peak of the beat currently being
     *         reported. Lets the caller timestamp the R-peak itself rather than
     *         the moment the window finished filling.
     */
    uint32_t samplesSinceR() const;

    /**
     * @brief  Fill rr[N_RR_FEATURES] with RR interval features for this beat.
     *         Features: [0] pre_rr_norm, [1] post_rr_norm, [2] ratio_rr
     *         Values are raw ratios — NOT z-score normalised yet.
     *         Inference.cpp applies rr_scaler constants from model_config.h.
     * @note   Only valid when isBeatReady() is true.
     */
    void getRRFeatures(float* rr) const;

private:
    // ------------------------------------------------------------------ //
    // Biquad filter (direct-form II transposed)
    // ------------------------------------------------------------------ //
    struct BiquadState { float w[2] = {0.0f, 0.0f}; };

    float applyBiquad(float x, const float* b, const float* a, BiquadState& st);

    BiquadState _hpState;
    BiquadState _lpState;
    BiquadState _notchState;

    // ------------------------------------------------------------------ //
    // Pan-Tompkins derivative + squaring + MWI pipeline
    // Used for R-peak detection only; the bandpass output feeds the beat buf.
    // ------------------------------------------------------------------ //
    struct PTState {
        // Derivative filter delay line (5-point)
        float  deriv[4]   = {};
        // Moving-window integrator (32 samples ~ 128 ms @ 250 Hz)
        float  mwiBuf[32] = {};
        size_t mwiHead    = 0;
        float  mwiSum     = 0.0f;
        // Detection threshold, recomputed from spki/npki rather than decayed.
        float  threshold  = FilterCfg::PT_THRESHOLD_INIT;
        // Running signal- and noise-peak estimates of the MWI.
        float  spki       = 0.0f;
        float  npki       = 0.0f;
        // Crest search: active between the threshold crossing and the crest.
        bool     searching     = false;
        float    searchBest    = 0.0f;   // largest MWI seen so far
        uint32_t searchBackoff = 0;      // samples since searchBest
        uint32_t searchLeft    = 0;      // samples left in the search window
        // Refractory counter: samples remaining before next peak allowed
        uint32_t refractory = 0;
    } _pt;

    /**
     * Push one filtered sample through the PT pipeline.
     *
     * Returns true when an R-peak is confirmed, which happens up to
     * PT_SEARCH_SAMPLES after the peak itself; @p backoff receives how many
     * samples ago the crest was, so the caller can place the beat window on
     * the R-peak rather than on the confirmation sample.
     */
    bool ptUpdate(float filtered, uint32_t& backoff);

    /** Recompute the detection threshold from the spki/npki estimates. */
    void updateThreshold();

    // ------------------------------------------------------------------ //
    // Beat ring buffer
    // Holds the last ECG_BEAT_BUFFER_SIZE filtered samples so we can
    // reach back BEAT_PRE_SAMPLES before the R-peak.
    // ------------------------------------------------------------------ //
    float  _beatBuf[SamplingCfg::ECG_BEAT_BUFFER_SIZE] = {};
    size_t _beatBufHead = 0;   // next write position
    size_t _beatBufFill = 0;   // saturates at ECG_BEAT_BUFFER_SIZE

    // ------------------------------------------------------------------ //
    // R-peak tracking
    // ------------------------------------------------------------------ //
    // How many samples have arrived since the last detected R-peak.
    uint32_t _samplesSinceR  = 0;
    // Sample index of the last R-peak inside _beatBuf (write-head position
    // at the time of detection).
    size_t   _rPeakBufPos    = 0;
    // True once we are waiting for the post-R window to fill.
    bool     _pendingBeat    = false;
    // Remaining post-R samples needed before the beat window is complete.
    uint32_t _postRRemaining = 0;
    // Set when a full beat window is ready; cleared by processSample().
    bool     _beatReady      = false;

    // ------------------------------------------------------------------ //
    // RR interval history
    // ------------------------------------------------------------------ //
    static constexpr size_t RR_HISTORY = MLCfg::RR_MEAN_WINDOW + 1;

    // Ring buffer of recent RR intervals in samples.
    float  _rrHistory[RR_HISTORY] = {};
    size_t _rrHead    = 0;
    size_t _rrFill    = 0;   // saturates at RR_HISTORY
    // Last two RR intervals (samples) for feature computation.
    float  _preRR     = 0.0f;   // interval before current beat
    float  _postRR    = 0.0f;   // interval after  current beat (set at next R)
    // Sample count since previous R-peak (used to compute _preRR).
    uint32_t _rrCounter = 0;
    // Snapshot of features captured at each beat-complete event.
    float  _rrFeatures[MLCfg::N_RR_FEATURES] = {};
};