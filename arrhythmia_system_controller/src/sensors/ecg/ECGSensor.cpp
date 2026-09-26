#include "ECGSensor.h"
#include "Arduino.h"
#include "driver/adc.h"

// ---- Static member definition ----
std::atomic<bool> ECGSensor::_sampleDue{false};

// Hardware timer handle (ESP32 group 0, timer 0)
static hw_timer_t* s_ecgTimer = nullptr;

void IRAM_ATTR ECGSensor::onTimerISR() {
    // Minimal work in ISR: just set the flag.
    _sampleDue.store(true, std::memory_order_relaxed);
}

// -------------------------------------------------------------------
ECGSensor::ECGSensor() {}

bool ECGSensor::begin() {
    // Configure lead-off detect pins as inputs with pull-up
    pinMode(Pins::ECG_LO_PLUS,  INPUT_PULLUP);
    pinMode(Pins::ECG_LO_MINUS, INPUT_PULLUP);

    // Configure ADC for ECG pin.
    // ADC1 channels are safe to use without WiFi/BLE restrictions.
    // 12-bit width, 11 dB attenuation (~0-3.1 V usable).
    adc1_config_width(ADC_WIDTH_BIT_12);
    adc1_config_channel_atten(
        static_cast<adc1_channel_t>(Pins::ECG_ADC_CHANNEL), ADC_ATTEN_DB_11);

    startTimer();
    _ready = false;
    return true;
}

void ECGSensor::update() {
    // Check lead-off status every call (cheap GPIO read)
    bool lo_p = digitalRead(Pins::ECG_LO_PLUS)  == HIGH;
    bool lo_m = digitalRead(Pins::ECG_LO_MINUS) == HIGH;
    _leadsOn  = !(lo_p || lo_m);

    // Only sample when the timer has signalled
    if (!_sampleDue.exchange(false, std::memory_order_relaxed)) return;

    if (!_leadsOn) {
        // Push a sentinel (INT16_MIN) so downstream can mark the gap
        _buf.pushOverwrite(INT16_MIN);
        _ready = true;
        return;
    }

    // Read ADC and centre around zero
    int raw = adc1_get_raw(static_cast<adc1_channel_t>(Pins::ECG_ADC_CHANNEL));
    int16_t sample = static_cast<int16_t>(raw - SamplingCfg::ADC_MIDPOINT);

    _buf.pushOverwrite(sample);
    _ready = true;
}

bool ECGSensor::isReady() const {
    return _ready && !_buf.empty();
}

void ECGSensor::reset() {
    _ready   = false;
}

bool ECGSensor::leadsOn() const {
    return _leadsOn;
}

size_t ECGSensor::popSamples(int16_t* dest, size_t max) {
    return _buf.popBatch(dest, max);
}

size_t ECGSensor::available() const {
    return _buf.count();
}

// -------------------------------------------------------------------
void ECGSensor::startTimer() {
    // Timer 0, prescaler 80 → 1 µs tick (80 MHz / 80 = 1 MHz)
    s_ecgTimer = timerBegin(0, 80, true);
    timerAttachInterrupt(s_ecgTimer, &ECGSensor::onTimerISR, true);
    timerAlarmWrite(s_ecgTimer, SamplingCfg::ECG_TIMER_PERIOD_US, true);
    timerAlarmEnable(s_ecgTimer);
}

void ECGSensor::stopTimer() {
    if (s_ecgTimer) {
        timerAlarmDisable(s_ecgTimer);
        timerDetachInterrupt(s_ecgTimer);
        timerEnd(s_ecgTimer);
        s_ecgTimer = nullptr;
    }
}
