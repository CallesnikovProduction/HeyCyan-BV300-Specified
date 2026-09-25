package com.fersaiyan.cyanbridge.localai

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.localai.embedding.EmbeddingGemmaEngine
import com.fersaiyan.cyanbridge.localai.embedding.EmbeddingGemmaFiles
import com.fersaiyan.cyanbridge.localai.embedding.EmbeddingRole
import com.fersaiyan.cyanbridge.localai.memory.ConversationContextPolicy
import com.fersaiyan.cyanbridge.localai.model.LocalModelManager
import com.fersaiyan.cyanbridge.localai.stt.VoskRussianSpeechRecognizer
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.session.LocalChatSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Phone-only native memory check; it does not claim BV300 capture or playback validation. */
@RunWith(AndroidJUnit4::class)
class Bv300ModelCoexistenceDeviceTest {
    @Test fun voskEmbeddingGemmaSupertonicAndGemmaCoexist() { runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(LocalModelManager.hasVosk(context)) { "Vosk model missing" }
        check(EmbeddingGemmaFiles.isReady(context)) { "EmbeddingGemma model missing" }
        check(LocalModelManager.isValidSupertonicDirectory(LocalModelManager.supertonicDirectory(context))) {
            "Supertonic model missing"
        }
        val wav = File(context.cacheDir, "bv300-coexistence-test.wav")
        try {
            // A silent frame loads Vosk without falsely claiming recognition accuracy.
            VoskRussianSpeechRecognizer(context).recognizePcm16(ByteArray(16_000 * 2), 16_000) {}
            val vector = EmbeddingGemmaEngine(context).embed("Память очков BV300", EmbeddingRole.QUERY)
            assertEquals(768, vector.size)
            val provider = LocalModelsProvider()
            provider.prepareSelectedModel(context, contextSizeOverride = ConversationContextPolicy.ENGINE_TOKENS)
            val reply = provider.streamChat(
                context = context,
                messages = listOf(
                    mapOf("role" to "system", "content" to Bv300VoicePrompt.SYSTEM),
                    mapOf("role" to "user", "content" to "Ответь одним коротким предложением: сколько будет два плюс два?"),
                ),
                conversationId = "bv300-coexistence", contextSizeOverride = ConversationContextPolicy.ENGINE_TOKENS,
                maxTokens = 96,
            )
            assertTrue(reply.isNotBlank())
            SupertonicTts.synthesize(context, "Два плюс два равно четыре.", wav)
            assertTrue(wav.isFile && wav.length() > 44)
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            Log.i("Bv300Coexistence", "context=${ConversationContextPolicy.ENGINE_TOKENS} " +
                "pssKb=${memory.totalPss} ttsBytes=${wav.length()} replyChars=${reply.length}")
        } finally {
            wav.delete()
            SupertonicTts.release()
            LocalChatSessionManager.unload()
        }
    } }
}
