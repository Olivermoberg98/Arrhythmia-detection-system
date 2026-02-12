#pragma once
#include <stdint.h>
#include <cstddef>

// ============================================================
// Pin Assignments
// ============================================================
namespace Pins {
    // AD8232 ECG
    constexpr int ECG_OUTPUT   = 34;   // Analog input (ADC1_CH6) – must be ADC1
    constexpr int ECG_LO_PLUS  = 32;   // Lead-off detect +
    constexpr int ECG_LO_MINUS = 33;   // Lead-off detect -

    // MAX30102 – I2C (shared bus)
    constexpr int I2C_SDA      = 21;
    constexpr int I2C_SCL      = 22;
}

// ============================================================
// Sampling Configuration
// ============================================================
namespace SamplingCfg {
    // ECG -------------------------------------------------------
    // AD8232 analogue output → ESP32 ADC sampled by hardware timer.
    constexpr uint32_t ECG_SAMPLE_RATE_HZ    = 250;   // Hz
    constexpr uint32_t ECG_TIMER_PERIOD_US   = 1000000 / ECG_SAMPLE_RATE_HZ; // 4000 µs

    // ADC resolution on ESP32 is 12-bit (0–4095).
    // Centre (~1.65 V) is ~2048.  We store as int16_t offset from 2048.
    constexpr int ADC_MIDPOINT = 2048;

    // PPG -------------------------------------------------------
    // MAX30102 internal sampling rate (register value 100 sps).
    constexpr uint32_t PPG_SAMPLE_RATE_HZ    = 100;

    // ---- Buffer sizes -----------------------------------------
    // BLE batch: 10 ECG samples @ 250 Hz = 40 ms per packet.
    constexpr size_t BLE_ECG_BATCH_SIZE      = 10;

    // Circular buffer for ECG → BLE path:  200 ms headroom.
    constexpr size_t ECG_BLE_BUFFER_SIZE     = 50;

    // ML sliding window: 5 seconds of ECG at 250 Hz.
    constexpr size_t ML_WINDOW_SAMPLES       = 1250;

    // ML inference stride: 50 % overlap → re-run every 2.5 s.
    constexpr size_t ML_STRIDE_SAMPLES       = 625;

    // Serial data-capture ring buffer (debug build only).
    constexpr size_t SERIAL_BUFFER_SIZE      = 512;
}

// ============================================================
// Signal Processing
// ============================================================
namespace FilterCfg {
    // All coefficients are for fs = 250 Hz, direct-form II biquads.
    // Coefficients stored as: [b0, b1, b2, a1, a2]
    // (a0 normalised to 1; sign convention: y = b*x - a*y_prev)

    // 2nd-order Butterworth high-pass  fc = 0.5 Hz
    constexpr float HP_B[3] = { 0.99115f, -1.98230f,  0.99115f };
    constexpr float HP_A[2] = {-1.98222f,  0.98232f };

    // 2nd-order Butterworth low-pass   fc = 40 Hz
    constexpr float LP_B[3] = { 0.14413f,  0.28826f,  0.14413f };
    constexpr float LP_A[2] = {-0.67736f,  0.25390f };

    // 2nd-order IIR notch              f0 = 50 Hz, Q ≈ 30
    constexpr float NOTCH_B[3] = { 1.0f,    -0.61800f, 1.0f     };
    constexpr float NOTCH_A[2] = {-0.59484f,  0.92598f };

    // Enable / disable individual stages
    constexpr bool ENABLE_HP    = true;
    constexpr bool ENABLE_LP    = true;
    constexpr bool ENABLE_NOTCH = true;   // set false for 60 Hz regions
}

// ============================================================
// ML / Inference
// ============================================================
namespace MLCfg {
    // Input normalisation: z-score over the window.
    // Change to match how your training data was normalised.
    constexpr float INPUT_MEAN  = 0.0f;
    constexpr float INPUT_STD   = 1.0f;

    // Output classes (must match your model's output layer)
    enum Classification : uint8_t {
        NORMAL      = 0,
        AFIB        = 1,
        BRADYCARDIA = 2,
        TACHYCARDIA = 3,
        OTHER       = 4
    };
    constexpr uint8_t NUM_CLASSES = 5;

    // Confidence threshold – below this → report UNKNOWN
    constexpr float CONFIDENCE_THRESHOLD = 0.70f;
}

// ============================================================
// BLE Configuration & Wire Protocol
// ============================================================
namespace BLECfg {
    // ---- GATT UUIDs -------------------------------------------
    constexpr const char* SERVICE_UUID        = "4fafc201-1fb5-459e-8fcc-c5c9c331914b";

    // Notify: ECG + PPG stream packets  (see DataPacket below)
    constexpr const char* CHAR_STREAM_UUID    = "beb5483e-36e1-4688-b7f5-ea07361b26a8";

    // Notify: Arrhythmia inference results (see InferencePacket below)
    constexpr const char* CHAR_INFER_UUID     = "beb5483f-36e1-4688-b7f5-ea07361b26a8";

    // Read/Write: device status & configuration
    constexpr const char* CHAR_CONFIG_UUID    = "beb54840-36e1-4688-b7f5-ea07361b26a8";

    // ---- Timing -----------------------------------------------
    // BLE connection interval (multiples of 1.25 ms).
    // 32 = 40 ms.  At 250 Hz ECG, 10 samples accumulate in 40 ms.
    constexpr uint16_t CONN_INTERVAL_MIN = 24;   // 30 ms
    constexpr uint16_t CONN_INTERVAL_MAX = 32;   // 40 ms

    // ---- Device name ------------------------------------------
    constexpr const char* DEVICE_NAME = "ArrhythmiaMonitor";

    // ===========================================================
    // Wire Protocol
    // ===========================================================
    //
    // STREAM CHARACTERISTIC  –  DataPacket  (26 bytes)
    // ┌─────────┬──────────────────────┬──────────────────────────────────────────┐
    // │ Byte(s) │ Field                │ Description                              │
    // ├─────────┼──────────────────────┼──────────────────────────────────────────┤
    // │   0     │ seq_num   (uint8)    │ Rolling 0-255 counter, detect packet loss│
    // │  1-4    │ timestamp (uint32LE) │ millis() at first sample in this batch   │
    // │   5     │ lead_off  (uint8)    │ Bit-0 = LO+, Bit-1 = LO-. 0 = OK        │
    // │  6-25   │ ecg[10]   (int16LE) │ Filtered ECG samples (raw – ADC_MIDPOINT)│
    // └─────────┴──────────────────────┴──────────────────────────────────────────┘
    //
    // Effective ECG rate on the app side: 10 samples / 40 ms = 250 Hz.
    // Render each sample 4 ms apart for correct waveform scaling.
    //
    // INFERENCE CHARACTERISTIC  –  InferencePacket  (10 bytes)
    // ┌─────────┬──────────────────────┬──────────────────────────────────────────┐
    // │   0     │ marker    (uint8)    │ Always 0xFF                              │
    // │  1-4    │ timestamp (uint32LE) │ millis() when inference was run          │
    // │   5     │ class_id  (uint8)    │ MLCfg::Classification enum value         │
    // │  6-9    │ confidence(float32LE)│ Softmax probability of class_id          │
    // └─────────┴──────────────────────┴──────────────────────────────────────────┘

    #pragma pack(push, 1)
    struct DataPacket {
        uint8_t  seq_num;
        uint32_t timestamp_ms;
        uint8_t  lead_off;        // Bit-0 = LO+, Bit-1 = LO-. 0 = leads on
        int16_t  ecg[10];         // BLE_ECG_BATCH_SIZE filtered samples
    };

    struct InferencePacket {
        uint8_t  marker;          // 0xFF
        uint32_t timestamp_ms;
        uint8_t  class_id;
        float    confidence;
    };
    #pragma pack(pop)
}

// ============================================================
// Serial Data Capture (debug builds only)
// ============================================================
namespace SerialCfg {
    constexpr uint32_t BAUD_RATE = 921600;
    // CSV header: timestamp_ms,ecg_raw,ecg_filtered
}