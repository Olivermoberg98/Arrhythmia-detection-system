#pragma once
#include "config.h"
#include "NimBLEDevice.h"
#include <stdint.h>
#include <atomic>

class BLEManager : public NimBLEServerCallbacks {
public:
    BLEManager();
    bool begin();
    bool isConnected() const { return _connected.load(); }

    // Existing
    void sendStream(const BLECfg::DataPacket& pkt);
    
    // NEW: Send beat window
    void sendBeat(const BLECfg::BeatPacket& pkt);

    // NimBLE callbacks
    void onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) override;
    void onDisconnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo, int reason) override;

private:
    NimBLEServer*         _server        = nullptr;
    NimBLECharacteristic* _charStream    = nullptr;
    NimBLECharacteristic* _charBeat      = nullptr;
    NimBLECharacteristic* _charConfig    = nullptr;

    std::atomic<bool> _connected{false};
    uint16_t          _connHandle = 0xFFFF;
};