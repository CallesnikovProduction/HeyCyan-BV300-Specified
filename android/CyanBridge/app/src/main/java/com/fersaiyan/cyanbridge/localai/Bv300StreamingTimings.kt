package com.fersaiyan.cyanbridge.localai

import android.os.SystemClock
import android.util.Log
import com.fersaiyan.cyanbridge.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only, text-free timings measured from the BV300 assistant button press. */
internal class Bv300StreamingTimings(
    private val requestId: String,
    startedAtElapsedNanos: Long,
) {
    private val startedAt = startedAtElapsedNanos.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos()
    private val firstTokenSeen = AtomicBoolean()
    private val firstSentenceSeen = AtomicBoolean()
    private val firstSynthesisSeen = AtomicBoolean()
    private val firstAudioSeen = AtomicBoolean()

    init { mark("request_started") }

    fun firstToken() { if (firstTokenSeen.compareAndSet(false, true)) mark("first_gemma_token") }
    fun firstSentence() { if (firstSentenceSeen.compareAndSet(false, true)) mark("first_sentence") }
    fun synthesisStarted() { if (firstSynthesisSeen.compareAndSet(false, true)) mark("tts_synthesis_started") }
    fun firstAudio() { if (firstAudioSeen.compareAndSet(false, true)) mark("first_audio_played") }
    fun generationFinished() = mark("gemma_generation_finished")
    fun finalAudioFinished() = mark("final_audio_finished")

    private fun mark(event: String) {
        if (BuildConfig.DEBUG) {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L
            Log.d("BV300StreamTiming", "requestId=$requestId event=$event elapsedMs=$elapsedMs")
        }
    }
}
