package com.fersaiyan.cyanbridge.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsModelsTest {
    @Test
    fun `bounded history keeps newest events`() {
        val buffer = BoundedDiagnosticsBuffer(capacity = 2)
        buffer.add(event(1))
        buffer.add(event(2))
        val result = buffer.add(event(3))

        assertEquals(listOf(3L, 2L), result.map { it.id })
    }

    @Test
    fun `clear removes bounded history`() {
        val buffer = BoundedDiagnosticsBuffer(capacity = 2)
        buffer.add(event(1))

        assertTrue(buffer.clear().isEmpty())
    }

    @Test
    fun `connection aggregation preserves unrelated diagnostics state`() {
        val initial = DiagnosticsState(scannerStatus = "SCANNING", rawEventCount = 7)
        val connected = DiagnosticsStateAggregator.reduce(initial, DiagnosticsSignal.Connected)

        assertEquals(DiagnosticsConnectionStatus.CONNECTED, connected.connectionStatus)
        assertEquals("SCANNING", connected.scannerStatus)
        assertEquals(7, connected.rawEventCount)
    }

    @Test
    fun `known vendor frame is classified without inventing a button`() {
        val bytes = ByteArray(8).also { it[6] = 0x03 }
        val result = DiagnosticsRawClassifier.classify(bytes)

        assertTrue(result.known)
        assertEquals("AI voice activation", result.name)
        assertEquals(InputObservability.RAW_TRANSPORT_EVENT, result.observability)
    }

    @Test
    fun `unknown frame remains unknown`() {
        val result = DiagnosticsRawClassifier.classify(byteArrayOf(0x01, 0x02))

        assertFalse(result.known)
        assertEquals("Unknown raw event", result.name)
        assertEquals(InputObservability.UNKNOWN, result.observability)
    }

    @Test
    fun `raw formatting is unsigned bounded and reports truncation`() {
        val result = DiagnosticsRawFormatter.toHex(byteArrayOf(0x00, 0xFF.toByte(), 0x7F), maxBytes = 2)

        assertEquals("00 FF … (+1 bytes)", result)
    }

    @Test
    fun `large possible media payload is not rendered as hex`() {
        val result = DiagnosticsRawFormatter.toSafeHex(ByteArray(129) { 0x41 })

        assertTrue(result.contains("payload omitted"))
        assertFalse(result.contains("41 41"))
    }

    private fun event(id: Long) = DiagnosticsEvent(
        id = id,
        timestampMs = id,
        source = DiagnosticsSource.APPLICATION,
        category = DiagnosticsCategory.CALLBACK,
        name = "event-$id",
    )
}
