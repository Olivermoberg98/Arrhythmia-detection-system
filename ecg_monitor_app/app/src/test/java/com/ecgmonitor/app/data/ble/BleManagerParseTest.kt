package com.ecgmonitor.app.data.ble

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for the BLE packet parsers, against the config.h DataPacket (26 B) and
 * BeatPacket (275 B) layouts. A byte-order or offset slip here silently feeds the
 * classifier garbage, so every field is checked at a value that exposes it.
 */
class BleManagerParseTest {

    private val ble = BleManager(ContextWrapper(null))

    // region Stream packets

    @Test
    fun `stream packet fields decode little-endian and unsigned`() {
        val samples = shortArrayOf(-2048, -1, 0, 1, 2047, -300, 300, 12, -12, 1000)
        val packets = capture(ble.ecgPacketFlow) {
            ble.parseStreamPacket(streamPacket(seq = 200, timestampMs = 0xF000_0001L, samples = samples))
        }

        assertEquals(1, packets.size)
        val p = packets[0]
        assertEquals(200, p.seqNum)
        assertEquals(0xF000_0001L, p.timestampMs)
        assertFalse(p.leadsOff)
        assertArrayEquals(samples, p.samples)
        assertEquals(0, p.droppedBefore)
    }

    @Test
    fun `lead-off byte drives packet flag and leadOffState`() {
        val packets = capture(ble.ecgPacketFlow) {
            ble.parseStreamPacket(streamPacket(seq = 0, leadOff = 0x02))
            assertTrue(ble.leadOffState.value)
            ble.parseStreamPacket(streamPacket(seq = 1, leadOff = 0x00))
            assertFalse(ble.leadOffState.value)
        }

        assertEquals(listOf(true, false), packets.map { it.leadsOff })
    }

    @Test
    fun `short stream packet is dropped`() {
        val packets = capture(ble.ecgPacketFlow) {
            ble.parseStreamPacket(streamPacket(seq = 0).copyOf(STREAM_SIZE - 1))
        }

        assertTrue(packets.isEmpty())
    }

    @Test
    fun `oversized stream packet parses its first 26 bytes`() {
        val samples = ShortArray(SAMPLES_PER_PACKET) { (it * 100).toShort() }
        val packets = capture(ble.ecgPacketFlow) {
            ble.parseStreamPacket(streamPacket(seq = 7, samples = samples) + byteArrayOf(1, 2, 3, 4))
        }

        assertEquals(1, packets.size)
        assertEquals(7, packets[0].seqNum)
        assertArrayEquals(samples, packets[0].samples)
    }

    @Test
    fun `sequence gaps count dropped packets across the uint8 wrap`() {
        val packets = capture(ble.ecgPacketFlow) {
            listOf(10, 11, 14, 255, 0, 2).forEach { ble.parseStreamPacket(streamPacket(seq = it)) }
        }

        // 11 follows 10; 12-13 missing; 15-254 missing; 0 follows 255; 1 missing.
        assertEquals(listOf(0, 0, 2, 240, 0, 1), packets.map { it.droppedBefore })
    }

    // endregion

    // region Beat packets

    @Test
    fun `beat packet fields decode little-endian`() {
        val ecg = ShortArray(BEAT_WINDOW) { (it * 37 - 2000).toShort() }
        val rr = floatArrayOf(0.95f, 1.12f, 0.848f)
        val beats = capture(ble.beatWindowFlow) {
            ble.parseBeatPacket(beatPacket(timestampMs = 0xFFFF_FFFFL, beatNum = 0xFFFF, ecg = ecg, rr = rr))
        }

        assertEquals(1, beats.size)
        val b = beats[0]
        assertEquals(0xFFFF, b.beatNum)
        assertEquals(0xFFFF_FFFFL, b.timestampMs)
        assertArrayEquals(ecg, b.ecgSamples)
        assertArrayEquals(rr, b.rrFeatures, 0f)
    }

    @Test
    fun `beat packet with the wrong marker is dropped`() {
        val beats = capture(ble.beatWindowFlow) {
            ble.parseBeatPacket(beatPacket(marker = 0xBF))
        }

        assertTrue(beats.isEmpty())
    }

    @Test
    fun `short beat packet is dropped`() {
        val beats = capture(ble.beatWindowFlow) {
            ble.parseBeatPacket(beatPacket().copyOf(BEAT_SIZE - 1))
        }

        assertTrue(beats.isEmpty())
    }

    // endregion

    // region Helpers

    /** Runs [block] with a live subscriber, since a replay-0 SharedFlow drops unobserved emits. */
    private fun <T> capture(flow: SharedFlow<T>, block: () -> Unit): List<T> = runBlocking {
        val received = mutableListOf<T>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { flow.collect { received.add(it) } }
        block()
        yield()
        job.cancel()
        received
    }

    private fun streamPacket(
        seq: Int,
        timestampMs: Long = 1_000L,
        leadOff: Int = 0x00,
        samples: ShortArray = ShortArray(SAMPLES_PER_PACKET)
    ): ByteArray = ByteBuffer.allocate(STREAM_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(seq.toByte())
        putInt(timestampMs.toInt())
        put(leadOff.toByte())
        samples.forEach { putShort(it) }
    }.array()

    private fun beatPacket(
        marker: Int = 0xBE,
        timestampMs: Long = 5_000L,
        beatNum: Int = 1,
        ecg: ShortArray = ShortArray(BEAT_WINDOW),
        rr: FloatArray = floatArrayOf(1f, 1f, 1f)
    ): ByteArray = ByteBuffer.allocate(BEAT_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
        put(marker.toByte())
        putInt(timestampMs.toInt())
        putShort(beatNum.toShort())
        ecg.forEach { putShort(it) }
        rr.forEach { putFloat(it) }
    }.array()

    private companion object {
        const val STREAM_SIZE = 26
        const val SAMPLES_PER_PACKET = 10
        const val BEAT_SIZE = 275
        const val BEAT_WINDOW = 128
    }

    // endregion
}
