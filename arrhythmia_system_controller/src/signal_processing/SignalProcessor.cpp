#include "SignalProcessor.h"
#include <cmath>
#include <cstring>
#include <algorithm>

SignalProcessor::SignalProcessor() {
    reset();
}

void SignalProcessor::reset() {
    _hpState    = {};
    _lpState    = {};
    _notchState = {};
    memset(_window, 0, sizeof(_window));
    _windowHead  = 0;
    _windowFill  = 0;
    _strideCount = 0;
}

// -------------------------------------------------------------------
// Direct-form II Transposed biquad
//   H(z) = (b0 + b1*z⁻¹ + b2*z⁻²) / (1 + a1*z⁻¹ + a2*z⁻²)
// -------------------------------------------------------------------
float SignalProcessor::applyBiquad(float x,
                                   const float* b, const float* a,
                                   BiquadState& st) {
    float y  = b[0]*x + st.w[0];
    st.w[0]  = b[1]*x - a[0]*y + st.w[1];
    st.w[1]  = b[2]*x - a[1]*y;
    return y;
}

// -------------------------------------------------------------------
float SignalProcessor::processSample(int16_t raw) {
    float x = static_cast<float>(raw);

    if (FilterCfg::ENABLE_HP)
        x = applyBiquad(x, FilterCfg::HP_B, FilterCfg::HP_A, _hpState);

    if (FilterCfg::ENABLE_LP)
        x = applyBiquad(x, FilterCfg::LP_B, FilterCfg::LP_A, _lpState);

    if (FilterCfg::ENABLE_NOTCH)
        x = applyBiquad(x, FilterCfg::NOTCH_B, FilterCfg::NOTCH_A, _notchState);

    // --- Push into circular ML window ---------------------------
    _window[_windowHead] = x;
    _windowHead = (_windowHead + 1) % SamplingCfg::ML_WINDOW_SAMPLES;

    if (_windowFill < SamplingCfg::ML_WINDOW_SAMPLES)
        _windowFill++;

    _strideCount++;

    return x;
}

// -------------------------------------------------------------------
bool SignalProcessor::isWindowReady() {
    if (_windowFill < SamplingCfg::ML_WINDOW_SAMPLES) return false;
    if (_strideCount >= SamplingCfg::ML_STRIDE_SAMPLES) {
        _strideCount = 0;
        return true;
    }
    return false;
}

// -------------------------------------------------------------------
void SignalProcessor::getMLWindow(float* out) const {
    // Linearise the circular window (oldest → newest) into out[]
    size_t oldest = _windowHead;   // Head points to the oldest slot
    for (size_t i = 0; i < SamplingCfg::ML_WINDOW_SAMPLES; ++i) {
        out[i] = _window[(oldest + i) % SamplingCfg::ML_WINDOW_SAMPLES];
    }

    // Z-score normalisation over the extracted window
    float sum = 0.0f, sumSq = 0.0f;
    const size_t N = SamplingCfg::ML_WINDOW_SAMPLES;
    for (size_t i = 0; i < N; ++i) sum += out[i];
    float mean = sum / N;
    for (size_t i = 0; i < N; ++i) {
        float d = out[i] - mean;
        sumSq  += d * d;
    }
    float std = sqrtf(sumSq / N);
    if (std < 1e-6f) std = 1e-6f; // Guard against silence / flat line

    for (size_t i = 0; i < N; ++i) {
        out[i] = (out[i] - mean) / std;
    }
}
