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

    // Lead-off debounce state.
    uint32_t s_leadOffRun  = 0;
    int16_t  s_lastGoodRaw = 0;

    // Settling window after power-up / leads-on: the chain keeps running (the
    // detector learns through it), but no beat whose window overlaps it is sent.
    uint32_t s_settleLeft  = SamplingCfg::LEAD_ON_SETTLE_SAMPLES;
    uint32_t s_settledFor  = 0;   // samples since settling ended

    for (;;) {
        const uint32_t now = millis();

        ecg.update();

        while (ecg.available() > 0) {
            int16_t raw = 0;
            if (ecg.popSamples(&raw, 1) == 0) break;

            // Lead-off is debounced: the AD8232's comparators chatter on
            // marginal contact, and a single-sample glitch must not tear down
            // the detector's state. Brief glitches hold the last good sample so
            // the filters and threshold keep tracking; only a sustained outage
            // resets the chain and zeroes the stream.
            const bool leadOffNow = !ecg.leadsOn() || (raw == INT16_MIN);

            if (leadOffNow) {
                if (s_leadOffRun < SamplingCfg::LEAD_OFF_DEBOUNCE_SAMPLES) s_leadOffRun++;
            } else {
                s_leadOffRun  = 0;
                s_lastGoodRaw = raw;
            }

            const bool leadOff =
                (s_leadOffRun >= SamplingCfg::LEAD_OFF_DEBOUNCE_SAMPLES);

            if (leadOff) {
                processor.reset();
                s_settleLeft = SamplingCfg::LEAD_ON_SETTLE_SAMPLES;
                s_settledFor = 0;
            }

            int16_t sampleToSend;
            if (leadOff) {
                sampleToSend = 0;
            } else {
                float filtered = processor.processSample(
                    leadOffNow ? s_lastGoodRaw : raw);
                sampleToSend   = static_cast<int16_t>(filtered);

                if (s_settleLeft > 0) s_settleLeft--;
                else if (s_settledFor < UINT32_MAX) s_settledFor++;
            }

            // Beats are emitted one RR late, so a beat sent after settling can
            // still have its window inside the transient: drop those too.
            const bool beatSettled = s_settleLeft == 0 &&
                processor.samplesSinceR() + SamplingCfg::BEAT_PRE_SAMPLES <= s_settledFor;

            // Per-sample CSV for tools/data_capture/capture.py: build with -DENABLE_SERIAL_STREAM=1.
#if ENABLE_SERIAL_CAPTURE && defined(ENABLE_SERIAL_STREAM) && ENABLE_SERIAL_STREAM
            Serial.printf("%lu,%d,%d\n", now, (int)raw, (int)sampleToSend);
#endif

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
            if (!leadOff && beatSettled && processor.isBeatReady()) {
                BLECfg::BeatPacket beatPkt = {};
                beatPkt.marker       = 0xBE;
                // Back-date to the R-peak: the beat is sent one post-R window
                // plus crest search after it, and the app aligns on this time.
                beatPkt.timestamp_ms = millis()
                    - (processor.samplesSinceR() * 1000u
                       / SamplingCfg::ECG_SAMPLE_RATE_HZ);
                beatPkt.beat_num     = s_beatNum++;

                float beatFloat[SamplingCfg::BEAT_WINDOW_SAMPLES];
                processor.getBeat(beatFloat);
                for (size_t i = 0; i < SamplingCfg::BEAT_WINDOW_SAMPLES; i++) {
                    beatPkt.ecg[i] = static_cast<int16_t>(beatFloat[i]);
                }

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
    Serial.println("[INIT] Starting ECG Monitor");
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

    // Sampling, filtering, detection and BLE TX all run in one task on core 1.
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