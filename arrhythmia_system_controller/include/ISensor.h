#pragma once

/**
 * @file  ISensor.h
 * @brief Abstract base class for all sensor drivers.
 *
 * Every sensor must implement begin(), update(), and isReady().
 * update() is called periodically from the sensor task; it should
 * be non-blocking (no busy-waits).
 */
class ISensor {
public:
    virtual ~ISensor() = default;

    /**
     * @brief  One-time hardware initialisation.
     * @return true on success, false on hardware fault.
     */
    virtual bool begin() = 0;

    /**
     * @brief  Called every main-loop tick. Reads new data if available.
     */
    virtual void update() = 0;

    /**
     * @brief  Returns true when fresh data has been produced since last read.
     */
    virtual bool isReady() const = 0;

    /**
     * @brief  Reset internal state (e.g. after lead-off event).
     */
    virtual void reset() {}
};
