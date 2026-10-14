#pragma once
#include <stdint.h>
#include <cstddef>

// ============================================================
// Pin Assignments
// ============================================================
namespace Pins {
    // AD8232 ECG - ESP32-S3: ADC1 is GPIO1-10 only; GPIO4-10 are taken by the
    // camera DVP bus and GPIO26-37 by flash + octal PSRAM.
    constexpr int ECG_OUTPUT   = 1;    // Analog input (ADC1_CH0) - must be ADC1
    constexpr int ECG_LO_PLUS  = 42;   // Lead-off detect +
    constexpr int ECG_LO_MINUS = 41;   // Lead-off detect -

    // Must match ECG_OUTPUT: GPIO1 = ADC1_CH0 on ESP32-S3.
    constexpr int ECG_ADC_CHANNEL = 0;
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
    constexpr size_t BEAT_PRE_SAMPLES    = 96;
    constexpr size_t BEAT_POST_SAMPLES   = 32;
    constexpr size_t BEAT_WINDOW_SAMPLES = BEAT_PRE_SAMPLES + BEAT_POST_SAMPLES; // 128

    // Consecutive lead-off samples before the signal chain is torn down.
    // The AD8232's DC leads-off comparators chatter on marginal electrode
    // contact — a real recording showed 18 dropouts in 3.5 s, nearly all a
    // single sample. Resetting on each one wipes the filters, beat buffer, RR
    // history and adaptive threshold, so no beat ever survives to completion.
    // Shorter glitches are ridden out by holding the last good sample.
    constexpr uint32_t LEAD_OFF_DEBOUNCE_SAMPLES = 62;   // 250 ms @ 250 Hz

    // Ring buffer for beat extraction — must hold ≥ BEAT_PRE_SAMPLES.
    constexpr size_t ECG_BEAT_BUFFER_SIZE = 512;

    // Aliases kept for SignalProcessor / main.cpp compatibility.
    constexpr size_t ML_WINDOW_SAMPLES  = BEAT_WINDOW_SAMPLES; // 128
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

    // Samples to keep searching for the MWI crest after the threshold is
    // crossed. The threshold must be derived from the crest, not from the
    // crossing, or each detection lowers the threshold and the detector
    // ratchets down onto T waves.
    constexpr uint32_t PT_SEARCH_SAMPLES = 25;    // 100 ms @ 250 Hz

    // Running peak estimates: new = W * peak + (1 - W) * old.
    constexpr float PT_PEAK_WEIGHT = 0.125f;

    // Detection threshold = npki + FRAC * (spki - npki), floored at
    // FLOOR_FRAC * spki so a run of noise updates cannot drag it down to
    // where T waves qualify.
    constexpr float PT_THRESHOLD_FRAC  = 0.25f;
    constexpr float PT_THRESHOLD_FLOOR = 0.15f;

    // The MWI crest lags the R-peak by the integrator's group delay: half of
    // the 32-sample window plus the 5-point derivative. The beat window is
    // defined relative to the R-peak, so the crest is shifted back by that
    // delay and then snapped to the filtered signal's dominant deflection.
    constexpr uint32_t PT_MWI_GROUP_DELAY = 18;   // 16 (MWI/2) + 2 (derivative)

    // Half-width of the snap search, either side of the compensated crest.
    // Kept under half a QRS width so it cannot reach the T wave.
    constexpr uint32_t PT_RPEAK_SEARCH_HALF = 15;   // 60 ms @ 250 Hz
}

// ============================================================
// ML contract (inference runs in the app; these must match training)
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

    // RR features fed to model input 1: [pre_rr_norm, post_rr_norm, ratio_rr]
    constexpr uint8_t N_RR_FEATURES   = 3;
    // Mean RR = mean of the last RR_MEAN_WINDOW intervals up to pre_rr.
    // Must match preprocess.py RR_MEAN_WINDOW.
    constexpr size_t  RR_MEAN_WINDOW  = 8;
}

// ============================================================
// BLE Configuration & Wire Protocol
// ============================================================
namespace BLECfg {
    // ---- GATT UUIDs -------------------------------------------
    constexpr const char* SERVICE_UUID        = "4fafc201-1fb5-459e-8fcc-c5c9c331914b";
    constexpr const char* CHAR_STREAM_UUID    = "beb5483e-36e1-4688-b7f5-ea07361b26a8";
    constexpr const char* CHAR_BEAT_UUID      = "beb5483f-36e1-4688-b7f5-ea07361b26a8";
    constexpr const char* CHAR_CONFIG_UUID    = "beb54840-36e1-4688-b7f5-ea07361b26a8";

    // ---- Timing -----------------------------------------------
    constexpr uint16_t CONN_INTERVAL_MIN = 24;   // 30 ms
    constexpr uint16_t CONN_INTERVAL_MAX = 32;   // 40 ms
    constexpr uint16_t ADV_INTERVAL_MIN = 160;  // 100 ms  (160 × 0.625 ms)
    constexpr uint16_t ADV_INTERVAL_MAX = 320;  // 200 ms  (320 × 0.625 ms)

    // ---- Device name ------------------------------------------
    constexpr const char* DEVICE_NAME = "EcgMon";

    #pragma pack(push, 1)
    struct DataPacket {
        uint8_t  seq_num;
        uint32_t timestamp_ms;
        uint8_t  lead_off;        // Bit-0 = LO+, Bit-1 = LO-. 0 = leads on
        int16_t  ecg[10];         // BLE_ECG_BATCH_SIZE filtered samples
    };

    struct BeatPacket {
        uint8_t  marker;          // 0xBE (Beat marker)
        uint32_t timestamp_ms;    // R-peak timestamp
        uint16_t beat_num;        // Rolling beat counter
        int16_t    ecg[128];      // Full filtered beat window
        float    rr_features[3];  // [pre_rr_norm, post_rr_norm, ratio]
    };
    // Size: 1 + 4 + 2 + 256 + 12 = 275 
    #pragma pack(pop)
}

// ============================================================
// Serial Data Capture (debug builds only)
// ============================================================
namespace SerialCfg {
    constexpr uint32_t BAUD_RATE = 921600;
}