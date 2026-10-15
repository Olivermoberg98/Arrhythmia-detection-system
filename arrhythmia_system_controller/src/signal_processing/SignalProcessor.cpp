#include "SignalProcessor.h"
#include <cmath>
#include <cstring>
#include <algorithm>

// ======================================================================
// Constructor / reset
// ======================================================================

SignalProcessor::SignalProcessor() {
    reset();
}

void SignalProcessor::reset() {
    _hpState    = {};
    _lpState    = {};
    _notchState = {};
    _pt         = {};

    memset(_beatBuf, 0, sizeof(_beatBuf));
    _beatBufHead = 0;
    _beatBufFill = 0;

    _rPeakBufPos    = 0;
    _pendingBeat    = false;
    _postRRemaining = 0;
    _beatReady      = false;
    _rPeaksSeen     = 0;

    _holding      = false;
    _heldPreRR    = 0.0f;
    _heldMeanRR   = 0.0f;
    _reportSinceR = 0;
    memset(_heldWindow, 0, sizeof(_heldWindow));
    memset(_outWindow, 0, sizeof(_outWindow));

    memset(_rrHistory, 0, sizeof(_rrHistory));
    _rrHead     = 0;
    _rrFill     = 0;
    _lastRR     = 0.0f;
    _rrCounter  = 0;
    memset(_rrFeatures, 0, sizeof(_rrFeatures));
}

// ======================================================================
// Biquad filter (direct-form II transposed)
// ======================================================================

float SignalProcessor::applyBiquad(float x,
                                   const float* b, const float* a,
                                   BiquadState& st) {
    float y = b[0]*x + st.w[0];
    st.w[0]  = b[1]*x - a[0]*y + st.w[1];
    st.w[1]  = b[2]*x - a[1]*y;
    return y;
}

// ======================================================================
// Pan-Tompkins R-peak detector
//
// Runs on the bandpass-filtered signal.  The pipeline is:
//   bandpass -> 5-point derivative -> square -> 32-sample MWI -> threshold
//
// The derivative + MWI emphasise the steep slope around R-peaks and
// suppress T/P waves.  Detection is a two-stage process: a threshold
// crossing opens a short search window, and the R-peak is taken as the MWI
// crest within it.
//
// The crest search is what makes the threshold stable. The previous version
// fired on the crossing itself and set the next threshold to 0.625 * that
// value — which is by definition barely above the current threshold, not the
// height of the R-peak. Every detection therefore lowered the threshold, the
// lower threshold caught a smaller deflection, and the detector ratcheted
// down onto T waves. On a real 67 bpm recording it reported 145 bpm, and the
// spurious beats produced short RR intervals that the classifier correctly
// read as premature, flooding the session with false SVEB events.
//
// Signal and noise peaks are now tracked separately (spki / npki, standard
// Pan-Tompkins) so a noise detection cannot drag the signal estimate down,
// with a hard floor at PT_THRESHOLD_FLOOR * spki.
// ======================================================================

bool SignalProcessor::ptUpdate(float filtered, uint32_t& backoff) {
    backoff = 0;

    // -- Refractory period ------------------------------------------------
    if (_pt.refractory > 0) {
        _pt.refractory--;
    }

    // -- 5-point derivative  y[n] = (2x[n] + x[n-1] - x[n-3] - 2x[n-4]) / 8
    float deriv = (2.0f * filtered
                   + _pt.deriv[0]
                   - _pt.deriv[2]
                   - 2.0f * _pt.deriv[3]) / 8.0f;

    // Shift delay line
    _pt.deriv[3] = _pt.deriv[2];
    _pt.deriv[2] = _pt.deriv[1];
    _pt.deriv[1] = _pt.deriv[0];
    _pt.deriv[0] = filtered;

    // -- Square -----------------------------------------------------------
    float sq = deriv * deriv;

    // -- Moving-window integrator (32 samples = 128 ms @ 250 Hz) ----------
    const size_t MWI_LEN = sizeof(_pt.mwiBuf) / sizeof(_pt.mwiBuf[0]);
    _pt.mwiSum -= _pt.mwiBuf[_pt.mwiHead];
    _pt.mwiBuf[_pt.mwiHead] = sq;
    _pt.mwiSum += sq;
    _pt.mwiHead = (_pt.mwiHead + 1) % MWI_LEN;
    float mwi = _pt.mwiSum / MWI_LEN;

    // -- Crest search ------------------------------------------------------
    if (_pt.searching) {
        if (mwi > _pt.searchBest) {
            _pt.searchBest    = mwi;
            _pt.searchBackoff = 0;
        } else {
            _pt.searchBackoff++;
        }

        _pt.searchLeft--;
        if (_pt.searchLeft > 0) {
            return false;
        }

        // Window closed — the crest is the R-peak.
        _pt.spki = (_pt.spki > 0.0f)
            ? FilterCfg::PT_PEAK_WEIGHT * _pt.searchBest
              + (1.0f - FilterCfg::PT_PEAK_WEIGHT) * _pt.spki
            : _pt.searchBest;

        updateThreshold();

        _pt.searching   = false;
        _pt.refractory  = FilterCfg::REFRACTORY_SAMPLES;
        backoff         = _pt.searchBackoff;
        return true;
    }

    // -- Threshold crossing opens a search window --------------------------
    if (_pt.refractory == 0 && mwi > _pt.threshold) {
        _pt.searching     = true;
        _pt.searchBest    = mwi;
        _pt.searchBackoff = 0;
        _pt.searchLeft    = FilterCfg::PT_SEARCH_SAMPLES;
        return false;
    }

    // -- Below threshold: this sample is noise -----------------------------
    _pt.npki = (_pt.npki > 0.0f)
        ? FilterCfg::PT_PEAK_WEIGHT * mwi
          + (1.0f - FilterCfg::PT_PEAK_WEIGHT) * _pt.npki
        : mwi;
    updateThreshold();

    return false;
}

void SignalProcessor::updateThreshold() {
    if (_pt.spki <= 0.0f) return;   // keep PT_THRESHOLD_INIT until the first beat

    const float adaptive = _pt.npki
        + FilterCfg::PT_THRESHOLD_FRAC * (_pt.spki - _pt.npki);
    const float floorVal = FilterCfg::PT_THRESHOLD_FLOOR * _pt.spki;

    _pt.threshold = (adaptive > floorVal) ? adaptive : floorVal;
}

// ======================================================================
// processSample — main entry point called for every ADC sample
// ======================================================================

float SignalProcessor::processSample(int16_t raw) {
    // Clear "ready" flag from the previous beat so the caller only sees
    // it for one call cycle.
    _beatReady = false;

    // -- Filter chain -----------------------------------------------------
    float x = static_cast<float>(raw);

    if (FilterCfg::ENABLE_HP)
        x = applyBiquad(x, FilterCfg::HP_B, FilterCfg::HP_A, _hpState);
    if (FilterCfg::ENABLE_LP)
        x = applyBiquad(x, FilterCfg::LP_B, FilterCfg::LP_A, _lpState);
    if (FilterCfg::ENABLE_NOTCH)
        x = applyBiquad(x, FilterCfg::NOTCH_B, FilterCfg::NOTCH_A, _notchState);

    // -- Store in beat ring buffer ----------------------------------------
    _beatBuf[_beatBufHead] = x;
    const size_t thisPos   = _beatBufHead;
    _beatBufHead = (_beatBufHead + 1) % SamplingCfg::ECG_BEAT_BUFFER_SIZE;
    if (_beatBufFill < SamplingCfg::ECG_BEAT_BUFFER_SIZE)
        _beatBufFill++;

    // -- RR counter (counts every sample) ---------------------------------
    _rrCounter++;

    // -- Post-R window countdown ------------------------------------------
    if (_pendingBeat) {
        if (_postRRemaining > 0) {
            _postRRemaining--;
        }
        if (_postRRemaining == 0) {
            // Full window is in the ring buffer. Copy it out and hold it: the
            // beat is emitted at the next R-peak, which supplies post_rr.
            _pendingBeat = false;

            const size_t BUF = SamplingCfg::ECG_BEAT_BUFFER_SIZE;
            const size_t startPos =
                (_rPeakBufPos + BUF - SamplingCfg::BEAT_PRE_SAMPLES) % BUF;
            for (size_t i = 0; i < SamplingCfg::BEAT_WINDOW_SAMPLES; ++i) {
                _heldWindow[i] = _beatBuf[(startPos + i) % BUF];
            }

            // History still ends at pre_rr here, so the mean covers the
            // RR_MEAN_WINDOW intervals up to and including this beat's pre_rr.
            float sum = 0.0f;
            for (size_t i = 0; i < _rrFill; ++i) sum += _rrHistory[i];
            _heldPreRR  = _lastRR;
            _heldMeanRR = (_rrFill > 0) ? sum / _rrFill : 0.0f;
            _holding    = true;
        }
    }

    // -- R-peak detector --------------------------------------------------
    // The detector confirms a peak up to PT_SEARCH_SAMPLES late, so every
    // position below is back-dated by `backoff` to the crest itself.
    uint32_t backoff = 0;
    bool rPeak = ptUpdate(x, backoff);

    if (rPeak) {
        // Undo the MWI group delay, then move to the QRS's slope-energy centroid
        // (mean time of the squared first difference within the search window).
        // Snapping to the largest |deflection| flipped between R and S on
        // RS-type beats (|R| ~ |S|), shifting the window ~30 ms beat to beat.
        // Mirrors preprocess.py::snap_rpeak; change one, change both.
        const size_t BUF = SamplingCfg::ECG_BEAT_BUFFER_SIZE;
        auto at = [&](uint32_t back) { return _beatBuf[(thisPos + BUF - back) % BUF]; };

        float centre = static_cast<float>(backoff + FilterCfg::PT_MWI_GROUP_DELAY);
        for (uint32_t iter = 0; iter < FilterCfg::PT_FIDUCIAL_ITERATIONS; ++iter) {
            const uint32_t c = static_cast<uint32_t>(centre + 0.5f);
            uint32_t half = FilterCfg::PT_RPEAK_SEARCH_HALF;
            if (half > c) half = c;                              // stay in the past
            if (_beatBufFill < c + half + 2) break;              // not enough history

            float sumW = 0.0f, sumWB = 0.0f;
            for (uint32_t b = c - half; b <= c + half; ++b) {
                const float d = at(b) - at(b + 1);               // x[t] - x[t-1]
                sumW  += d * d;
                sumWB += d * d * static_cast<float>(b);
            }
            if (sumW > 0.0f) centre = sumWB / sumW;
        }
        backoff = static_cast<uint32_t>(centre + 0.5f);

        // _rrCounter ran on past the R-peak during the crest search and the
        // back-search, so both are removed to get the true R-to-R interval.
        const float rrInterval = (_rrCounter > backoff)
            ? static_cast<float>(_rrCounter - backoff)
            : static_cast<float>(_rrCounter);

        // The first R-peak after reset has no predecessor, so its "interval"
        // is just time since reset and is discarded.
        const bool havePrev = _rPeaksSeen > 0;
        if (!havePrev) _rPeaksSeen = 1;

        if (havePrev) {
            _rrHistory[_rrHead] = rrInterval;
            _rrHead = (_rrHead + 1) % RR_HISTORY;
            if (_rrFill < RR_HISTORY) _rrFill++;
            _lastRR = rrInterval;
        }
        _rrCounter = backoff;

        // This R-peak closes the held beat's post-RR interval: emit it now.
        // Features match preprocess.py::compute_rr_features exactly.
        if (_holding && havePrev) {
            _holding = false;
            const float pre  = _heldPreRR;
            const float post = rrInterval;
            const float mean = (_heldMeanRR > 1e-3f) ? _heldMeanRR : 1e-3f;

            _rrFeatures[0] = pre / mean;                           // pre_rr_norm
            _rrFeatures[1] = post / mean;                          // post_rr_norm
            _rrFeatures[2] = (post > 1e-3f) ? pre / post : 1.0f;   // ratio_rr

            memcpy(_outWindow, _heldWindow, sizeof(_outWindow));
            _reportSinceR = static_cast<uint32_t>(rrInterval) + backoff;
            _beatReady    = true;
        }

        _rPeakBufPos = (thisPos + BUF - backoff) % BUF;

        // The crest search already collected `backoff` of the post-R samples.
        // A beat is only windowed once it has a valid pre_rr (havePrev), and
        // not while another is still filling (very high HR / noise spikes).
        if (!_pendingBeat && havePrev) {
            // Only start if we have enough pre-samples buffered behind the crest.
            if (_beatBufFill >= SamplingCfg::BEAT_PRE_SAMPLES + backoff) {
                _pendingBeat    = true;
                _postRRemaining = (SamplingCfg::BEAT_POST_SAMPLES > backoff)
                    ? SamplingCfg::BEAT_POST_SAMPLES - backoff
                    : 0;
            }
        }
    }

    return x;
}

// ======================================================================
// isBeatReady
// ======================================================================

bool SignalProcessor::isBeatReady() const {
    return _beatReady;
}

// ======================================================================
// samplesSinceR
// ======================================================================

uint32_t SignalProcessor::samplesSinceR() const {
    return _reportSinceR;
}

// ======================================================================
// getBeat — copy out the reported beat's 128-sample window
// ======================================================================

void SignalProcessor::getBeat(float* out) const {
    memcpy(out, _outWindow, sizeof(_outWindow));
}

// ======================================================================
// getRRFeatures — return the snapshot taken when the beat was completed
// ======================================================================

void SignalProcessor::getRRFeatures(float* rr) const {
    for (size_t i = 0; i < MLCfg::N_RR_FEATURES; ++i) {
        rr[i] = _rrFeatures[i];
    }
}