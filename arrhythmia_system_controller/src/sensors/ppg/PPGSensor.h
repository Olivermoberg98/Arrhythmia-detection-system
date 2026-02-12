// #pragma once
// #include "../../include/ISensor.h"
// #include "../../src/config.h"
// #include <stdint.h>

// /**
//  * @file  PPGSensor.h
//  * @brief MAX30102 heart-rate / SpO₂ sensor driver.
//  *
//  * Sampling strategy:
//  *   The MAX30102 has an internal FIFO sampled at PPG_SAMPLE_RATE_HZ
//  *   (100 Hz).  update() drains the FIFO each call; the SparkFun library
//  *   handles I2C burst reads.  Heart-rate and SpO₂ are computed by the
//  *   library's built-in algorithm on every call.
//  *
//  * Note: I2C is shared with other peripherals.  All I2C access is
//  *   protected by the FreeRTOS mutex in the sensor task.
//  */

// struct PPGReading {
//     uint32_t irRaw;        ///< Raw IR value  (perfusion index / SpO₂)
//     uint32_t redRaw;       ///< Raw Red value
//     uint8_t  spo2;         ///< Derived SpO₂ 0–100; 0xFF = invalid
//     uint8_t  heartRate;    ///< Derived HR in bpm;  0xFF = invalid
//     bool     valid;        ///< True when library has enough data
// };

// class MAX30105;   // Forward declaration of SparkFun library class

// class PPGSensor : public ISensor {
// public:
//     PPGSensor();
//     ~PPGSensor();

//     bool begin()     override;
//     void update()    override;
//     bool isReady() const override;
//     void reset()     override;

//     /** @brief Get the latest reading.  Valid only when isReady(). */
//     PPGReading getReading() const;

// private:
//     void computeVitals();

//     MAX30105* _sensor    = nullptr;
//     PPGReading _reading  = {};
//     bool       _ready    = false;

//     // Rolling buffer for SparkFun HR/SpO₂ algorithm
//     static constexpr size_t FIFO_DEPTH = 100;
//     uint32_t _irBuffer[FIFO_DEPTH]  = {};
//     uint32_t _redBuffer[FIFO_DEPTH] = {};
//     int32_t  _spo2Value   = -1;
//     int8_t   _spo2Valid   = 0;
//     int32_t  _hrValue     = -1;
//     int8_t   _hrValid     = 0;
//     size_t   _bufferIndex = 0;
// };
