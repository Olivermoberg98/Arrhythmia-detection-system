#pragma once
#include "config.h"
#include <stdint.h>

/**
 * @file  Inference.h
 * @brief TensorFlow Lite Micro wrapper for dual-input arrhythmia classifier.
 *
 * Model inputs:
 *   Input 0 - ECG beat window  float32[1, BEAT_WINDOW_SAMPLES]  (250 samples)
 *   Input 1 - RR features      float32[1, N_RR_FEATURES]        (3 features)
 *
 * Normalisation applied inside runInference() using constants from model_config.h:
 *   ECG : (x[i] - ECG_MEAN) / ECG_STD       -- global scalar scaler
 *   RR  : (x[i] - RR_MEAN[i]) / RR_STD[i]   -- per-feature scaler
 *
 * Per-class thresholds (model_config.h::THRESHOLDS[]):
 *   If the winning class's softmax score < THRESHOLDS[class_id],
 *   InferenceResult::valid is set false (argmax class still returned).
 *
 * Lifecycle:
 *   1. begin()              -- once in setup()
 *   2. runInference(b, rr)  -- each time SignalProcessor::isBeatReady()
 *   3. getResult()          -- read classification / confidence / valid
 */

struct InferenceResult {
    MLCfg::Classification classification;
    float confidence;
    bool  valid;   ///< false if confidence < per-class threshold
};

class Inference {
public:
    Inference();

    /**
     * @brief  Initialise TFLite interpreter and verify tensor shapes.
     * @return true on success; false on schema mismatch or arena too small.
     */
    bool begin();

    /**
     * @brief  Run one inference pass with dual inputs.
     *
     * @param beat  float[BEAT_WINDOW_SAMPLES] -- raw filtered ECG beat from
     *              SignalProcessor::getBeat().  Will be normalised here.
     * @param rr    float[N_RR_FEATURES]       -- RR features from
     *              SignalProcessor::getRRFeatures().  Will be normalised here.
     *
     * @return true on success; false on interpreter error.
     */
    bool runInference(const float* beat, const float* rr);

    /** @brief Return the result of the most recent runInference() call. */
    InferenceResult getResult() const;

private:
    struct TFLiteContext;
    TFLiteContext*  _ctx    = nullptr;
    InferenceResult _result = {};

    void normaliseECG(const float* src, float* dest) const;
    void normaliseRR (const float* src, float* dest) const;
};