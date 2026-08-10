#include "Arduino.h"
#include "config.h"
#include "sensors/ecg/ECGSensor.h"
#include "signal_processing/SignalProcessor.h"
#include "ble/BLEManager.h"

// ============================================================
// Module instances
// ============================================================
static ECGSensor       ecg;
static SignalProcessor processor;
static BLEManager      ble;

// ============================================================
// Shared state
// ============================================================
static volatile uint8_t  s_bleSeqNum = 0;
static volatile uint16_t s_beatNum   = 0;

// ============================================================
// Sensor Task
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

            const bool leadOff = !ecg.leadsOn() || (raw == INT16_MIN);

            if (leadOff) {
                processor.reset();
            }

            int16_t sampleToSend;
            if (leadOff) {
                sampleToSend = 0;
            } else {
                float filtered = processor.processSample(raw);
                sampleToSend   = static_cast<int16_t>(filtered);
            }

// #if ENABLE_SERIAL_CAPTURE
//             Serial.printf("%lu,%d,%d\n", now, (int)raw, (int)sampleToSend);
// #endif

            ecgBatch[batchCount++] = sampleToSend;

            // Send BLE stream packet
            if (batchCount == SamplingCfg::BLE_ECG_BATCH_SIZE) {
                BLECfg::DataPacket pkt = {};
                pkt.seq_num      = s_bleSeqNum++;
                pkt.timestamp_ms = now;
                uint8_t loPlus  = digitalRead(Pins::ECG_LO_PLUS)  ? 0x01 : 0x00;
                uint8_t loMinus = digitalRead(Pins::ECG_LO_MINUS) ? 0x02 : 0x00;
                pkt.lead_off = loPlus | loMinus;

                memcpy(pkt.ecg, ecgBatch,
                       sizeof(int16_t) * SamplingCfg::BLE_ECG_BATCH_SIZE);

                ble.sendStream(pkt);
                batchCount = 0;
            }

            // ── Send beat window when ready ──────────────────────────────────────
            if (!leadOff && processor.isBeatReady()) {
                BLECfg::BeatPacket beatPkt = {};
                beatPkt.marker       = 0xBE;
                beatPkt.timestamp_ms = millis();
                beatPkt.beat_num     = s_beatNum++;
                
                // Was hardcoded to 250 — overflowed beatPkt.ecg (int16_t[128]).
                float beatFloat[SamplingCfg::BEAT_WINDOW_SAMPLES];
                processor.getBeat(beatFloat);
                for (size_t i = 0; i < SamplingCfg::BEAT_WINDOW_SAMPLES; i++) {
                    beatPkt.ecg[i] = static_cast<int16_t>(beatFloat[i]);
                }
                
                // Get RR features (3 values)
                processor.getRRFeatures(beatPkt.rr_features);
                
                ble.sendBeat(beatPkt);

#if ENABLE_SERIAL_CAPTURE
                Serial.printf("[BEAT_SENT] num=%u ts=%lu rr=[%.3f,%.3f,%.3f]\n",
                              beatPkt.beat_num,
                              beatPkt.timestamp_ms,
                              beatPkt.rr_features[0],
                              beatPkt.rr_features[1],
                              beatPkt.rr_features[2]);
#endif
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
    Serial.println("[INIT] Starting ECG Monitor v2.0");
#endif

    if (!ecg.begin()) {
#if ENABLE_SERIAL_CAPTURE
        Serial.println("[ERROR] ECG sensor init failed");
#endif
        while (true) { delay(1000); }
    }
#if ENABLE_SERIAL_CAPTURE
    Serial.println("[OK] ECG sensor initialized");
#endif

    if (!ble.begin()) {
#if ENABLE_SERIAL_CAPTURE
        Serial.println("[ERROR] BLE init failed");
#endif
        while (true) { delay(1000); }
    }
#if ENABLE_SERIAL_CAPTURE
    Serial.println("[OK] BLE initialized");
#endif

    // Single task
    xTaskCreatePinnedToCore(
        sensorTask,
        "SensorTask",
        4096,
        nullptr,
        5,
        nullptr,
        1
    );

#if ENABLE_SERIAL_CAPTURE
    Serial.println("[OK] Sensor task started");
#endif
}

void loop() {
    vTaskDelay(portMAX_DELAY);
}