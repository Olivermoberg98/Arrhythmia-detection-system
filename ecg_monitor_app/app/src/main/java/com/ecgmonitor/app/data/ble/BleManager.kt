package com.ecgmonitor.app.data.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import com.juul.kable.logs.Logging
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BleManager(private val context: Context) {

    // -------------------------------------------------------------------------
    // State flows
    // -------------------------------------------------------------------------

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDevice>> = _discoveredDevices.asStateFlow()

    /**
     * Emits every decoded 26-byte stream packet.
     * Each packet carries 10 ECG samples spaced 4 ms apart.
     * Check [EcgPacket.droppedBefore] > 0 to insert a gap in your waveform.
     */
    private val _ecgPacketFlow = MutableStateFlow<EcgPacket?>(null)
    val ecgPacketFlow: StateFlow<EcgPacket?> = _ecgPacketFlow.asStateFlow()

    /**
     * Emits a decoded 10-byte inference result whenever the ESP32 fires a
     * classification. Only updated on actual inference events, not every packet.
     */
    private val _inferenceResultFlow = MutableStateFlow<InferenceResult?>(null)
    val inferenceResultFlow: StateFlow<InferenceResult?> = _inferenceResultFlow.asStateFlow()

    /**
     * True when byte 5 of the stream packet is 0x03 (leads off).
     * Flips back to false once leads reconnect.
     */
    private val _leadOffState = MutableStateFlow(false)
    val leadOffState: StateFlow<Boolean> = _leadOffState.asStateFlow()

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    /** Last seen seq_num (0–255). -1 means we haven't received any packet yet. */
    private var lastSeqNum: Int = -1

    private var currentDevice: BleDevice? = null
    private var peripheral: Peripheral? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // -------------------------------------------------------------------------
    // Scanning
    // -------------------------------------------------------------------------

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
                        name = advertisement.name,
                        rssi = advertisement.rssi
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

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    suspend fun connect(device: BleDevice) {
        if (!BlePermissions.allGranted(context)) {
            _connectionState.value = ConnectionState.Error("Bluetooth permissions not granted")
            return
        }
        if (!isBluetoothEnabled()) {
            _connectionState.value = ConnectionState.Error("Bluetooth is disabled")
            return
        }

        Timber.i("Connecting to ${device.name}")
        _connectionState.value = ConnectionState.Connecting

        try {
            peripheral = scope.peripheral(device.address) {
                logging { level = Logging.Level.Events }
            }

            peripheral?.connect()

            peripheral?.services?.forEach { service ->
                Timber.d("Service: ${service.serviceUuid}")
                service.characteristics.forEach { char ->
                    Timber.d("  Char: ${char.characteristicUuid}")
                }
            }

            currentDevice = device
            lastSeqNum = -1  // Reset sequence tracking on every fresh connection
            _connectionState.value = ConnectionState.Connected(device)

            startStreamCollection()
            startInferenceCollection()

        } catch (e: Exception) {
            Timber.e(e, "Connection failed")
            _connectionState.value = ConnectionState.Error("Connection failed: ${e.message}")
        }
    }

    suspend fun disconnect() {
        peripheral?.disconnect()
        currentDevice = null
        lastSeqNum = -1
        _connectionState.value = ConnectionState.Disconnected
        Timber.i("Disconnected")
    }

    // -------------------------------------------------------------------------
    // Data collection
    // -------------------------------------------------------------------------

    /**
     * Subscribes to the ECG stream characteristic and parses each 26-byte packet.
     * Notifications arrive every ~40 ms, each carrying 10 samples at 4 ms spacing.
     */
    private fun startStreamCollection() {
        scope.launch {
            try {
                val characteristic = characteristicOf(
                    service = ECG_SERVICE_UUID,
                    characteristic = ECG_STREAM_CHARACTERISTIC_UUID
                )
                peripheral?.observe(characteristic)
                    ?.catch { cause ->
                        Timber.e(cause, "ECG stream failed")
                        _connectionState.value = ConnectionState.Error("ECG stream failed: ${cause.message}")
                    }
                    ?.collect { bytes -> parseStreamPacket(bytes) }
            } catch (e: Exception) {
                Timber.e(e, "Stream collection error")
            }
        }
    }

    /**
     * Subscribes to the inference characteristic and parses each 10-byte result packet.
     * Only notified when the ESP32 fires a classification — not every ECG packet.
     */
    private fun startInferenceCollection() {
        scope.launch {
            try {
                val characteristic = characteristicOf(
                    service = ECG_SERVICE_UUID,
                    characteristic = ECG_INFERENCE_CHARACTERISTIC_UUID
                )
                peripheral?.observe(characteristic)
                    ?.catch { cause ->
                        Timber.e(cause, "Inference stream failed")
                        // Non-fatal — inference is supplemental to the raw stream
                        Timber.w("Inference notifications unavailable: ${cause.message}")
                    }
                    ?.collect { bytes -> parseInferencePacket(bytes) }
            } catch (e: Exception) {
                Timber.e(e, "Inference collection error")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Packet parsers
    // -------------------------------------------------------------------------

    /**
     * Parses a 26-byte little-endian stream packet:
     *
     * Byte 0      uint8    seq_num        rolling counter 0–255
     * Bytes 1–4   uint32   timestamp_ms   ESP32 millis() when the batch started
     * Byte 5      uint8    lead_off       0x00 = leads on, 0x03 = leads off
     * Bytes 6–25  int16×10 ecg[10]        10 filtered, signed samples centred at 0
     */
    private fun parseStreamPacket(bytes: ByteArray) {
        if (bytes.size < STREAM_PACKET_SIZE) {
            Timber.w("Stream packet too short: ${bytes.size} bytes (expected $STREAM_PACKET_SIZE)")
            return
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // --- Header ---
        val seqNum = buf.get().toInt() and 0xFF
        val timestampMs = buf.int.toLong() and 0xFFFFFFFFL
        val leadOffByte = buf.get().toInt() and 0xFF

        // --- Dropped-packet detection ---
        val droppedBefore: Int = if (lastSeqNum == -1) {
            0  // First packet after (re)connect — nothing to compare against
        } else {
            val expected = (lastSeqNum + 1) and 0xFF
            if (seqNum != expected) {
                // Rolling subtraction handles the 255→0 wraparound correctly
                val dropped = (seqNum - expected + 256) and 0xFF
                Timber.w("Dropped $dropped packet(s): seq jumped $expected → $seqNum")
                dropped
            } else {
                0
            }
        }
        lastSeqNum = seqNum

        // --- Lead-off detection ---
        val leadsOff = leadOffByte == LEAD_OFF_VALUE
        if (_leadOffState.value != leadsOff) {
            _leadOffState.value = leadsOff
            if (leadsOff) Timber.w("Leads off (0x${leadOffByte.toString(16)})")
            else Timber.i("Leads reconnected")
        }

        // --- ECG samples ---
        val samples = ShortArray(ECG_SAMPLES_PER_PACKET) { buf.short }

        _ecgPacketFlow.value = EcgPacket(
            seqNum = seqNum,
            timestampMs = timestampMs,
            leadsOff = leadsOff,
            samples = samples,
            droppedBefore = droppedBefore
        )
    }

    /**
     * Parses a 10-byte little-endian inference packet:
     *
     * Byte 0      uint8    marker         always 0xFF
     * Bytes 1–4   uint32   timestamp_ms   when inference ran
     * Byte 5      uint8    class_id       0=Normal 1=AFib 2=Bradycardia 3=Tachycardia 4=Other
     * Bytes 6–9   float32  confidence     softmax probability 0.0–1.0
     */
    private fun parseInferencePacket(bytes: ByteArray) {
        if (bytes.size < INFERENCE_PACKET_SIZE) {
            Timber.w("Inference packet too short: ${bytes.size} bytes (expected $INFERENCE_PACKET_SIZE)")
            return
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val marker = buf.get().toInt() and 0xFF
        if (marker != INFERENCE_MARKER) {
            Timber.w("Unexpected inference marker: 0x${marker.toString(16)} (expected 0xFF)")
            return
        }

        val timestampMs = buf.int.toLong() and 0xFFFFFFFFL
        val classId = buf.get().toInt() and 0xFF
        val confidence = buf.float

        val className = CLASS_NAMES[classId] ?: "Unknown($classId)"
        Timber.i("Inference → $className  conf=${String.format("%.1f%%", confidence * 100)}  t=${timestampMs}ms")

        _inferenceResultFlow.value = InferenceResult(
            timestampMs = timestampMs,
            classId = classId,
            className = className,
            confidence = confidence
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    fun setError(message: String) {
        _connectionState.value = ConnectionState.Error(message)
    }

    fun isBluetoothEnabled(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    fun hasRequiredPermissions(): Boolean = BlePermissions.allGranted(context)

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    companion object {
        // Replace these with the actual UUIDs advertised by your ESP32 firmware
        private const val ECG_SERVICE_UUID                = "12345678-1234-1234-1234-123456789012"
        private const val ECG_STREAM_CHARACTERISTIC_UUID  = "12345678-1234-1234-1234-123456789013"
        private const val ECG_INFERENCE_CHARACTERISTIC_UUID = "12345678-1234-1234-1234-123456789014"

        private const val STREAM_PACKET_SIZE    = 26
        private const val INFERENCE_PACKET_SIZE = 10
        private const val ECG_SAMPLES_PER_PACKET = 10
        private const val LEAD_OFF_VALUE        = 0x03
        private const val INFERENCE_MARKER      = 0xFF

        /** Maps ESP32 class_id → human-readable label */
        val CLASS_NAMES = mapOf(
            0 to "Normal",
            1 to "AFib",
            2 to "Bradycardia",
            3 to "Tachycardia",
            4 to "Other"
        )

        /** Inter-sample interval in milliseconds (250 Hz → 4 ms) */
        const val SAMPLE_INTERVAL_MS = 4L

        /** Notification period in milliseconds */
        const val NOTIFICATION_INTERVAL_MS = 40L
    }
}

// =============================================================================
// Sealed connection state — unchanged
// =============================================================================

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Scanning : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val device: BleDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

// =============================================================================
// Data classes
// =============================================================================

data class BleDevice(val address: String, val name: String?, val rssi: Int)

/**
 * One decoded 26-byte stream packet from the ESP32.
 *
 * @param seqNum        Rolling 0–255 counter. Used to detect gaps.
 * @param timestampMs   ESP32 millis() at the start of this batch.
 * @param leadsOff      True if the ECG leads were disconnected during this batch.
 * @param samples       10 signed int16 ECG values, 4 ms apart, centred at 0.
 * @param droppedBefore Number of packets missing before this one.
 *                      Insert a gap in the waveform when this is > 0.
 */
data class EcgPacket(
    val seqNum: Int,
    val timestampMs: Long,
    val leadsOff: Boolean,
    val samples: ShortArray,
    val droppedBefore: Int
) {
    // ShortArray doesn't get structural equality for free, so we override manually
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EcgPacket) return false
        return seqNum == other.seqNum &&
                timestampMs == other.timestampMs &&
                leadsOff == other.leadsOff &&
                samples.contentEquals(other.samples) &&
                droppedBefore == other.droppedBefore
    }

    override fun hashCode(): Int {
        var result = seqNum
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + leadsOff.hashCode()
        result = 31 * result + samples.contentHashCode()
        result = 31 * result + droppedBefore
        return result
    }
}

/**
 * One decoded 10-byte inference packet from the ESP32.
 *
 * @param timestampMs   ESP32 millis() when inference ran.
 * @param classId       Raw class ID from the model (0–4).
 * @param className     Human-readable label (e.g. "AFib").
 * @param confidence    Softmax probability in [0.0, 1.0].
 */
data class InferenceResult(
    val timestampMs: Long,
    val classId: Int,
    val className: String,
    val confidence: Float
)