#include "Arduino.h"
#include "config.h"
#include "sensors/ecg/ECGSensor.h"
#include "signal_processing/SignalProcessor.h"
#include "ml/Inference.h"
#include "ble/BLEManager.h"

// ============================================================
// Module instances
// ============================================================
static ECGSensor      ecg;
static SignalProcessor processor;
static Inference      inference;
static BLEManager     ble;

// ============================================================
// Shared state (inter-task, protected by a mutex)
// ============================================================
static volatile uint8_t  s_bleSeqNum  = 0;

// ML window buffer (allocated once, reused across inference calls)
static float s_mlInputBuf[SamplingCfg::ML_WINDOW_SAMPLES];

// ============================================================
// Sensor Task  (Core 1 – sampling + signal processing)
// ============================================================
static void sensorTask(void* /*param*/) {
    // Serial data-capture header (debug builds only)
#if ENABLE_SERIAL_CAPTURE
    Serial.println("timestamp_ms,ecg_raw,ecg_filtered");
#endif

    // Accumulate ECG samples for the next BLE packet
    int16_t ecgBatch[SamplingCfg::BLE_ECG_BATCH_SIZE];
    size_t  batchCount = 0;

    for (;;) {
        const uint32_t now = millis();


        // ---- ECG: drain available samples from circular buffer ----
        ecg.update();

        while (ecg.available() > 0) {
            int16_t raw = 0;
            if (ecg.popSamples(&raw, 1) == 0) break;

            // Signal processing (filter + ML window)
            float filtered = 0.0f;
            bool  leadOff  = (raw == INT16_MIN);

            if (!leadOff) {
                filtered = processor.processSample(raw);
            } else {
                processor.reset();   // Reset filters on lead-off event
            }

            // ---- Serial capture (debug build) -----------------
#if ENABLE_SERIAL_CAPTURE
            Serial.printf("%lu,%d,%.2f\n", now, (int)raw, filtered);
#endif

            // ---- Accumulate into BLE batch --------------------
            if (!leadOff) {
                ecgBatch[batchCount++] = static_cast<int16_t>(filtered);
            }

            // Once batch is full, notify BLE task via queue
            if (batchCount == SamplingCfg::BLE_ECG_BATCH_SIZE) {
                BLECfg::DataPacket pkt = {};
                pkt.seq_num      = s_bleSeqNum++;
                pkt.timestamp_ms = now;
                pkt.lead_off     = ecg.leadsOn() ? 0x00 : 0x03;

                memcpy(pkt.ecg, ecgBatch,
                       sizeof(int16_t) * SamplingCfg::BLE_ECG_BATCH_SIZE);

                ble.sendStream(pkt);
                batchCount = 0;
            }
        }

        // ---- ML inference (triggered by stride counter) -------
        if (processor.isWindowReady()) {
            processor.getMLWindow(s_mlInputBuf);
            if (inference.runInference(s_mlInputBuf)) {
                InferenceResult r = inference.getResult();
                if (r.valid) {
                    BLECfg::InferencePacket ipkt = {};
                    ipkt.marker       = 0xFF;
                    ipkt.timestamp_ms = millis();
                    ipkt.class_id     = static_cast<uint8_t>(r.classification);
                    ipkt.confidence   = r.confidence;
                    ble.sendInference(ipkt);

#if ENABLE_SERIAL_CAPTURE
                    Serial.printf("[INFER] class=%u conf=%.2f\n",
                                  ipkt.class_id, ipkt.confidence);
#endif
                }
            }
        }

        // Yield briefly to avoid starving the idle task / BLE stack
        vTaskDelay(1 / portTICK_PERIOD_MS);
    }
}

// ============================================================
// setup / loop
// ============================================================
void setup() {
#if ENABLE_SERIAL_CAPTURE
    Serial.begin(SerialCfg::BAUD_RATE);
    while (!Serial) { delay(10); }
#endif

    // ---- Initialise modules -----------------------------------
    if (!ecg.begin()) {
        // ECG init failure – halt
        while (true) { delay(1000); }
    }

    if (!inference.begin()) {
        // Inference init failure – continue without ML
    }

    if (!ble.begin()) {
        // BLE failure – halt
        while (true) { delay(1000); }
    }

    // ---- Launch sensor task on Core 1 -------------------------
    xTaskCreatePinnedToCore(
        sensorTask,
        "SensorTask",
        SENSOR_TASK_STACK,
        nullptr,
        /*priority=*/ 5,
        nullptr,
        /*core=*/     1
    );
    // BLE stack runs on Core 0 (managed by NimBLE internals).
    // No separate BLE task needed – NimBLE handles its own threading.
}

void loop() {
    // Intentionally empty: all work is in FreeRTOS tasks.
    vTaskDelay(portMAX_DELAY);
}