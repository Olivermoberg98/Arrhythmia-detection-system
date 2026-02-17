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
// Shared state
// ============================================================
static volatile uint8_t s_bleSeqNum = 0;

// Beat + RR buffers — filled by SignalProcessor, consumed by Inference.
// Sized at compile time from config.h constants.
static float s_beatBuf[SamplingCfg::BEAT_WINDOW_SAMPLES];
static float s_rrBuf[MLCfg::N_RR_FEATURES];

// ============================================================
// Sensor Task  (Core 1 — sampling + signal processing)
// ============================================================
static void sensorTask(void* /*param*/) {
#if ENABLE_SERIAL_CAPTURE
    Serial.println("timestamp_ms,ecg_raw,ecg_filtered");
#endif

    int16_t ecgBatch[SamplingCfg::BLE_ECG_BATCH_SIZE];
    size_t  batchCount = 0;

    for (;;) {
        const uint32_t now = millis();

        ecg.update();

        while (ecg.available() > 0) {
            int16_t raw = 0;
            if (ecg.popSamples(&raw, 1) == 0) break;

            // Lead-off detection.
            const bool leadOff = !ecg.leadsOn() || (raw == INT16_MIN);

            if (leadOff) {
                // Reset filter + detector state on lead disconnect.
                // The R-peak detector will re-adapt within a few beats.
                processor.reset();
            }

            int16_t sampleToSend;
            if (leadOff) {
                sampleToSend = 0;
            } else {
                float filtered = processor.processSample(raw);
                sampleToSend   = static_cast<int16_t>(filtered);
            }

#if ENABLE_SERIAL_CAPTURE
            Serial.printf("%lu,%d,%d\n", now, (int)raw, (int)sampleToSend);
#endif

            ecgBatch[batchCount++] = sampleToSend;

            // Send BLE packet once a full batch is ready.
            if (batchCount == SamplingCfg::BLE_ECG_BATCH_SIZE) {
                BLECfg::DataPacket pkt = {};
                pkt.seq_num      = s_bleSeqNum++;
                pkt.timestamp_ms = now;
                uint8_t loPlus  = digitalRead(Pins::ECG_LO_PLUS)  ? 0x01 : 0x00;
                uint8_t loMinus = digitalRead(Pins::ECG_LO_MINUS) ? 0x02 : 0x00;
                pkt.lead_off = loPlus | loMinus;  // 0x00 = both leads on

                memcpy(pkt.ecg, ecgBatch,
                       sizeof(int16_t) * SamplingCfg::BLE_ECG_BATCH_SIZE);

                ble.sendStream(pkt);
                batchCount = 0;
            }

            // -- ML inference (triggered once per detected beat) -----------
            // isBeatReady() is only true for exactly one processSample()
            // call after a complete 250-sample beat window is captured.
            if (!leadOff && processor.isBeatReady()) {
                processor.getBeat(s_beatBuf);       // raw filtered ECG window
                processor.getRRFeatures(s_rrBuf);   // [pre_rr_norm, post_rr_norm, ratio_rr]

                if (inference.runInference(s_beatBuf, s_rrBuf)) {
                    InferenceResult r = inference.getResult();

                    // Always send the BLE inference packet so the app can
                    // display all beats; valid=false ones can be shown dimmed.
                    BLECfg::InferencePacket ipkt = {};
                    ipkt.marker       = 0xFF;
                    ipkt.timestamp_ms = millis();
                    ipkt.class_id     = static_cast<uint8_t>(r.classification);
                    ipkt.confidence   = r.confidence;
                    ble.sendInference(ipkt);

#if ENABLE_SERIAL_CAPTURE
                    Serial.printf("[BEAT] class=%u conf=%.2f valid=%d"
                                  " pre_rr=%.3f post_rr=%.3f ratio=%.3f\n",
                                  ipkt.class_id, ipkt.confidence, (int)r.valid,
                                  s_rrBuf[0], s_rrBuf[1], s_rrBuf[2]);
#endif
                }
            }
        }

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

    if (!ecg.begin()) {
        while (true) { delay(1000); }
    }

    if (!inference.begin()) {
        // Model failed to load — continue without ML.
        // BLE stream will still work; inference packets simply won't be sent.
    }

    if (!ble.begin()) {
        while (true) { delay(1000); }
    }

    xTaskCreatePinnedToCore(
        sensorTask,
        "SensorTask",
        SENSOR_TASK_STACK,
        nullptr,
        /*priority=*/ 5,
        nullptr,
        /*core=*/     1
    );
}

void loop() {
    vTaskDelay(portMAX_DELAY);
}