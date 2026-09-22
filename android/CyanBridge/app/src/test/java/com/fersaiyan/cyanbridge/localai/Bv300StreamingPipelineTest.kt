package com.fersaiyan.cyanbridge.localai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Bv300StreamingPipelineTest {
    @Test fun generationContinuesWhileEarlierSentencePlaysAndAudioStaysOrdered() = runBlocking {
        val owner = Bv300TurnOwnership().also { it.begin("A") }
        val firstPlaying = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val played = mutableListOf<Int>()
        val pipeline = pipeline(owner, play = { audio ->
            if (audio.speech.index == 0) {
                firstPlaying.complete(Unit)
                releaseFirst.await()
            }
            played += audio.speech.index
        })
        val result = withTimeout(3_000) {
            pipeline.run { emit ->
                emit("First sentence.")
                firstPlaying.await()
                emit("Second sentence.")
                assertFalse(releaseFirst.isCompleted)
                releaseFirst.complete(Unit)
                "First sentence. Second sentence."
            }
        }
        assertEquals("First sentence. Second sentence.", result.reply)
        assertEquals(listOf(0, 1), played)
        assertEquals(0, result.speechFailures)
    }

    @Test fun bargeInCancelsGenerationPlaybackAndQueuedOldSentences() = runBlocking {
        val owner = Bv300TurnOwnership().also { it.begin("A") }
        val firstPlaying = CompletableDeferred<Unit>()
        val played = mutableListOf<Int>()
        val pipeline = pipeline(owner, play = { audio ->
            played += audio.speech.index
            if (audio.speech.index == 0) {
                firstPlaying.complete(Unit)
                delay(10_000)
            }
        })
        val oldTurn = async {
            pipeline.run { emit ->
                emit("First.")
                emit("Queued second.")
                firstPlaying.await()
                delay(10_000) // Gemma is still generating while audio is playing.
                "unfinished"
            }
        }
        withTimeout(3_000) { firstPlaying.await() }
        assertEquals("A", owner.begin("B")) // New listening does not await old TTS.
        oldTurn.cancelAndJoin()
        assertTrue(owner.isCurrent("B"))
        assertEquals(listOf(0), played)
    }

    @Test fun lateSynthesisFromCancelledTurnCannotPlay() = runBlocking {
        val owner = Bv300TurnOwnership().also { it.begin("A") }
        val synthesisStarted = CompletableDeferred<Unit>()
        val played = mutableListOf<Int>()
        val pipeline = Bv300StreamingPipeline(
            requestId = "A",
            ownership = owner,
            synthesize = { chunk ->
                synthesisStarted.complete(Unit)
                Thread.sleep(120) // Simulates non-cooperative native Supertonic synthesis.
                tempWav(chunk.index)
            },
            play = { audio -> played += audio.speech.index },
        )
        val oldTurn = async {
            pipeline.run { emit -> emit("Old sentence."); delay(10_000); "old" }
        }
        withTimeout(3_000) { synthesisStarted.await() }
        owner.begin("B")
        oldTurn.cancelAndJoin()
        assertTrue(played.isEmpty())
    }

    @Test fun oneTtsFailureDoesNotStopGemmaOrReorderRemainingAudio() = runBlocking {
        val owner = Bv300TurnOwnership().also { it.begin("A") }
        val played = mutableListOf<Int>()
        val pipeline = Bv300StreamingPipeline(
            requestId = "A",
            ownership = owner,
            synthesize = { chunk ->
                if (chunk.index == 1) error("TTS failed")
                tempWav(chunk.index)
            },
            play = { audio -> played += audio.speech.index },
        )
        val result = withTimeout(3_000) {
            pipeline.run { emit ->
                emit("One."); emit("Two."); emit("Three.")
                "One. Two. Three."
            }
        }
        assertEquals("One. Two. Three.", result.reply)
        assertEquals(listOf(0, 2), played)
        assertEquals(1, result.speechFailures)
    }

    private fun pipeline(
        owner: Bv300TurnOwnership,
        play: suspend (Bv300AudioChunk) -> Unit,
    ) = Bv300StreamingPipeline(
        requestId = "A",
        ownership = owner,
        synthesize = { chunk -> tempWav(chunk.index) },
        play = play,
    )

    private fun tempWav(index: Int): File = File.createTempFile("bv300_test_$index", ".wav").apply {
        writeBytes(byteArrayOf(1))
        deleteOnExit()
    }
}
