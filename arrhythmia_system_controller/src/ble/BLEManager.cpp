#include "BLEManager.h"
#include "NimBLEDevice.h"
#include "Arduino.h"

BLEManager::BLEManager() {}

bool BLEManager::begin() {
    NimBLEDevice::init(BLECfg::DEVICE_NAME);
    NimBLEDevice::setMTU(512);
    NimBLEDevice::setPower(9);

    _server = NimBLEDevice::createServer();
    _server->setCallbacks(this);

    NimBLEService* service = _server->createService(BLECfg::SERVICE_UUID);

    // Stream characteristic (existing)
    _charStream = service->createCharacteristic(
        BLECfg::CHAR_STREAM_UUID,
        NIMBLE_PROPERTY::NOTIFY
    );

    // Beat characteristic (NEW)
    _charBeat = service->createCharacteristic(
        BLECfg::CHAR_BEAT_UUID,
        NIMBLE_PROPERTY::NOTIFY
    );

    // Config characteristic (existing)
    _charConfig = service->createCharacteristic(
        BLECfg::CHAR_CONFIG_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE
    );
    uint8_t fwVersion = 0x02;  // Version 2.0
    _charConfig->setValue(&fwVersion, 1);

    service->start();

    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(BLECfg::SERVICE_UUID);
    adv->setName(BLECfg::DEVICE_NAME);
    adv->setMinInterval(BLECfg::ADV_INTERVAL_MIN);
    adv->setMaxInterval(BLECfg::ADV_INTERVAL_MAX);
    adv->start();

    return true;
}

void BLEManager::sendStream(const BLECfg::DataPacket& pkt) {
    if (!_connected.load(std::memory_order_relaxed)) return;

    _charStream->setValue(
        reinterpret_cast<const uint8_t*>(&pkt),
        sizeof(BLECfg::DataPacket)
    );
    _charStream->notify();
}

// NEW: Send beat window
void BLEManager::sendBeat(const BLECfg::BeatPacket& pkt) {
    if (!_connected.load(std::memory_order_relaxed)) return;

    _charBeat->setValue(
        reinterpret_cast<const uint8_t*>(&pkt),
        sizeof(BLECfg::BeatPacket)
    );
    _charBeat->notify();
}

void BLEManager::onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) {
    _connHandle = connInfo.getConnHandle();
    _connected.store(true, std::memory_order_release);

    pServer->updateConnParams(
        _connHandle,
        BLECfg::CONN_INTERVAL_MIN,
        BLECfg::CONN_INTERVAL_MAX,
        0,
        400
    );
}

void BLEManager::onDisconnect(NimBLEServer* pServer,
                               NimBLEConnInfo& connInfo,
                               int reason) {
    _connected.store(false, std::memory_order_release);
    _connHandle = 0xFFFF;
    NimBLEDevice::getAdvertising()->start();
}