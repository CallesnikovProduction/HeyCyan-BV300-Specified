package com.fersaiyan.cyanbridge.localai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

internal data class Bv300SpeechChunk(val requestId: String, val index: Int, val text: String)
internal data class Bv300AudioChunk(val speech: Bv300SpeechChunk, val file: File)
internal data class Bv300PipelineResult(val reply: String, val speechFailures: Int)

/** One turn, bounded text/audio queues, one synthesizer and one playback owner. */
internal class Bv300StreamingPipeline(
    private val requestId: String,
    private val ownership: Bv300TurnOwnership,
    private val synthesize: suspend (Bv300SpeechChunk) -> File,
    private val play: suspend (Bv300AudioChunk) -> Unit,
    private val onSpeechError: (Throwable) -> Unit = {},
) {
    suspend fun run(produce: suspend (emitSentence: (String) -> Unit) -> String): Bv300PipelineResult = coroutineScope {
        val textQueue = Channel<Bv300SpeechChunk>(capacity = 3)
        val audioQueue = Channel<Bv300AudioChunk>(capacity = 2, onUndeliveredElement = { it.file.delete() })
        val producerContext = currentCoroutineContext()
        val failures = AtomicInteger()
        var nextIndex = 0

        val synthesizer = launch(Dispatchers.IO) {
            try {
                for (sentence in textQueue) {
                    ensureActive()
                    ownership.requireCurrent(sentence.requestId)
                    var file: File? = null
                    var handedOff = false
                    try {
                        file = synthesize(sentence)
                        ensureActive()
                        ownership.requireCurrent(sentence.requestId)
                        audioQueue.send(Bv300AudioChunk(sentence, file))
                        handedOff = true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        failures.incrementAndGet()
                        runCatching { onSpeechError(error) }
                    } finally {
                        if (!handedOff) file?.delete()
                    }
                }
            } finally {
                audioQueue.close()
            }
        }
        val player = launch(Dispatchers.IO) {
            for (audio in audioQueue) {
                try {
                    ensureActive()
                    ownership.requireCurrent(audio.speech.requestId)
                    play(audio)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures.incrementAndGet()
                    runCatching { onSpeechError(error) }
                } finally {
                    audio.file.delete()
                }
            }
        }

        try {
            var producerError: Throwable? = null
            var reply = ""
            try {
                reply = produce { sentence ->
                    ownership.requireCurrent(requestId)
                    val chunk = Bv300SpeechChunk(requestId, nextIndex++, sentence)
                    if (textQueue.trySend(chunk).isFailure) {
                        // Only when the bounded queue is full: apply backpressure, never drop speech.
                        runBlocking(producerContext) { textQueue.send(chunk) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                producerError = error
            }
            textQueue.close()
            joinAll(synthesizer, player)
            producerError?.let { throw it }
            ownership.requireCurrent(requestId)
            Bv300PipelineResult(reply, failures.get())
        } finally {
            textQueue.cancel()
            audioQueue.cancel()
            synthesizer.cancel()
            player.cancel()
        }
    }
}
