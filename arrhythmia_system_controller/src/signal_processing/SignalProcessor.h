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
 * For each detected R-peak (beat B) the processor:
 *   1. Waits until BEAT_POST_SAMPLES more samples arrive, then copies out the
 *      128-sample window (96 pre + 32 post).
 *   2. Holds the beat until R(B+1) is detected, so post_rr is the real
 *      interval — the same definition the model was trained on.
 *   3. Sets a "beat ready" flag for beat B at R(B+1). Beats therefore arrive
 *      one RR interval late; samplesSinceR() lets the caller back-date them.
 *
 * Caller flow (main.cpp / sensorTask):
 *   float filtered = processor.processSample(raw);
 *   if (processor.isBeatReady()) {
 *       processor.getBeat(beat);        // filtered ADC counts, NOT normalised
 *       processor.getRRFeatures(rr);    // [pre_rr_norm, post_rr_norm, ratio_rr]
 *   }
 *
 * Normalisation and inference happen in the Android app.
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
     * @brief  True once per beat, at the following R-peak (which supplies
     *         post_rr). Automatically clears on the next processSample().
     */
    bool isBeatReady() const;

    /**
     * @brief  Copy the 128-sample beat window into out[BEAT_WINDOW_SAMPLES].
     *         Samples are in chronological order (oldest first).
     *         Values are filtered floats in ADC counts — NOT normalised.
     * @note   Only valid when isBeatReady() is true.
     */
    void getBeat(float* out) const;

    /**
     * @brief  Samples elapsed since the R-peak of the beat currently being
     *         reported (one RR interval plus detection latency). Lets the
     *         caller timestamp the R-peak itself rather than the emit time.
     */
    uint32_t samplesSinceR() const;

    /**
     * @brief  Fill rr[N_RR_FEATURES] with RR interval features for this beat.
     *         Features: [0] pre_rr_norm, [1] post_rr_norm, [2] ratio_rr
     *         Values are raw ratios — NOT z-score normalised yet.
     *         Mean RR = mean of the last RR_MEAN_WINDOW intervals up to pre_rr.
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
    // Sample index of the last R-peak inside _beatBuf (write-head position
    // at the time of detection).
    size_t   _rPeakBufPos    = 0;
    // True once we are waiting for the post-R window to fill.
    bool     _pendingBeat    = false;
    // Remaining post-R samples needed before the beat window is complete.
    uint32_t _postRRemaining = 0;
    // Set when a held beat is emitted; cleared by processSample().
    bool     _beatReady      = false;
    // R-peaks seen since reset, saturating at 1: the first has no valid RR.
    uint32_t _rPeaksSeen     = 0;

    // Completed window waiting for the next R-peak to supply post_rr.
    bool     _holding        = false;
    float    _heldWindow[SamplingCfg::BEAT_WINDOW_SAMPLES] = {};
    float    _heldPreRR      = 0.0f;   // samples
    float    _heldMeanRR     = 0.0f;   // samples
    // The emitted beat, and how many samples ago its R-peak was.
    float    _outWindow[SamplingCfg::BEAT_WINDOW_SAMPLES] = {};
    uint32_t _reportSinceR   = 0;

    // ------------------------------------------------------------------ //
    // RR interval history
    // ------------------------------------------------------------------ //
    static constexpr size_t RR_HISTORY = MLCfg::RR_MEAN_WINDOW;

    // Ring buffer of recent RR intervals in samples.
    float  _rrHistory[RR_HISTORY] = {};
    size_t _rrHead    = 0;
    size_t _rrFill    = 0;   // saturates at RR_HISTORY
    // Most recent valid RR interval (samples).
    float  _lastRR    = 0.0f;
    // Sample count since previous R-peak.
    uint32_t _rrCounter = 0;
    // Features of the emitted beat.
    float  _rrFeatures[MLCfg::N_RR_FEATURES] = {};
};