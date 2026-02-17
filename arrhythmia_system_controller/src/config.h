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
}

// ============================================================
// Sampling Configuration
// ============================================================
namespace SamplingCfg {
    // ECG -------------------------------------------------------
    // AD8232 analogue output → ESP32 ADC sampled by hardware timer.
    constexpr uint32_t ECG_SAMPLE_RATE_HZ    = 250;   // Hz
    constexpr uint32_t ECG_TIMER_PERIOD_US   = 1000000 / ECG_SAMPLE_RATE_HZ; // 4000 µs
    constexpr int      ADC_MIDPOINT          = 2048;

    // BLE stream batching — UNCHANGED
    constexpr size_t BLE_ECG_BATCH_SIZE  = 10;
    constexpr size_t ECG_BLE_BUFFER_SIZE = 50;

    // ── Beat segmentation ──────────────────────────────────────
    // 187 samples before R-peak + 63 after = 250 samples total @ 250 Hz.
    constexpr size_t BEAT_PRE_SAMPLES    = 187;
    constexpr size_t BEAT_POST_SAMPLES   = 63;
    constexpr size_t BEAT_WINDOW_SAMPLES = BEAT_PRE_SAMPLES + BEAT_POST_SAMPLES; // 250

    // Ring buffer for beat extraction — must hold ≥ BEAT_PRE_SAMPLES.
    constexpr size_t ECG_BEAT_BUFFER_SIZE = 512;

    // Aliases kept for SignalProcessor / main.cpp compatibility.
    constexpr size_t ML_WINDOW_SAMPLES  = BEAT_WINDOW_SAMPLES; // 250
    constexpr size_t ML_STRIDE_SAMPLES  = BEAT_WINDOW_SAMPLES; // fire on each beat

    constexpr size_t SERIAL_BUFFER_SIZE = 512;
}

// ============================================================
// Signal Processing & R-peak detection
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

    constexpr bool ENABLE_HP    = true;
    constexpr bool ENABLE_LP    = true;
    constexpr bool ENABLE_NOTCH = true;

    // Pan-Tompkins R-peak detector
    constexpr uint32_t REFRACTORY_SAMPLES = 50;   // 200 ms @ 250 Hz
    constexpr float    PT_THRESHOLD_INIT  = 500.0f;
    constexpr float    PT_THRESHOLD_DECAY = 0.995f;
}

// ============================================================
// ML / Inference — UPDATED for dual-input 3-class model
// ============================================================
namespace MLCfg {

    // Output classes — 0=Normal, 1=SVEB, 2=VEB
    enum Classification : uint8_t {
        NORMAL           = 0,
        SUPRAVENTRICULAR = 1,
        VENTRICULAR      = 2,
    };
    constexpr uint8_t NUM_CLASSES = 3;

    constexpr const char* CLASS_NAMES[NUM_CLASSES] = {
        "Normal",
        "Supraventricular",
        "Ventricular",
    };

    // Per-class thresholds — tuned on validation set (macro F1 = 0.772).
    // Real values are baked into model_config.h by extract_constants.py;
    // these defaults compile when model_config.h is not yet generated.
    constexpr float DEFAULT_THRESHOLDS[NUM_CLASSES] = {
        0.234f,  // Normal
        0.050f,  // Supraventricular
        0.050f,  // Ventricular
    };

    // RR features fed to model input 1: [pre_rr_norm, post_rr_norm, ratio_rr]
    constexpr uint8_t N_RR_FEATURES   = 3;
    constexpr size_t  RR_MEAN_WINDOW  = 8;    // beats used for running mean RR
    constexpr float   RR_CLAMP_SIGMA  = 4.0f; // outlier clamp before normalisation
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

    constexpr uint16_t ADV_INTERVAL_MIN = 160;  // 100 ms  (160 × 0.625 ms)
    constexpr uint16_t ADV_INTERVAL_MAX = 320;  // 200 ms  (320 × 0.625 ms)

    // ---- Device name ------------------------------------------
    constexpr const char* DEVICE_NAME = "EcgMon";

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
    // class_id meaning (InferencePacket byte 5):
    //   Was: 0=Normal, 1=AFib, 2=Brady, 3=Tachy, 4=Other
    //   Now: 0=Normal, 1=Supraventricular (SVEB), 2=Ventricular (VEB)

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
}