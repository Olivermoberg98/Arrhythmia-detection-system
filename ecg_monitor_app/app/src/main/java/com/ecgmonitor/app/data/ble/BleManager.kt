package com.ecgmonitor.app.data.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import com.juul.kable.logs.Logging
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BleManager(private val context: Context) {

    // region Public state

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDevice>> = _discoveredDevices.asStateFlow()

    private val _ecgPacketFlow = MutableStateFlow<EcgPacket?>(null)
    val ecgPacketFlow: StateFlow<EcgPacket?> = _ecgPacketFlow.asStateFlow()

    private val _inferenceResultFlow = MutableStateFlow<InferenceResult?>(null)
    val inferenceResultFlow: StateFlow<InferenceResult?> = _inferenceResultFlow.asStateFlow()

    /**
     * True when any lead-off bit is set in byte 5 (Bit-0 = LO+, Bit-1 = LO-).
     * Firmware bitmask: 0x00 = both leads on, any other value = at least one lead off.
     */
    private val _leadOffState = MutableStateFlow(false)
    val leadOffState: StateFlow<Boolean> = _leadOffState.asStateFlow()

    // endregion

    // region Internal state

    private var lastSeqNum: Int = -1
    private var currentDevice: BleDevice? = null
    private var peripheral: Peripheral? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var rawPacketCount = 0

    // endregion

    // region Scanning

    suspend fun startScanning() {
        if (!BlePermissions.allGranted(context)) {
            _connectionState.value = ConnectionState.Error("Bluetooth permissions not granted")
            return
        }
        if (!isBluetoothEnabled()) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
            return
        }

        Timber.i("Starting BLE scan")
        _connectionState.value = ConnectionState.Scanning
        _discoveredDevices.value = emptyList()

        try {
            Scanner().advertisements
                .catch { cause ->
                    Timber.e(cause, "Scan failed")
                    _connectionState.value = ConnectionState.Error("Scan failed: ${cause.message}")
                }
                .collect { advertisement ->
                    val name = advertisement.name
                    if (name.isNullOrBlank()) return@collect
                    val device = BleDevice(
                        address = advertisement.address,
                        name    = advertisement.name,
                        rssi    = advertisement.rssi
                    )
                    val current = _discoveredDevices.value.toMutableList()
                    if (current.none { it.address == device.address }) {
                        current.add(device)
                        _discoveredDevices.value = current
                        Timber.d("Found: ${device.name} (${device.address})")
                    }
                }
        } catch (e: Exception) {
            Timber.e(e, "Scan error")
            _connectionState.value = ConnectionState.Error("Scan failed: ${e.message}")
        }
    }

    fun stopScanning() {
        _connectionState.value = ConnectionState.Disconnected
        Timber.i("Scan stopped")
    }

    // endregion

    // region Connection

    /**
     * Full connection sequence with correct Kable ordering and MTU negotiation:
     *
     * 1. Build peripheral
     * 2. Register observe() flows  — must be before connect()
     * 3. connect()                 — CCCD writes happen here
     * 4. Request MTU ≥ 29          — 26-byte packet + 3-byte ATT header
     * 5. Launch collectors on the pre-registered flows
     *
     * BLE default MTU is 23 bytes (20-byte payload). Our DataPacket is 26 bytes,
     * so without negotiation the stack silently truncates every notification.
     * We request 512; the ESP32 caps at 185, yielding a 182-byte payload.
     */
    suspend fun connect(device: BleDevice) {
        if (!BlePermissions.allGranted(context)) {
            _connectionState.value = ConnectionState.Error("Bluetooth permissions not granted")
            return
        }
        if (!isBluetoothEnabled()) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
            return
        }

        Timber.i("Connecting to ${device.name} (${device.address})")
        _connectionState.value = ConnectionState.Connecting
        rawPacketCount = 0

        try {
            val p = scope.peripheral(device.address) {
                logging { level = Logging.Level.Events }
            }
            peripheral = p

            val streamCharacteristic = characteristicOf(
                service        = ECG_SERVICE_UUID,
                characteristic = ECG_STREAM_CHARACTERISTIC_UUID
            )
            val inferenceCharacteristic = characteristicOf(
                service        = ECG_SERVICE_UUID,
                characteristic = ECG_INFERENCE_CHARACTERISTIC_UUID
            )

            Timber.d("Registering observations (before connect)…")
            val streamFlow: Flow<ByteArray>    = p.observe(streamCharacteristic)
            val inferenceFlow: Flow<ByteArray> = p.observe(inferenceCharacteristic)
            Timber.d("Observations registered — calling connect()")

            p.connect()
            Timber.i("GATT connected to ${device.name}")

            p.services?.forEach { service ->
                Timber.d("Service: ${service.serviceUuid}")
                service.characteristics.forEach { char ->
                    Timber.d("  Char: ${char.characteristicUuid}  props=${char.properties}")
                }
            }

            // We request 512; the ESP32 (NimBLE setMTU(185)) caps negotiation at 185
            // giving a 182-byte payload. Without this, notifications are truncated to 20 bytes.
            val negotiatedMtu = try {
                (p as AndroidPeripheral).requestMtu(REQUESTED_MTU)
            } catch (e: Exception) {
                Timber.w("MTU negotiation threw: ${e.message} — will use default")
                ATT_HEADER_BYTES + 20
            }

            val maxPayload = negotiatedMtu - ATT_HEADER_BYTES
            Timber.i("MTU negotiated: ${negotiatedMtu}B  →  max payload: ${maxPayload}B")

            if (maxPayload < STREAM_PACKET_SIZE) {
                Timber.e(
                    "MTU too small after negotiation: ${maxPayload}B payload < ${STREAM_PACKET_SIZE}B DataPacket. " +
                            "Ensure NimBLEDevice::setMTU(185) is called before init() on the ESP32."
                )
                _connectionState.value = ConnectionState.Error(
                    "MTU too small (${maxPayload}B). Flash updated ESP32 firmware."
                )
                p.disconnect()
                return
            }

            currentDevice = device
            lastSeqNum    = -1
            _connectionState.value = ConnectionState.Connected(device)

            scope.launch {
                p.state.collect { peripheralState ->
                    Timber.d("Peripheral state → $peripheralState")
                    if (peripheralState is com.juul.kable.State.Disconnected) {
                        Timber.w("Peripheral dropped connection")
                        currentDevice = null
                        lastSeqNum    = -1
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }

            scope.launch {
                Timber.d("ECG stream collector started — awaiting notifications…")
                streamFlow
                    .catch { cause ->
                        Timber.e(cause, "ECG stream flow error: ${cause.message}")
                        _connectionState.value =
                            ConnectionState.Error("ECG stream failed: ${cause.message}")
                    }
                    .collect { bytes -> parseStreamPacket(bytes) }
                Timber.w("ECG stream flow completed (peripheral disconnected?)")
            }

            scope.launch {
                Timber.d("Inference collector started — awaiting notifications…")
                inferenceFlow
                    .catch { cause ->
                        Timber.w("Inference flow error (non-fatal): ${cause.message}")
                    }
                    .collect { bytes -> parseInferencePacket(bytes) }
                Timber.d("Inference flow completed")
            }

        } catch (e: Exception) {
            Timber.e(e, "Connection failed: ${e.message}")
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
        }
    }

    suspend fun disconnect() {
        peripheral?.disconnect()
        currentDevice = null
        lastSeqNum    = -1
        _connectionState.value = ConnectionState.Disconnected
        Timber.i("Disconnected")
    }

    // endregion

    // region Packet parsers

    /**
     * Parses a 26-byte little-endian stream packet.
     *
     * Layout (matches config.h DataPacket):
     * - [0]     uint8    seq_num
     * - [1-4]   uint32   timestamp_ms  (LE)
     * - [5]     uint8    lead_off      Bit-0=LO+, Bit-1=LO-. 0x00 = both leads on.
     * - [6-25]  int16×10 ecg[10]       Filtered samples offset from ADC midpoint (2048)
     */
    private fun parseStreamPacket(bytes: ByteArray) {
        rawPacketCount++
        if (rawPacketCount <= 5) {
            val hex = bytes.joinToString(" ") { "%02X".format(it) }
            Timber.d("RAW stream packet #$rawPacketCount (${bytes.size}B): $hex")
        } else if (rawPacketCount == 6) {
            Timber.d("Raw-dump suppressed after packet #5 (data flowing normally)")
        }

        if (bytes.size < STREAM_PACKET_SIZE) {
            Timber.w(
                "Stream packet too short: ${bytes.size}B (need $STREAM_PACKET_SIZE). " +
                        "MTU not negotiated correctly — check ESP32 NimBLEDevice::setMTU(185) call."
            )
            return
        }

        val buf         = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val seqNum      = buf.get().toInt() and 0xFF
        val timestampMs = buf.int.toLong() and 0xFFFFFFFFL
        val leadOffByte = buf.get().toInt() and 0xFF

        val droppedBefore: Int = if (lastSeqNum == -1) {
            0
        } else {
            val expected = (lastSeqNum + 1) and 0xFF
            if (seqNum != expected) {
                val dropped = (seqNum - expected + 256) and 0xFF
                Timber.w("Dropped $dropped packet(s): expected seq $expected, got $seqNum")
                dropped
            } else 0
        }
        lastSeqNum = seqNum

        val leadsOff = leadOffByte != 0x00
        if (_leadOffState.value != leadsOff) {
            _leadOffState.value = leadsOff
            if (leadsOff) {
                val loPlus  = if (leadOffByte and 0x01 != 0) "LO+" else ""
                val loMinus = if (leadOffByte and 0x02 != 0) "LO-" else ""
                Timber.w("Leads off — $loPlus $loMinus (byte=0x${leadOffByte.toString(16)})")
            } else {
                Timber.i("Leads reconnected")
            }
        }

        val samples = ShortArray(ECG_SAMPLES_PER_PACKET) { buf.short }

        if (rawPacketCount <= 5) {
            Timber.d(
                "Parsed → seq=$seqNum  t=${timestampMs}ms  " +
                        "leadOff=0x${leadOffByte.toString(16)}  " +
                        "samples[0..2]=${samples[0]},${samples[1]},${samples[2]}"
            )
        }

        _ecgPacketFlow.value = EcgPacket(
            seqNum        = seqNum,
            timestampMs   = timestampMs,
            leadsOff      = leadsOff,
            samples       = samples,
            droppedBefore = droppedBefore
        )
    }

    /**
     * Parses a 10-byte little-endian inference packet.
     *
     * Layout (matches config.h InferencePacket):
     * - [0]    uint8    marker        always 0xFF
     * - [1-4]  uint32   timestamp_ms  (LE)
     * - [5]    uint8    class_id      MLCfg::Classification enum
     * - [6-9]  float32  confidence    softmax probability 0.0–1.0
     */
    private fun parseInferencePacket(bytes: ByteArray) {
        if (bytes.size < INFERENCE_PACKET_SIZE) {
            Timber.w("Inference packet too short: ${bytes.size}B (need $INFERENCE_PACKET_SIZE)")
            return
        }

        val buf    = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val marker = buf.get().toInt() and 0xFF

        if (marker != INFERENCE_MARKER) {
            Timber.w("Unexpected inference marker: 0x${marker.toString(16)} (expected 0xFF)")
            return
        }

        val timestampMs = buf.int.toLong() and 0xFFFFFFFFL
        val classId     = buf.get().toInt() and 0xFF
        val confidence  = buf.float
        val className   = CLASS_NAMES[classId] ?: "Unknown($classId)"

        Timber.i("Inference → $className  ${"%.1f".format(confidence * 100)}%  t=${timestampMs}ms")

        _inferenceResultFlow.value = InferenceResult(
            timestampMs = timestampMs,
            classId     = classId,
            className   = className,
            confidence  = confidence
        )
    }

    // endregion

    // region Helpers

    fun setError(message: String) {
        _connectionState.value = ConnectionState.Error(message)
    }

    fun isBluetoothEnabled(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    fun hasRequiredPermissions(): Boolean = BlePermissions.allGranted(context)

    // endregion

    companion object {
        private const val ECG_SERVICE_UUID                  = "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
        private const val ECG_STREAM_CHARACTERISTIC_UUID    = "beb5483e-36e1-4688-b7f5-ea07361b26a8"
        private const val ECG_INFERENCE_CHARACTERISTIC_UUID = "beb5483f-36e1-4688-b7f5-ea07361b26a8"

        private const val STREAM_PACKET_SIZE     = 26
        private const val INFERENCE_PACKET_SIZE  = 10
        private const val ECG_SAMPLES_PER_PACKET = 10
        private const val INFERENCE_MARKER       = 0xFF

        /** 3-byte ATT protocol header consumed from every BLE notification MTU. */
        private const val ATT_HEADER_BYTES = 3

        /**
         * MTU requested from the peripheral. The ESP32 (NimBLE setMTU(185)) caps
         * negotiation at 185, yielding a 182-byte payload. Requesting 512 ensures
         * we always accept whatever the peripheral offers.
         */
        private const val REQUESTED_MTU = 512

        val CLASS_NAMES = mapOf(
            0 to "Normal",
            1 to "Supraventricular",
            2 to "Ventricular"
        )

        const val SAMPLE_INTERVAL_MS = 4L

        @Suppress("unused")
        const val NOTIFICATION_INTERVAL_MS = 40L
    }
}

// region Sealed states and data classes

sealed class ConnectionState {
    object Disconnected                         : ConnectionState()
    object Scanning                             : ConnectionState()
    object Connecting                           : ConnectionState()
    data class Connected(val device: BleDevice) : ConnectionState()
    data class Error(val message: String)       : ConnectionState()
}

data class BleDevice(val address: String, val name: String?, val rssi: Int)

data class EcgPacket(
    val seqNum       : Int,
    val timestampMs  : Long,
    val leadsOff     : Boolean,
    val samples      : ShortArray,
    val droppedBefore: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EcgPacket) return false
        return seqNum        == other.seqNum        &&
                timestampMs   == other.timestampMs   &&
                leadsOff      == other.leadsOff      &&
                samples.contentEquals(other.samples) &&
                droppedBefore == other.droppedBefore
    }

    override fun hashCode(): Int {
        var r = seqNum
        r = 31 * r + timestampMs.hashCode()
        r = 31 * r + leadsOff.hashCode()
        r = 31 * r + samples.contentHashCode()
        r = 31 * r + droppedBefore
        return r
    }
}

data class InferenceResult(
    val timestampMs: Long,
    val classId    : Int,
    val className  : String,
    val confidence : Float
)

// endregion