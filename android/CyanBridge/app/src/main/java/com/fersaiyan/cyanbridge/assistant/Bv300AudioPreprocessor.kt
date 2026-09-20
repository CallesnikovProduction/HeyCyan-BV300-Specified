package com.fersaiyan.cyanbridge.assistant

import com.fersaiyan.cyanbridge.ai.transcription.SilenceCompactor
import java.io.File
import java.io.FileOutputStream

/** Converts decoded BV300 mono PCM into the WAV consumed by the existing STT pipeline. */
internal object Bv300AudioPreprocessor {
    data class PreparedPcm(
        val bytes: ByteArray,
        val inputSamples: Int,
        val outputSamples: Int,
        val peak: Int,
        val gain: Double,
    )

    fun prepare(pcm16: ByteArray, sampleRateHz: Int, compactSilence: Boolean = true): PreparedPcm {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(pcm16.size % 2 == 0) { "PCM16 must contain complete samples" }
        val samples = ShortArray(pcm16.size / 2)
        for (index in samples.indices) {
            val offset = index * 2
            samples[index] = (
                (pcm16[offset].toInt() and 0xff) or
                    (pcm16[offset + 1].toInt() shl 8)
                ).toShort()
        }
        val compacted = if (compactSilence) SilenceCompactor.compactMonoPcm(samples, sampleRateHz) else samples
        val peak = compacted.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        val gain = if (peak in 1 until 24_000) {
            (24_000.0 / peak).coerceAtMost(4.0)
        } else {
            1.0
        }
        val output = ByteArray(compacted.size * 2)
        compacted.forEachIndexed { index, sample ->
            val normalized = (sample.toDouble() * gain)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val offset = index * 2
            output[offset] = (normalized and 0xff).toByte()
            output[offset + 1] = ((normalized ushr 8) and 0xff).toByte()
        }
        return PreparedPcm(output, samples.size, compacted.size, peak, gain)
    }

    fun writeWav(file: File, pcm16: ByteArray, sampleRateHz: Int) {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(pcm16.size % 2 == 0) { "PCM16 must contain complete samples" }
        require(pcm16.size.toLong() + 36L <= Int.MAX_VALUE) { "PCM16 is too large for WAV" }
        val header = ByteArray(44)
        fun writeAscii(offset: Int, value: String) {
            value.forEachIndexed { index, character -> header[offset + index] = character.code.toByte() }
        }
        fun writeLe16(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }
        fun writeLe32(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value ushr 8) and 0xff).toByte()
            header[offset + 2] = ((value ushr 16) and 0xff).toByte()
            header[offset + 3] = ((value ushr 24) and 0xff).toByte()
        }

        writeAscii(0, "RIFF")
        writeLe32(4, 36 + pcm16.size)
        writeAscii(8, "WAVE")
        writeAscii(12, "fmt ")
        writeLe32(16, 16)
        writeLe16(20, 1)
        writeLe16(22, 1)
        writeLe32(24, sampleRateHz)
        writeLe32(28, sampleRateHz * 2)
        writeLe16(32, 2)
        writeLe16(34, 16)
        writeAscii(36, "data")
        writeLe32(40, pcm16.size)

        FileOutputStream(file).use { output ->
            output.write(header)
            output.write(pcm16)
        }
    }
}
