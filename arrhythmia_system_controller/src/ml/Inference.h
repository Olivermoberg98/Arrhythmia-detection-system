#pragma once
#include "config.h"
#include <stdint.h>

/**
 * @file  Inference.h
 * @brief TensorFlow Lite Micro wrapper for arrhythmia classification.
 *
 * Lifecycle:
 *   1. Call begin() once in setup.
 *   2. When SignalProcessor::isWindowReady(), call getMLWindow() and
 *      pass the buffer to runInference().
 *   3. Read results via getClassification() and getConfidence().
 */

struct InferenceResult {
    MLCfg::Classification classification;
    float confidence;
    bool  valid;   ///< False until at least one inference has run
};

class Inference {
public:
    Inference();

    /**
     * @brief  Initialise TFLite interpreter and allocate tensor arena.
     * @return true on success.
     */
    bool begin();

    /**
     * @brief  Run one inference pass.
     * @param  input  Float array of length ML_WINDOW_SAMPLES, z-score normalised.
     * @return true on success; false on tensor mismatch / interpreter error.
     */
    bool runInference(const float* input);

    InferenceResult getResult() const;

private:
    // TFLite objects are forward-declared to avoid polluting all headers
    // with TFLite includes.  Definitions are in the .cpp.
    struct TFLiteContext;
    TFLiteContext* _ctx = nullptr;

    InferenceResult _result = {};
};