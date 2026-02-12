#pragma once
#include "config.h"
#include <stdint.h>
#include <stddef.h>

/**
 * @file  SignalProcessor.h
 * @brief ECG signal conditioning + ML sliding-window management.
 *
 * Pipeline per sample:
 *   raw int16 → float cast → high-pass → low-pass → notch → normalise → ML buffer
 *
 * The ML window is a flat circular array that always holds the most
 * recent ML_WINDOW_SAMPLES processed samples.  When ML_STRIDE_SAMPLES
 * new samples have accumulated since the last inference, isWindowReady()
 * returns true and getMLWindow() copies data into the caller's buffer.
 */

class SignalProcessor {
public:
    SignalProcessor();

    /** @brief Reset all filter state and window counters. */
    void reset();

    /**
     * @brief  Process one ECG sample through the full filter chain.
     * @param  raw  Raw int16 sample from ECGSensor.
     * @return Filtered float value (not yet normalised).
     */
    float processSample(int16_t raw);

    /**
     * @brief  Returns true when a fresh ML window is ready for inference.
     *         Resets the stride counter on call.
     */
    bool isWindowReady();

    /**
     * @brief  Copy the ML window into `out[ML_WINDOW_SAMPLES]` as
     *         z-score normalised float32 values.
     */
    void getMLWindow(float* out) const;

private:
    // ---- Biquad filter state (direct-form II) ------------------
    struct BiquadState {
        float w[2] = {0.0f, 0.0f};
    };

    float applyBiquad(float x,
                      const float* b, const float* a,
                      BiquadState& state);

    BiquadState _hpState;
    BiquadState _lpState;
    BiquadState _notchState;

    // ---- ML sliding window ------------------------------------
    float  _window[SamplingCfg::ML_WINDOW_SAMPLES] = {};
    size_t _windowHead  = 0;
    size_t _windowFill  = 0;   // Counts up to ML_WINDOW_SAMPLES on first fill
    size_t _strideCount = 0;   // Counts new samples since last inference
};