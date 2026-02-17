#include "Inference.h"
#include "model.h"          // -> model_data.h (g_model[]) + model_config.h (ModelCfg::)

// TFLite Micro headers (TensorFlowLite_ESP32 library)
#include <TensorFlowLite_ESP32.h>
#include "tensorflow/lite/micro/all_ops_resolver.h"
#include "tensorflow/lite/micro/micro_error_reporter.h"
#include "tensorflow/lite/micro/micro_interpreter.h"
#include "tensorflow/lite/schema/schema_generated.h"

#include <cmath>
#include <cstring>
#include <algorithm>

// ======================================================================
// Tensor arena
// Tune TFLITE_TENSOR_ARENA_SIZE in platformio.ini if AllocateTensors fails.
// After a successful build call interpreter->arena_used_bytes() and trim
// to that value + ~4 kB margin.
// ======================================================================
static uint8_t s_tensorArena[TFLITE_TENSOR_ARENA_SIZE];

// ======================================================================
// TFLite context (forward-declared in header to keep TFLite out of all headers)
// ======================================================================
struct Inference::TFLiteContext {
    tflite::MicroErrorReporter  reporter;
    tflite::AllOpsResolver      resolver;
    const tflite::Model*        model   = nullptr;
    tflite::MicroInterpreter*   interp  = nullptr;

    // We store both input tensor pointers by matching their shape at begin().
    TfLiteTensor* inputECG  = nullptr;   // shape [1, BEAT_WINDOW_SAMPLES]
    TfLiteTensor* inputRR   = nullptr;   // shape [1, N_RR_FEATURES]
    TfLiteTensor* output    = nullptr;   // shape [1, NUM_CLASSES]
};

// ======================================================================
// Constructor
// ======================================================================
Inference::Inference() {
    _ctx = new TFLiteContext();
}

// ======================================================================
// begin()
// ======================================================================
bool Inference::begin() {
    _ctx->model = tflite::GetModel(g_model);
    if (_ctx->model->version() != TFLITE_SCHEMA_VERSION) {
        return false;
    }

    _ctx->interp = new tflite::MicroInterpreter(
        _ctx->model,
        _ctx->resolver,
        s_tensorArena,
        TFLITE_TENSOR_ARENA_SIZE,
        &_ctx->reporter
    );

    if (_ctx->interp->AllocateTensors() != kTfLiteOk) {
        return false;
    }

    // ------------------------------------------------------------------ //
    // Map input tensors by shape.
    // The model has two inputs; their order in the flat-buffer may vary
    // depending on how the model was built, so we match by feature count
    // rather than assuming index 0 = ECG.
    // ------------------------------------------------------------------ //
    const int nInputs = static_cast<int>(_ctx->interp->inputs().size());
    for (int i = 0; i < nInputs; ++i) {
        TfLiteTensor* t = _ctx->interp->input(i);
        if (t->dims->size < 2) continue;

        const int dim1 = t->dims->data[1];
        if (dim1 == static_cast<int>(ModelCfg::BEAT_LENGTH)) {
            _ctx->inputECG = t;
        } else if (dim1 == static_cast<int>(ModelCfg::N_RR_FEATURES)) {
            _ctx->inputRR = t;
        }
    }

    if (!_ctx->inputECG || !_ctx->inputRR) {
        // Could not match tensors — shapes don't match model_config.h
        return false;
    }

    _ctx->output = _ctx->interp->output(0);
    if (!_ctx->output) return false;

    if (_ctx->output->dims->data[1] != static_cast<int>(ModelCfg::NUM_CLASSES)) {
        return false;
    }

    return true;
}

// ======================================================================
// Normalisation helpers
// ======================================================================

void Inference::normaliseECG(const float* src, float* dest) const {
    // Global scalar scaler: (x - ECG_MEAN) / ECG_STD
    const float mean = ModelCfg::ECG_MEAN;
    const float std  = ModelCfg::ECG_STD + ModelCfg::STD_EPS;

    for (size_t i = 0; i < ModelCfg::BEAT_LENGTH; ++i) {
        dest[i] = (src[i] - mean) / std;
    }
}

void Inference::normaliseRR(const float* src, float* dest) const {
    // Per-feature scaler: (x[i] - RR_MEAN[i]) / RR_STD[i]
    for (size_t i = 0; i < ModelCfg::N_RR_FEATURES; ++i) {
        const float std = ModelCfg::RR_STD[i] + ModelCfg::STD_EPS;

        // Clamp to ±RR_CLAMP_SIGMA before normalising to limit outlier influence
        float val = src[i];
        const float lo = ModelCfg::RR_MEAN[i] - MLCfg::RR_CLAMP_SIGMA * ModelCfg::RR_STD[i];
        const float hi = ModelCfg::RR_MEAN[i] + MLCfg::RR_CLAMP_SIGMA * ModelCfg::RR_STD[i];
        val = std::max(lo, std::min(hi, val));

        dest[i] = (val - ModelCfg::RR_MEAN[i]) / std;
    }
}

// ======================================================================
// runInference()
// ======================================================================
bool Inference::runInference(const float* beat, const float* rr) {
    if (!_ctx->inputECG || !_ctx->inputRR || !_ctx->output) return false;

    // -- Normalise ECG -> input tensor 0 ----------------------------------
    normaliseECG(beat, _ctx->inputECG->data.f);

    // -- Normalise RR -> input tensor 1 -----------------------------------
    normaliseRR(rr, _ctx->inputRR->data.f);

    // -- Run interpreter --------------------------------------------------
    if (_ctx->interp->Invoke() != kTfLiteOk) return false;

    // -- Read softmax output ----------------------------------------------
    const float*  scores   = _ctx->output->data.f;
    uint8_t       bestClass = 0;
    float         bestScore = scores[0];

    for (uint8_t c = 1; c < ModelCfg::NUM_CLASSES; ++c) {
        if (scores[c] > bestScore) {
            bestScore = scores[c];
            bestClass = c;
        }
    }

    _result.classification = static_cast<MLCfg::Classification>(bestClass);
    _result.confidence     = bestScore;
    // Apply per-class threshold from model_config.h
    _result.valid = (bestScore >= ModelCfg::THRESHOLDS[bestClass]);

    return true;
}

// ======================================================================
// getResult()
// ======================================================================
InferenceResult Inference::getResult() const {
    return _result;
}