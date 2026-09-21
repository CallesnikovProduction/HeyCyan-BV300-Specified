package com.fersaiyan.cyanbridge.localai.audio

import kotlin.math.sqrt

/** Lightweight PCM gate for BV300's SDK audio frames. Vosk still decides the final words. */
class VoiceActivityDetector(
    private val sampleRateHz: Int = 16_000,
    private val trailingSilenceMs: Long = 950,
    private val maxDurationMs: Long = 30_000,
) {
    var speechDetected: Boolean = false
        private set
    private var processedSamples = 0L
    private var firstSpeechMs = -1L
    private var lastSpeechMs = -1L
    private var finished = false

    /** True means the SDK dialogue can be ended. Initial silence never ends it early. */
    fun accept(pcm16: ByteArray): Boolean {
        if (finished) return true
        val samples = pcm16.size / 2
        if (samples == 0) return false
        var sumSquares = 0.0
        var peak = 0
        for (index in 0 until samples) {
            val offset = index * 2
            val value = ((pcm16[offset].toInt() and 0xff) or
                (pcm16[offset + 1].toInt() shl 8)).toShort().toInt()
            sumSquares += value.toDouble() * value
            peak = maxOf(peak, kotlin.math.abs(value))
        }
        val nowMs = processedSamples * 1_000 / sampleRateHz
        processedSamples += samples
        val rms = sqrt(sumSquares / samples)
        if (rms >= 180.0 && peak >= 500) {
            speechDetected = true
            if (firstSpeechMs < 0) firstSpeechMs = nowMs
            lastSpeechMs = nowMs + samples * 1_000L / sampleRateHz
        }
        val elapsedMs = processedSamples * 1_000 / sampleRateHz
        finished = elapsedMs >= maxDurationMs || (
            speechDetected &&
                firstSpeechMs >= 0 &&
                lastSpeechMs - firstSpeechMs >= 350 &&
                elapsedMs - lastSpeechMs >= trailingSilenceMs
            )
        return finished
    }
}
