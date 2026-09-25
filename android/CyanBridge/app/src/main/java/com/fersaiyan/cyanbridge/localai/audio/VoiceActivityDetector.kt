package com.fersaiyan.cyanbridge.localai.audio

import kotlin.math.sqrt

internal enum class EndpointReason { TRAILING_SILENCE, MAX_DURATION }

/** Lightweight PCM gate for BV300's SDK audio frames. Vosk still decides the final words. */
class VoiceActivityDetector(
    private val sampleRateHz: Int = 16_000,
    private val trailingSilenceMs: Long = 1_200,
    private val maxDurationMs: Long = 30_000,
) {
    var speechDetected: Boolean = false
        private set
    internal var endpointReason: EndpointReason? = null
        private set
    var currentRms: Double = 0.0
        private set
    var noiseFloor: Double = 80.0
        private set
    val durationMs: Long get() = processedSamples * 1_000 / sampleRateHz
    private var processedSamples = 0L
    private var firstSpeechMs = -1L
    private var lastSpeechMs = -1L
    private var candidateSpeechMs = 0L
    private var candidateStartMs = -1L
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
        val frameMs = samples * 1_000L / sampleRateHz
        val rms = sqrt(sumSquares / samples)
        currentRms = rms
        // The SDK supplies one mono PCM stream, not a directional voice signal. Track quiet
        // frames so a steady ambient level does not hold the endpoint open indefinitely.
        val threshold = maxOf(180.0, noiseFloor * if (speechDetected) 1.65 else 2.3)
        val active = rms >= threshold && peak >= threshold * 2.0
        if (active) {
            if (candidateStartMs < 0) candidateStartMs = nowMs
            candidateSpeechMs += frameMs
            // Once an utterance is established, the first resumed frame resets the grace
            // period; waiting for another 250 ms here could cut off a resumed word.
            if (speechDetected) lastSpeechMs = nowMs + frameMs
            if (candidateSpeechMs >= 250) {
                speechDetected = true
                if (firstSpeechMs < 0) firstSpeechMs = candidateStartMs
                lastSpeechMs = nowMs + frameMs
            }
        } else {
            candidateSpeechMs = 0
            candidateStartMs = -1
            val rate = if (rms < noiseFloor) 0.12 else 0.015
            noiseFloor = (noiseFloor * (1.0 - rate) + rms * rate).coerceIn(40.0, 4_000.0)
        }
        val elapsedMs = durationMs
        val silenceComplete = (
            speechDetected &&
                firstSpeechMs >= 0 &&
                elapsedMs - lastSpeechMs >= trailingSilenceMs
            )
        endpointReason = when {
            elapsedMs >= maxDurationMs -> EndpointReason.MAX_DURATION
            silenceComplete -> EndpointReason.TRAILING_SILENCE
            else -> null
        }
        finished = endpointReason != null
        return finished
    }
}
