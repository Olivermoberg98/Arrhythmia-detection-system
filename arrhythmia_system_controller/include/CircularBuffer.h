#pragma once
#include <stddef.h>
#include <stdint.h>
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"

/**
 * @file  CircularBuffer.h
 * @brief Lock-protected, fixed-capacity circular buffer.
 *
 * Designed for single-producer / single-consumer use across
 * FreeRTOS tasks.  Uses a binary semaphore so it is safe to push
 * from an ISR or a higher-priority task and pop from another.
 *
 * @tparam T    Element type.
 * @tparam N    Capacity (power-of-two recommended for speed).
 */
template<typename T, size_t N>
class CircularBuffer {
public:
    CircularBuffer() : _head(0), _tail(0), _count(0) {
        _mutex = xSemaphoreCreateMutex();
    }

    ~CircularBuffer() {
        vSemaphoreDelete(_mutex);
    }

    /** @brief Push one element.  Returns false if full (oldest is NOT overwritten). */
    bool push(const T& val) {
        if (xSemaphoreTake(_mutex, portMAX_DELAY) != pdTRUE) return false;
        bool ok = false;
        if (_count < N) {
            _buf[_head] = val;
            _head = (_head + 1) % N;
            _count++;
            ok = true;
        }
        xSemaphoreGive(_mutex);
        return ok;
    }

    /**
     * @brief  Overwriting push. Drops the oldest element when full.
     *         Preferred for streaming sensor data.
     */
    void pushOverwrite(const T& val) {
        if (xSemaphoreTake(_mutex, portMAX_DELAY) != pdTRUE) return;
        _buf[_head] = val;
        _head = (_head + 1) % N;
        if (_count == N) {
            // Overwrite: advance tail to discard oldest
            _tail = (_tail + 1) % N;
        } else {
            _count++;
        }
        xSemaphoreGive(_mutex);
    }

    /** @brief Pop one element. Returns false if empty. */
    bool pop(T& out) {
        if (xSemaphoreTake(_mutex, portMAX_DELAY) != pdTRUE) return false;
        bool ok = false;
        if (_count > 0) {
            out = _buf[_tail];
            _tail = (_tail + 1) % N;
            _count--;
            ok = true;
        }
        xSemaphoreGive(_mutex);
        return ok;
    }

    /**
     * @brief  Pop up to `maxItems` elements into `dest[].
     * @return Number of items actually popped.
     */
    size_t popBatch(T* dest, size_t maxItems) {
        if (xSemaphoreTake(_mutex, portMAX_DELAY) != pdTRUE) return 0;
        size_t n = 0;
        while (n < maxItems && _count > 0) {
            dest[n++] = _buf[_tail];
            _tail = (_tail + 1) % N;
            _count--;
        }
        xSemaphoreGive(_mutex);
        return n;
    }

    size_t count() const { return _count; }
    bool   empty() const { return _count == 0; }
    bool   full()  const { return _count == N; }
    size_t capacity() const { return N; }

private:
    T                 _buf[N];
    volatile size_t   _head;
    volatile size_t   _tail;
    volatile size_t   _count;
    SemaphoreHandle_t _mutex;
};
