#include "BLEManager.h"
#include "NimBLEDevice.h"
#include "Arduino.h"

BLEManager::BLEManager() {}

bool BLEManager::begin() {
    NimBLEDevice::init(BLECfg::DEVICE_NAME);

    // Increase TX power for reliable range
    NimBLEDevice::setPower(ESP_PWR_LVL_P9);

    _server = NimBLEDevice::createServer();
    _server->setCallbacks(this);

    // Advertise with a short connection interval to maximise throughput
    _server->setMinInterval(BLECfg::CONN_INTERVAL_MIN);
    _server->setMaxInterval(BLECfg::CONN_INTERVAL_MAX);

    // ---- GATT Service ----
    NimBLEService* service = _server->createService(BLECfg::SERVICE_UUID);

    // ---- Stream characteristic (ECG + PPG)  ----
    _charStream = service->createCharacteristic(
        BLECfg::CHAR_STREAM_UUID,
        NIMBLE_PROPERTY::NOTIFY
    );

    // ---- Inference result characteristic ----
    _charInference = service->createCharacteristic(
        BLECfg::CHAR_INFER_UUID,
        NIMBLE_PROPERTY::NOTIFY
    );

    // ---- Config / status characteristic (read + write) ----
    _charConfig = service->createCharacteristic(
        BLECfg::CHAR_CONFIG_UUID,
        NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE
    );
    // Initial value: firmware version byte
    uint8_t fwVersion = 0x01;
    _charConfig->setValue(&fwVersion, 1);

    service->start();

    // ---- Advertising ----
    NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
    adv->addServiceUUID(BLECfg::SERVICE_UUID);
    adv->setScanResponse(true);
    adv->setMinPreferred(BLECfg::CONN_INTERVAL_MIN);
    adv->setMaxPreferred(BLECfg::CONN_INTERVAL_MAX);
    NimBLEDevice::startAdvertising();

    return true;
}

// -------------------------------------------------------------------
void BLEManager::sendStream(const BLECfg::DataPacket& pkt) {
    if (!_connected.load(std::memory_order_relaxed)) return;
    if (!_charStream->getSubscribedCount()) return;

    _charStream->setValue(
        reinterpret_cast<const uint8_t*>(&pkt),
        sizeof(BLECfg::DataPacket)
    );
    _charStream->notify();
}

void BLEManager::sendInference(const BLECfg::InferencePacket& pkt) {
    if (!_connected.load(std::memory_order_relaxed)) return;
    if (!_charInference->getSubscribedCount()) return;

    _charInference->setValue(
        reinterpret_cast<const uint8_t*>(&pkt),
        sizeof(BLECfg::InferencePacket)
    );
    _charInference->notify();
}

// -------------------------------------------------------------------
void BLEManager::onConnect(NimBLEServer* pServer, ble_gap_conn_desc* desc) {
    _connHandle = desc->conn_handle;
    _connected.store(true, std::memory_order_release);

    // Request tighter connection interval immediately after connect
    pServer->updateConnParams(
        desc->conn_handle,
        BLECfg::CONN_INTERVAL_MIN,
        BLECfg::CONN_INTERVAL_MAX,
        /*latency=*/ 0,
        /*timeout=*/ 400   // 4 seconds supervision timeout
    );
}

void BLEManager::onDisconnect(NimBLEServer* pServer) {
    _connected.store(false, std::memory_order_release);
    _connHandle = BLE_HS_CONN_HANDLE_NONE;

    // Restart advertising so a new connection can be made
    NimBLEDevice::startAdvertising();
}
