#pragma once
#include <stdint.h>
#include <stddef.h>

/**
 * @file  model.h
 * @brief Placeholder for the TFLite Micro model flat-buffer.
 *
 * ---- How to replace this with your real model ----
 *
 *  1. After training in Python, save as:
 *       converter = tf.lite.TFLiteConverter.from_keras_model(model)
 *       converter.optimizations = [tf.lite.Optimize.DEFAULT]  # int8 quant
 *       tflite_model = converter.convert()
 *       with open("arrhythmia_model.tflite", "wb") as f:
 *           f.write(tflite_model)
 *
 *  2. Convert to a C array:
 *       xxd -i arrhythmia_model.tflite > src/ml/model.h
 *     OR use Python:
 *       python3 -c "
 *       data = open('arrhythmia_model.tflite','rb').read()
 *       print('const unsigned char g_model[] = {' + ','.join(hex(b) for b in data) + '};')
 *       print(f'const unsigned int g_model_len = {len(data)};')
 *       " > src/ml/model.h
 *
 *  3. Add the extern declarations below and delete this comment block.
 *
 * ---- Expected model I/O ----
 *   Input  tensor: shape [1, ML_WINDOW_SAMPLES] float32, z-score normalised
 *   Output tensor: shape [1, NUM_CLASSES]       float32, softmax probabilities
 *
 * ---- Alignment note ----
 *   TFLite Micro requires the model buffer to be 8-byte aligned.
 *   The attribute below ensures this even after xxd conversion.
 */

// Stub – replace the body with the xxd output
alignas(8) extern const unsigned char g_model[];
extern const unsigned int             g_model_len;

#ifdef ARRHYTHMIA_MODEL_STUB
// Minimal valid flat-buffer stub so the project compiles without a real model.
// Remove this block once you have a real model.
alignas(8) const unsigned char g_model[] = { 0x00 };
const unsigned int             g_model_len = 1;
#define ARRHYTHMIA_MODEL_STUB
#endif
