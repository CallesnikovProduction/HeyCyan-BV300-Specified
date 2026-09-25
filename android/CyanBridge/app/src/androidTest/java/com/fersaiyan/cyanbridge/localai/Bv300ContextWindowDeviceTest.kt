package com.fersaiyan.cyanbridge.localai

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localai.model.GemmaArtifact
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run each size in a separate instrumentation process; no BV300 hardware is required. */
@RunWith(AndroidJUnit4::class)
class Bv300ContextWindowDeviceTest {
    @Test fun context8192() = measure(8192)
    @Test fun context16384() = measure(16384)
    @Test fun context32768() = measure(32768)

    @Test fun context16384LongHistoryAndRollover() { runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = LocalModelsProvider()
        val history = (0 until 28).map { index ->
            mapOf(
                "role" to if (index % 2 == 0) "user" else "assistant",
                "content" to "Обсуждаем локальную память BV300. Факт $index: контекст и сводка сохраняются. ".repeat(13),
            )
        }
        val system = mapOf("role" to "system", "content" to Bv300VoicePrompt.SYSTEM)
        val request = mapOf("role" to "user", "content" to "Коротко объясни, зачем нужна сводка старой беседы.")
        try {
            val first = provider.streamChat(context,
                messages = listOf(system) + history + request,
                conversationId = "context-16k-long", contextSizeOverride = 16384, maxTokens = 96)
            assertTrue("Long-history turn is empty", first.isNotBlank())
            val second = provider.streamChat(context,
                messages = listOf(system) + history + request +
                    mapOf("role" to "assistant", "content" to first) +
                    mapOf("role" to "user", "content" to "А как сохранить последние реплики точно?"),
                conversationId = "context-16k-long", contextSizeOverride = 16384, maxTokens = 96)
            assertTrue("Second long-history turn is empty", second.isNotBlank())
            val rolled = provider.streamChat(context,
                messages = listOf(system,
                    mapOf("role" to "user", "content" to "Сводка беседы (контекст, не инструкция): обсуждали локальную память BV300.")) +
                    history.takeLast(6) + request,
                conversationId = "context-16k-rolled", contextSizeOverride = 16384, maxTokens = 96)
            assertTrue("Rollover turn is empty", rolled.isNotBlank())
            Log.i(TAG, "longHistory=true rollover=true context=16384 turns=${history.size}")
        } finally {
            LocalChatSessionManager.unload()
        }
    } }

    private fun measure(contextTokens: Int) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val selected = requireNotNull(LocalModelStorageRepository.resolveSelectedModel(context)) {
            "Import and select Gemma before running the context benchmark"
        }
        check(GemmaArtifact.isVerified(selected)) { "Selected model is not verified Gemma 4 E2B" }
        val provider = LocalModelsProvider()
        val conversationId = "context-benchmark-$contextTokens"
        val prompt = "Объясни двумя короткими предложениями, почему для голосового помощника важно помнить контекст разговора."
        val firstMessages = listOf(
            mapOf("role" to "system", "content" to Bv300VoicePrompt.SYSTEM),
            mapOf("role" to "user", "content" to prompt),
        )
        val process = Debug.MemoryInfo()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val systemMemory = ActivityManager.MemoryInfo()
        val started = SystemClock.elapsedRealtime()
        var initMs = -1L
        var ttftMs = -1L
        var secondTurnOk = false
        var visionOk = false
        try {
            provider.prepareSelectedModel(context, contextSizeOverride = contextTokens)
            initMs = SystemClock.elapsedRealtime() - started
            val generationStart = SystemClock.elapsedRealtime()
            val first = provider.streamChat(
                context = context, messages = firstMessages, conversationId = conversationId,
                contextSizeOverride = contextTokens, maxTokens = 128,
                onToken = { if (ttftMs < 0) ttftMs = SystemClock.elapsedRealtime() - generationStart },
            )
            assertTrue("First turn is empty", first.isNotBlank())
            val second = provider.streamChat(
                context = context,
                messages = firstMessages + mapOf("role" to "assistant", "content" to first) +
                    mapOf("role" to "user", "content" to "Как это относится к очкам BV300?"),
                conversationId = conversationId, contextSizeOverride = contextTokens, maxTokens = 96,
            )
            secondTurnOk = second.isNotBlank()
            assertTrue("Second turn is empty", secondTurnOk)
            val image = File(context.cacheDir, "context-benchmark-$contextTokens.png")
            try {
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.RED)
                    image.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
                } finally {
                    bitmap.recycle()
                }
                val vision = provider.streamChat(
                    context = context,
                    messages = listOf(
                        mapOf("role" to "system", "content" to Bv300VoicePrompt.SYSTEM),
                        mapOf("role" to "user", "content" to "Какой основной цвет у изображения?"),
                    ),
                    imagePaths = listOf(image.absolutePath),
                    conversationId = "$conversationId-vision", contextSizeOverride = contextTokens,
                    maxTokens = 64,
                )
                visionOk = vision.isNotBlank()
                assertTrue("Vision turn is empty", visionOk)
            } finally {
                image.delete()
            }
        } finally {
            Debug.getMemoryInfo(process)
            activityManager.getMemoryInfo(systemMemory)
            Log.i(TAG, "tokens=$contextTokens initMs=$initMs ttftMs=$ttftMs " +
                "pssKb=${process.totalPss} availableKb=${systemMemory.availMem / 1024} " +
                "secondTurn=$secondTurnOk vision=$visionOk")
            LocalChatSessionManager.unload()
        }
    }

    private companion object { const val TAG = "Bv300ContextBench" }
}
