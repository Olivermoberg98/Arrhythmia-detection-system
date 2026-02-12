#pragma once
#include "ISensor.h"
#include "CircularBuffer.h"
#include "config.h"
#include <stdint.h>
#include <atomic>

/**
 * @file  ECGSensor.h
 * @brief AD8232 ECG module driver.
 *
 * Sampling strategy:
 *   A hardware timer fires every ECG_TIMER_PERIOD_US (4 ms at 250 Hz)
 *   and sets an atomic flag.  The sensor task calls update() on every
 *   loop iteration; when the flag is set it reads the ADC and pushes the
 *   sample into the output circular buffer.  This avoids doing ADC reads
 *   inside an ISR (which is unreliable on ESP32).
 *
 * Output buffer: ECG_BLE_BUFFER_SIZE samples.  Caller pops batches of
 *   BLE_ECG_BATCH_SIZE every 40 ms for BLE streaming.  A separate
 *   ML sliding-window buffer is maintained inside the signal processor.
 */
class ECGSensor : public ISensor {
public:
    ECGSensor();

    bool begin()     override;
    void update()    override;
    bool isReady() const override;
    void reset()     override;

    /** @brief Whether leads are currently connected (both LO pins low). */
    bool leadsOn()   const;

    /**
     * @brief Pop up to `max` samples into `dest`.
     * @return Number of samples actually popped.
     */
    size_t popSamples(int16_t* dest, size_t max);

    /** @brief Number of samples currently waiting in buffer. */
    size_t available() const;

    // ---- Timer ISR callback (must be public for C linkage) ----
    static void IRAM_ATTR onTimerISR();

private:
    void  startTimer();
    void  stopTimer();

    CircularBuffer<int16_t, SamplingCfg::ECG_BLE_BUFFER_SIZE> _buf;

    // Atomic sample-ready flag set by ISR, cleared by update()
    static std::atomic<bool> _sampleDue;

    bool _ready    = false;
    bool _leadsOn  = false;
};
