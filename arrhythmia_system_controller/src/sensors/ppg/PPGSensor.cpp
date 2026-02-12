// #include "PPGSensor.h"
// #include "Wire.h"
// #include "MAX30105.h"        // SparkFun MAX3010x library
// #include "spo2_algorithm.h"  // SparkFun HR/SpO₂ algorithm
// #include "Arduino.h"

// PPGSensor::PPGSensor() {
//     _sensor = new MAX30105();
// }

// PPGSensor::~PPGSensor() {
//     delete _sensor;
// }

// bool PPGSensor::begin() {
//     Wire.begin(Pins::I2C_SDA, Pins::I2C_SCL);

//     if (!_sensor->begin(Wire, I2C_SPEED_FAST)) {
//         return false;
//     }

//     // Configure MAX30102 for heart-rate + SpO₂ mode
//     // Sample rate 100 Hz, LED current 60 mA, 18-bit ADC resolution
//     _sensor->setup(
//         /*ledBrightness=*/ 60,
//         /*sampleAverage=*/ 4,     // 4 samples averaged → 400 samples/s ÷ 4 = 100 Hz effective
//         /*ledMode=*/       2,     // Red + IR
//         /*sampleRate=*/    400,
//         /*pulseWidth=*/    411,   // 18-bit
//         /*adcRange=*/      4096
//     );

//     _sensor->setPulseAmplitudeRed(0x0A);
//     _sensor->setPulseAmplitudeGreen(0);   // Not used

//     memset(_irBuffer,  0, sizeof(_irBuffer));
//     memset(_redBuffer, 0, sizeof(_redBuffer));
//     _bufferIndex = 0;
//     _ready       = false;

//     return true;
// }

// void PPGSensor::update() {
//     // Drain available FIFO samples (MAX30102 buffers up to 32)
//     while (_sensor->available()) {
//         _irBuffer [_bufferIndex] = _sensor->getIR();
//         _redBuffer[_bufferIndex] = _sensor->getRed();
//         _bufferIndex = (_bufferIndex + 1) % FIFO_DEPTH;
//         _sensor->nextSample();
//     }

//     // Refill sensor FIFO
//     _sensor->check();

//     // Once we have a full FIFO_DEPTH window, compute vitals
//     if (_bufferIndex == 0) {  // wrapped around once at least
//         computeVitals();
//         _ready = true;
//     }
// }

// void PPGSensor::computeVitals() {
//     maxim_heart_rate_and_oxygen_saturation(
//         _irBuffer, FIFO_DEPTH,
//         _redBuffer,
//         &_spo2Value, &_spo2Valid,
//         &_hrValue,   &_hrValid
//     );

//     _reading.irRaw     = _irBuffer[(_bufferIndex + FIFO_DEPTH - 1) % FIFO_DEPTH];
//     _reading.redRaw    = _redBuffer[(_bufferIndex + FIFO_DEPTH - 1) % FIFO_DEPTH];
//     _reading.spo2      = (_spo2Valid && _spo2Value >= 0) ? (uint8_t)_spo2Value : 0xFF;
//     _reading.heartRate = (_hrValid   && _hrValue   >= 0) ? (uint8_t)_hrValue   : 0xFF;
//     _reading.valid     = (_spo2Valid && _hrValid);
// }

// bool PPGSensor::isReady() const {
//     return _ready;
// }

// void PPGSensor::reset() {
//     memset(_irBuffer,  0, sizeof(_irBuffer));
//     memset(_redBuffer, 0, sizeof(_redBuffer));
//     _bufferIndex = 0;
//     _ready       = false;
//     _reading     = {};
// }

// PPGReading PPGSensor::getReading() const {
//     return _reading;
// }
