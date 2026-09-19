package com.fersaiyan.cyanbridge.assistant

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Bv300AudioPreprocessorTest {
    @Test fun normalizesPcmWithoutChangingTheSampleCount() {
        val prepared = Bv300AudioPreprocessor.prepare(byteArrayOf(0xE8.toByte(), 0x03), 16_000)
        assertEquals(1, prepared.inputSamples)
        assertEquals(1, prepared.outputSamples)
        assertEquals(1_000, prepared.peak)
        assertEquals(4.0, prepared.gain, 0.0)
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 0x0F), prepared.bytes)
    }

    @Test fun writesPcm16MonoWavHeaderAndPayload() {
        val file = File.createTempFile("bv300-audio-test-", ".wav")
        try {
            val pcm = byteArrayOf(0x34, 0x12, 0x78, 0x56)
            Bv300AudioPreprocessor.writeWav(file, pcm, 16_000)
            val bytes = file.readBytes()
            assertEquals(48, bytes.size)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            assertEquals("data", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
            assertEquals(16_000, le32(bytes, 24))
            assertEquals(4, le32(bytes, 40))
            assertArrayEquals(pcm, bytes.copyOfRange(44, 48))
        } finally {
            file.delete()
        }
    }

    @Test fun rejectsMalformedPcmBeforeWriting() {
        assertThrows(IllegalArgumentException::class.java) {
            Bv300AudioPreprocessor.prepare(byteArrayOf(1), 16_000)
        }
        val file = File.createTempFile("bv300-audio-test-", ".wav")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                Bv300AudioPreprocessor.writeWav(file, byteArrayOf(1, 2), 0)
            }
        } finally {
            file.delete()
        }
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
