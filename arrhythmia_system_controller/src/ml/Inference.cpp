#include "Inference.h"
#include "model.h"

// TFLite Micro headers (TensorFlowLite_ESP32 library)
#include <TensorFlowLite_ESP32.h>
#include "tensorflow/lite/micro/all_ops_resolver.h"
#include "tensorflow/lite/micro/micro_error_reporter.h"
#include "tensorflow/lite/micro/micro_interpreter.h"
#include "tensorflow/lite/schema/schema_generated.h"

// ---- Tensor arena ----------------------------------------------------
// Allocated in DRAM.  Tune TFLITE_TENSOR_ARENA_SIZE in platformio.ini.
// Use ESP32 PSRAM if available (define USE_PSRAM in build flags).
static uint8_t s_tensorArena[TFLITE_TENSOR_ARENA_SIZE];

// ---- TFLite context --------------------------------------------------
struct Inference::TFLiteContext {
    tflite::MicroErrorReporter   reporter;
    tflite::AllOpsResolver       resolver;
    const tflite::Model*         model   = nullptr;
    tflite::MicroInterpreter*    interp  = nullptr;
    TfLiteTensor*                input   = nullptr;
    TfLiteTensor*                output  = nullptr;
};

// -------------------------------------------------------------------
Inference::Inference() {
    _ctx = new TFLiteContext();
}

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

    _ctx->input  = _ctx->interp->input(0);
    _ctx->output = _ctx->interp->output(0);

    // Sanity-check tensor shapes against compile-time constants
    if (_ctx->input->dims->data[1] != (int)SamplingCfg::ML_WINDOW_SAMPLES) {
        return false;
    }
    if (_ctx->output->dims->data[1] != (int)MLCfg::NUM_CLASSES) {
        return false;
    }

    return true;
}

bool Inference::runInference(const float* input) {
    if (!_ctx->input || !_ctx->output) return false;

    // Copy normalised samples into the input tensor
    float* dest = _ctx->input->data.f;
    for (size_t i = 0; i < SamplingCfg::ML_WINDOW_SAMPLES; ++i) {
        dest[i] = input[i];
    }

    if (_ctx->interp->Invoke() != kTfLiteOk) return false;

    // Find argmax of softmax output
    const float* scores = _ctx->output->data.f;
    uint8_t bestClass   = 0;
    float   bestScore   = scores[0];
    for (uint8_t c = 1; c < MLCfg::NUM_CLASSES; ++c) {
        if (scores[c] > bestScore) {
            bestScore = scores[c];
            bestClass = c;
        }
    }

    _result.classification = static_cast<MLCfg::Classification>(bestClass);
    _result.confidence     = bestScore;
    _result.valid          = (bestScore >= MLCfg::CONFIDENCE_THRESHOLD);

    return true;
}

InferenceResult Inference::getResult() const {
    return _result;
}