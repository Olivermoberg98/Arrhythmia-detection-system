#pragma once
#include <stdint.h>
#include <stddef.h>

/**
 * @file  model.h
 * @brief TFLite Micro model flat-buffer array.
 *
 * ╔═══════════════════════════════════════════════════════════════════╗
 * ║  REPLACE THIS FILE WITH YOUR TRAINED MODEL                        ║
 * ╚═══════════════════════════════════════════════════════════════════╝
 *
 * STEP 1: Train your model in Python and save as .tflite
 * -------------------------------------------------------
 * import tensorflow as tf
 * 
 * # After training your model:
 * converter = tf.lite.TFLiteConverter.from_keras_model(model)
 * converter.optimizations = [tf.lite.Optimize.DEFAULT]  # int8 quantization
 * tflite_model = converter.convert()
 * 
 * with open("arrhythmia_model.tflite", "wb") as f:
 *     f.write(tflite_model)
 *
 * STEP 2: Convert to C array with xxd
 * ------------------------------------
 * From command line:
 *   xxd -i arrhythmia_model.tflite > src/ml/model.h
 *
 * OR with Python:
 *   python3 -c "
 *   data = open('arrhythmia_model.tflite','rb').read()
 *   print('#pragma once')
 *   print('#include <stdint.h>')
 *   print('alignas(8) const unsigned char g_model[] = {')
 *   print(','.join(hex(b) for b in data))
 *   print('};')
 *   print(f'const unsigned int g_model_len = {len(data)};')
 *   " > src/ml/model.h
 *
 * STEP 3: Verify expected I/O shapes in config.h match your model
 * ----------------------------------------------------------------
 * Input:  [1, ML_WINDOW_SAMPLES] float32  (1250 samples = 5 seconds @ 250 Hz)
 * Output: [1, NUM_CLASSES] float32         (5 classes: Normal, AFib, Brady, Tachy, Other)
 */

// Declarations — replace the body below with xxd output
alignas(8) extern const unsigned char g_model[];
extern const unsigned int             g_model_len;

// ╔═══════════════════════════════════════════════════════════════════╗
// ║  TEMPORARY STUB — DELETE THIS BLOCK AFTER ADDING YOUR MODEL       ║
// ╚═══════════════════════════════════════════════════════════════════╝
// This minimal stub allows compilation without a real model.
// Inference will always return false until you replace this with a real .tflite.

alignas(8) const unsigned char g_model[] = {
    0x1c, 0x00, 0x00, 0x00, 0x54, 0x46, 0x4c, 0x33,  // Minimal valid TFLite header
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0e, 0x00,
    0x18, 0x00, 0x04, 0x00, 0x08, 0x00, 0x0c, 0x00,
    0x10, 0x00, 0x14, 0x00, 0x0e, 0x00, 0x00, 0x00
};
const unsigned int g_model_len = 32;