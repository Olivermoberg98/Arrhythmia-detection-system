#pragma once
#include "../config.h"
#include "NimBLEDevice.h"
#include <stdint.h>
#include <atomic>

/**
 * @file  BLEManager.h
 * @brief BLE GATT server wrapping NimBLE-Arduino.
 *
 * Implements the wire protocol defined in BLECfg (config.h).
 *
 * Kable (Kotlin BLE) compatibility notes:
 *   - Uses standard GATT service + notify characteristics.
 *   - Client must subscribe (write 0x0001 to the CCCD) before
 *     notifications are sent.  Kable does this automatically when
 *     you call observe() on a characteristic.
 *   - MTU is negotiated on connection; NimBLE attempts up to 247 bytes.
 *   - Connection parameter update request is sent after connect to
 *     reduce interval to CONN_INTERVAL_MIN/MAX (30-40 ms).
 *
 * Thread-safety:
 *   sendStream() and sendInference() may be called from any task.
 *   They check _connected atomically and NimBLE handles the underlying
 *   stack serialisation.
 */

class BLEManager : public NimBLEServerCallbacks {
public:
    BLEManager();

    bool begin();

    /** @brief True when a central is connected and subscribed. */
    bool isConnected() const { return _connected.load(); }

    /**
     * @brief  Send a data stream packet (ECG + PPG).
     *         Drops silently if no subscriber.
     */
    void sendStream(const BLECfg::DataPacket& pkt);

    /**
     * @brief  Send an inference result packet.
     *         Drops silently if no subscriber.
     */
    void sendInference(const BLECfg::InferencePacket& pkt);

    // ---- NimBLEServerCallbacks ----
    void onConnect   (NimBLEServer* pServer, ble_gap_conn_desc* desc) override;
    void onDisconnect(NimBLEServer* pServer) override;

private:
    NimBLEServer*         _server         = nullptr;
    NimBLECharacteristic* _charStream     = nullptr;
    NimBLECharacteristic* _charInference  = nullptr;
    NimBLECharacteristic* _charConfig     = nullptr;

    std::atomic<bool> _connected{false};
    uint8_t           _connHandle = BLE_HS_CONN_HANDLE_NONE;
};
