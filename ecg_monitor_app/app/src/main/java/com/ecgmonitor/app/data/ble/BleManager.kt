package com.ecgmonitor.app.data.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral
import com.juul.kable.Scanner
import com.juul.kable.characteristicOf
import com.juul.kable.logs.Logging
import com.juul.kable.peripheral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /**
     * Hot stream of fully-parsed ECG packets.
     *
     * A [MutableSharedFlow], not a StateFlow: StateFlow conflates, so a collector
     * still processing packet N would silently lose N+1 at 25 packets/s.
     *
     * Configured with:
     *   - extraBufferCapacity = 64  →  64 × 40 ms = 2.56 s of headroom
     *   - DROP_OLDEST on overflow   →  if the ViewModel truly falls behind,
     *     stale data is discarded rather than blocking the BLE IO thread
     *
     * The ViewModel collects on [Dispatchers.Default] so in normal operation the
     * buffer never fills; it is a safety valve for transient slowdowns.
     */
    private val _ecgPacketFlow = MutableSharedFlow<EcgPacket>(
        extraBufferCapacity = ECG_FLOW_BUFFER,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val ecgPacketFlow: SharedFlow<EcgPacket> = _ecgPacketFlow.asSharedFlow()

    /**
     * Hot stream of beat windows from the ESP32 for app-side classification.
     * Each beat window contains:
     *   - 128 filtered ECG samples (int16)
     *   - 3 RR interval features (float32)
     *
     * Beat windows arrive ~once per heartbeat so 8 slots is more than sufficient;
     * DROP_OLDEST ensures a slow consumer never blocks the BLE IO thread.
     */
    private val _beatWindowFlow = MutableSharedFlow<BeatWindow>(
        extraBufferCapacity = BEAT_FLOW_BUFFER,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val beatWindowFlow: SharedFlow<BeatWindow> = _beatWindowFlow.asSharedFlow()

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
    private var lastPacketTimeMs = 0L

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
        lastPacketTimeMs = 0L

        try {
            val p = scope.peripheral(device.address) {
                logging { level = Logging.Level.Events }
            }
            peripheral = p

            val streamCharacteristic = characteristicOf(
                service        = ECG_SERVICE_UUID,
                characteristic = ECG_STREAM_CHARACTERISTIC_UUID
            )
            val beatCharacteristic = characteristicOf(
                service        = ECG_SERVICE_UUID,
                characteristic = ECG_BEAT_CHARACTERISTIC_UUID
            )

            Timber.d("Registering observations (before connect)…")
            val streamFlow: Flow<ByteArray> = p.observe(streamCharacteristic)
            val beatFlow: Flow<ByteArray>   = p.observe(beatCharacteristic)
            Timber.d("Observations registered — calling connect()")

            p.connect()
            Timber.i("GATT connected to ${device.name}")

            p.services?.forEach { service ->
                Timber.d("Service: ${service.serviceUuid}")
                service.characteristics.forEach { char ->
                    Timber.d("  Char: ${char.characteristicUuid}  props=${char.properties}")
                }
            }

            Timber.d("About to request MTU...")  // ← ADD THIS

            // We request 512; the ESP32 (NimBLE setMTU(600)) should negotiate to 600
            // giving a 597-byte payload, sufficient for 519-byte beat packets + 26-byte stream.
            // Without this, notifications are truncated to 20 bytes.
            val negotiatedMtu = try {
                Timber.d("Calling requestMtu(600)...")  // ← ADD THIS
                (p as AndroidPeripheral).requestMtu(REQUESTED_MTU)
            } catch (e: Exception) {
                Timber.e(e, "MTU negotiation exception")  // ← CHANGE from .w to .e
                ATT_HEADER_BYTES + 20
            }

            val maxPayload = negotiatedMtu - ATT_HEADER_BYTES
            Timber.i("MTU negotiated: ${negotiatedMtu}B  →  max payload: ${maxPayload}B")

            if (maxPayload < BEAT_PACKET_SIZE) {
                Timber.w(
                    "MTU payload (${maxPayload}B) < beat packet size (${BEAT_PACKET_SIZE}B). " +
                            "Beat windows will fail. Ensure NimBLEDevice::setMTU(600) on ESP32."
                )
            }
            if (maxPayload < STREAM_PACKET_SIZE) {
                Timber.e(
                    "MTU critically low: ${maxPayload}B payload < ${STREAM_PACKET_SIZE}B stream packet. " +
                            "Even ECG stream will fail."
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
                        lastPacketTimeMs = 0L
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
                Timber.d("Beat window collector started — awaiting notifications…")
                beatFlow
                    .catch { cause ->
                        Timber.w("Beat window flow error (non-fatal): ${cause.message}")
                    }
                    .collect { bytes -> parseBeatPacket(bytes) }
                Timber.d("Beat window flow completed")
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
        lastPacketTimeMs = 0L
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
     *
     * If the firmware was updated and the packet is now larger than [STREAM_PACKET_SIZE],
     * the extra bytes are ignored and a warning is logged so the ECG stream keeps flowing
     * while you investigate. Update [STREAM_PACKET_SIZE] and this parser once the new
     * layout is confirmed.
     */
    @VisibleForTesting
    internal fun parseStreamPacket(bytes: ByteArray) {
        rawPacketCount++

        // ── Inter-packet gap diagnostic ───────────────────────────────────────────
        // Expected gap: ~NOTIFICATION_INTERVAL_MS (40 ms).
        // Gaps > 2× that indicate BLE stack stalls, ESP32 stopped notifying, or
        // Android throttling background BLE callbacks — all of which appear as
        // "interrupted stream" on the chart.
        val now = System.currentTimeMillis()
        if (lastPacketTimeMs > 0) {
            val gapMs = now - lastPacketTimeMs
            if (gapMs > NOTIFICATION_INTERVAL_MS * 2) {
                Timber.w(
                    "Stream gap: ${gapMs}ms since last packet " +
                            "(expected ~${NOTIFICATION_INTERVAL_MS}ms) at packet #$rawPacketCount"
                )
            }
        }
        lastPacketTimeMs = now

        // ── First-5 hex dump ──────────────────────────────────────────────────────
        // Captures exact raw bytes so you can verify the layout matches the parser.
        // Check: packet #1 size, byte[0]=seqNum, bytes[1..4]=timestamp, byte[5]=leadOff.
        if (rawPacketCount <= 5) {
            val hex = bytes.joinToString(" ") { "%02X".format(it) }
            Timber.d("RAW stream packet #$rawPacketCount (${bytes.size}B): $hex")
        } else if (rawPacketCount == 6) {
            Timber.d("Raw-dump suppressed after packet #5 (stream flowing)")
        }

        // ── Size guards ───────────────────────────────────────────────────────────
        if (bytes.size < STREAM_PACKET_SIZE) {
            Timber.w(
                "Stream packet too short: ${bytes.size}B (need $STREAM_PACKET_SIZE). " +
                        "MTU not negotiated — check ESP32 NimBLEDevice::setMTU(185) call."
            )
            return
        }
        if (bytes.size > STREAM_PACKET_SIZE) {
            // Likely cause: new firmware appended a classification byte or other field.
            // We still parse the ECG samples correctly from the first 26 bytes.
            // The stream keeps flowing — update STREAM_PACKET_SIZE when the layout is confirmed.
            Timber.w(
                "Stream packet oversized: ${bytes.size}B (expected $STREAM_PACKET_SIZE). " +
                        "Extra ${bytes.size - STREAM_PACKET_SIZE}B ignored. " +
                        "Check config.h DataPacket — did firmware add a classification byte?"
            )
        }

        // ── Parse ─────────────────────────────────────────────────────────────────
        val buf         = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val seqNum      = buf.get().toInt() and 0xFF
        val timestampMs = buf.int.toLong() and 0xFFFFFFFFL
        val leadOffByte = buf.get().toInt() and 0xFF

        // ── Sequence check ────────────────────────────────────────────────────────
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

        // ── Lead-off state ────────────────────────────────────────────────────────
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

        // ── Emit ──────────────────────────────────────────────────────────────────
        // tryEmit() is non-blocking. With a 64-slot buffer it should never return
        // false. If it does, the ViewModel processing coroutine is severely stalled
        // and we need to know immediately.
        val emitted = _ecgPacketFlow.tryEmit(
            EcgPacket(
                seqNum        = seqNum,
                timestampMs   = timestampMs,
                leadsOff      = leadsOff,
                samples       = samples,
                droppedBefore = droppedBefore
            )
        )
        if (!emitted) {
            Timber.e(
                "ECG SharedFlow buffer full — packet seq=$seqNum dropped at BleManager. " +
                        "ViewModel processing is stalled. Verify collectors run on Dispatchers.Default."
            )
        }
    }

    /**
     * Parses a 275-byte little-endian beat window packet.
     *
     * Layout (matches config.h BeatPacket):
     * - [0]         uint8      marker          always 0xBE
     * - [1-4]       uint32     timestamp_ms    R-peak time (LE)
     * - [5-6]       uint16     beat_num        rolling beat counter (LE)
     * - [7-262]     int16[128] ecg             filtered beat window (LE)
     * - [263-274]   float[3]   rr_features     [pre_rr_norm, post_rr_norm, ratio] (LE)
     *
     * The beat window is sent to the ViewModel where TFLite runs classification.
     */
    @VisibleForTesting
    internal fun parseBeatPacket(bytes: ByteArray) {
        if (bytes.size < BEAT_PACKET_SIZE) {
            Timber.w("Beat packet too short: ${bytes.size}B (expected $BEAT_PACKET_SIZE)")
            return
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val marker = buf.get().toInt() and 0xFF

        if (marker != BEAT_MARKER) {
            Timber.w("Unexpected beat marker: 0x${marker.toString(16)} (expected 0xBE)")
            return
        }

        val timestampMs = buf.int.toLong() and 0xFFFFFFFFL
        val beatNum = buf.short.toInt() and 0xFFFF

        // Parse 128 int16 ECG samples
        val ecgSamples = ShortArray(BEAT_WINDOW_SIZE) { buf.short }

        // Parse 3 float32 RR features
        val rrFeatures = FloatArray(RR_FEATURE_COUNT) { buf.float }

        Timber.d(
            "Beat #$beatNum → t=${timestampMs}ms  " +
                    "ecg[0..2]=${ecgSamples[0]},${ecgSamples[1]},${ecgSamples[2]}  " +
                    "rr=${rrFeatures.joinToString(",") { "%.3f".format(it) }}"
        )

        // Emit beat window to ViewModel for classification
        val emitted = _beatWindowFlow.tryEmit(
            BeatWindow(
                beatNum = beatNum,
                timestampMs = timestampMs,
                ecgSamples = ecgSamples,
                rrFeatures = rrFeatures
            )
        )

        if (!emitted) {
            Timber.e(
                "Beat window SharedFlow buffer full — beat #$beatNum dropped at BleManager. " +
                        "ViewModel classification processing is stalled."
            )
        }
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
        private const val ECG_SERVICE_UUID               = "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
        private const val ECG_STREAM_CHARACTERISTIC_UUID = "beb5483e-36e1-4688-b7f5-ea07361b26a8"
        private const val ECG_BEAT_CHARACTERISTIC_UUID   = "beb5483f-36e1-4688-b7f5-ea07361b26a8"

        private const val STREAM_PACKET_SIZE  = 26
        private const val BEAT_PACKET_SIZE    = 275
        private const val ECG_SAMPLES_PER_PACKET = 10
        private const val BEAT_WINDOW_SIZE    = 128
        private const val RR_FEATURE_COUNT    = 3
        private const val BEAT_MARKER         = 0xBE

        /** 3-byte ATT protocol header consumed from every BLE notification MTU. */
        private const val ATT_HEADER_BYTES = 3

        /**
         * MTU requested from the peripheral. The ESP32 (NimBLE setMTU(600)) should
         * negotiate to 600, yielding a 597-byte payload sufficient for both:
         *   - 26-byte stream packets (ECG waveform)
         *   - 519-byte beat packets (beat windows for classification)
         * Requesting 600 ensures we accept whatever the peripheral offers.
         */
        private const val REQUESTED_MTU = 512

        /**
         * SharedFlow buffer sizes.
         *
         * ECG: 64 × 40 ms = 2.56 s of burst headroom before DROP_OLDEST fires.
         * Beat: ~1/beat, 8 slots is ample even at 200 BPM.
         */
        private const val ECG_FLOW_BUFFER  = 64
        private const val BEAT_FLOW_BUFFER = 8

        const val SAMPLE_INTERVAL_MS       = 4L
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

/**
 * Beat window packet received from ESP32 for app-side classification.
 *
 * Contains a 128-sample ECG window centered on an R-peak, plus RR interval
 * features. The ViewModel uses ArrhythmiaClassifier to run TFLite inference
 * on this data.
 */
data class BeatWindow(
    val beatNum     : Int,        // Rolling beat counter (0-65535)
    val timestampMs : Long,       // R-peak timestamp from ESP32
    val ecgSamples  : ShortArray, // 128 filtered ECG samples (int16)
    val rrFeatures  : FloatArray  // [pre_rr_norm, post_rr_norm, ratio]
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BeatWindow) return false
        return beatNum      == other.beatNum      &&
                timestampMs  == other.timestampMs  &&
                ecgSamples.contentEquals(other.ecgSamples) &&
                rrFeatures.contentEquals(other.rrFeatures)
    }

    override fun hashCode(): Int {
        var result = beatNum
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + ecgSamples.contentHashCode()
        result = 31 * result + rrFeatures.contentHashCode()
        return result
    }
}

// endregion