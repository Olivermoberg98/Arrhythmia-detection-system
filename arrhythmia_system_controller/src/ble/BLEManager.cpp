#include "BLEManager.h"
#include "NimBLEDevice.h"
#include "Arduino.h"

BLEManager::BLEManager() {}

bool BLEManager::begin() {
    NimBLEDevice::init(BLECfg::DEVICE_NAME);
    NimBLEDevice::setPower(9);   // Max TX power (dBm)

    _server = NimBLEDevice::createServer();
    _server->setCallbacks(this);

    // ---- GATT Service ----
    NimBLEService* service = _server->createService(BLECfg::SERVICE_UUID);

    // ---- Stream characteristic (ECG only, notify) ----
    _charStream = service->createCharacteristic(
        BLECfg::CHAR_STREAM_UUID,
        NIMBLE_PROPERTY::NOTIFY
    );

    // ---- Inference result characteristic (notify) ----
    _charInference = service->createCharacteristic(
        BLECfg::CHAR_INFER_UUID,
        NIMBLE_PROPERTY::NOTIFY
    );

    // ---- Config / status characteristic (read + write) ----
    _charConfig = service->createCharacteristic(
        BLECfg::CHAR_CONFIG_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE
    );
    uint8_t fwVersion = 0x01;
    _charConfig->setValue(&fwVersion, 1);

    service->start();

    // ---- Advertising ----
    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(BLECfg::SERVICE_UUID);
    adv->setName(BLECfg::DEVICE_NAME);
    adv->setMinInterval(BLECfg::ADV_INTERVAL_MIN);
    adv->setMaxInterval(BLECfg::ADV_INTERVAL_MAX);
    adv->start();

    return true;
}

// -------------------------------------------------------------------
void BLEManager::sendStream(const BLECfg::DataPacket& pkt) {
    if (!_connected.load(std::memory_order_relaxed)) return;

    _charStream->setValue(
        reinterpret_cast<const uint8_t*>(&pkt),
        sizeof(BLECfg::DataPacket)
    );
    _charStream->notify();
}

void BLEManager::sendInference(const BLECfg::InferencePacket& pkt) {
    if (!_connected.load(std::memory_order_relaxed)) return;

    _charInference->setValue(
        reinterpret_cast<const uint8_t*>(&pkt),
        sizeof(BLECfg::InferencePacket)
    );
    _charInference->notify();
}

// -------------------------------------------------------------------
// NimBLE 2.x callback signatures
// -------------------------------------------------------------------
void BLEManager::onConnect(NimBLEServer* pServer, NimBLEConnInfo& connInfo) {
    _connHandle = connInfo.getConnHandle();
    _connected.store(true, std::memory_order_release);

    // Request tighter connection interval (30-40 ms) for ECG streaming
    pServer->updateConnParams(
        _connHandle,
        BLECfg::CONN_INTERVAL_MIN,
        BLECfg::CONN_INTERVAL_MAX,
        /*latency=*/  0,
        /*timeout=*/  400   // 4 s supervision timeout
    );
}

void BLEManager::onDisconnect(NimBLEServer* pServer,
                               NimBLEConnInfo& connInfo,
                               int reason) {
    _connected.store(false, std::memory_order_release);
    _connHandle = 0xFFFF;

    // Restart advertising so a new connection can be made
    NimBLEDevice::getAdvertising()->start();
}