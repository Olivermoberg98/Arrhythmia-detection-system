#pragma once
/**
 * @file  model.h
 * @brief TFLite Micro model entry point for the dual-input ECG classifier.
 *
 * This file ties together the two auto-generated headers produced by
 * extract_constants.py:
 *
 *   model_data.h   — the g_model[] flat-buffer C array (from xxd / Python)
 *   model_config.h — normalisation constants and per-class thresholds
 *
 * ═══════════════════════════════════════════════════════════════════════
 *  HOW TO REGENERATE AFTER RETRAINING
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  STEP 1 — Convert .keras → INT8 TFLite
 *  ─────────────────────────────────────
 *  python tools/convert_model.py \
 *      --model   models/trained/model_XXXXXX.keras \
 *      --meta    data/processed/metadata.pkl \
 *      --ecg     data/processed/X_ecg_val.npy \
 *      --rr      data/processed/X_rr_val.npy \
 *      --out     src/ml/arrhythmia_model.tflite
 *
 *  STEP 2+3 — C-array + constants
 *  ────────────────────────────────
 *  python tools/extract_constants.py \
 *      --tflite      src/ml/arrhythmia_model.tflite \
 *      --meta        data/processed/metadata.pkl \
 *      --ecg_scaler  data/processed/ecg_scaler.pkl \
 *      --rr_scaler   data/processed/rr_scaler.pkl \
 *      --results     models/trained/results_XXXXXX.pkl \
 *      --out_data    src/ml/model_data.h \
 *      --out_cfg     src/ml/model_config.h
 *
 *  The two generated files are #included below — no other changes needed.
 *
 * ═══════════════════════════════════════════════════════════════════════
 *  MODEL I/O CONTRACT (must match the trained model)
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  Input 0 — ECG beat window
 *    Shape : [1, BEAT_LENGTH]  = [1, 250]
 *    Type  : float32
 *    Range : z-score normalised using ecg_scaler (element-wise)
 *
 *  Input 1 — RR interval features
 *    Shape : [1, N_RR_FEATURES] = [1, 3]
 *    Type  : float32
 *    Content (in order):
 *      [0] pre_rr_norm  — pre-beat RR interval  / mean_rr
 *      [1] post_rr_norm — post-beat RR interval / mean_rr
 *      [2] ratio_rr     — pre_rr / post_rr
 *    Range : z-score normalised using rr_scaler (per-feature)
 *
 *  Output — Class probabilities
 *    Shape : [1, NUM_CLASSES] = [1, 3]
 *    Type  : float32  (softmax)
 *    Index : 0=Normal, 1=SVEB, 2=VEB
 *
 * ═══════════════════════════════════════════════════════════════════════
 *  TENSOR ARENA SIZING GUIDANCE
 * ═══════════════════════════════════════════════════════════════════════
 *
 *  The tensor arena must be large enough for all intermediate activations.
 *  Start with TFLITE_TENSOR_ARENA_SIZE = 60000 (already in platformio.ini).
 *  If AllocateTensors() returns kTfLiteError, increase by 10 kB increments.
 *  On success, call interpreter->arena_used_bytes() and trim to that value
 *  + ~4 kB margin.
 *
 *  Current setting in platformio.ini: -DTFLITE_TENSOR_ARENA_SIZE=60000
 */

// ── Auto-generated outputs from extract_constants.py ────────────────────────
// Both files must exist before building.  Run extract_constants.py first.
#include "model_data.h"    // g_model[], g_model_len
#include "model_config.h"  // namespace ModelCfg — all constants + thresholds