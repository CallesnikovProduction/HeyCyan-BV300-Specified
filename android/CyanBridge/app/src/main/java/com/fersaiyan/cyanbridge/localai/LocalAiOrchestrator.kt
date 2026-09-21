package com.fersaiyan.cyanbridge.localai

import android.content.Context
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.localai.model.LocalModelManager
import com.fersaiyan.cyanbridge.localai.model.FreshPhotoGuard
import com.fersaiyan.cyanbridge.localai.model.GemmaArtifact
import com.fersaiyan.cyanbridge.localai.stt.RussianSpeechRecognizer
import com.fersaiyan.cyanbridge.localmodels.provider.LocalModelsProvider
import com.fersaiyan.cyanbridge.localmodels.remote.RemoteOpenAiPrefs
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelRuntime
import com.fersaiyan.cyanbridge.localmodels.settings.LocalModelSettingsRepository
import com.fersaiyan.cyanbridge.localmodels.storage.LocalModelStorageRepository
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/** Owns one BV300 utterance at a time; hardware capture and TTS remain in the device/UI layer. */
class LocalAiOrchestrator(
    private val context: Context,
    private val stt: RussianSpeechRecognizer,
    private val captureFreshPhoto: suspend (Long) -> File,
) {
    private val turnMutex = Mutex()
    private val provider = LocalModelsProvider()

    suspend fun process(pcm16: ByteArray, sampleRateHz: Int): String {
        check(turnMutex.tryLock()) { "Local assistant is already processing a request" }
        try {
            check(LocalModelManager.hasVosk(context)) { "Vosk model missing. Import the Russian ZIP in Local Models." }
            check(!RemoteOpenAiPrefs.isActive(context)) { "Remote model is enabled. Switch to local Gemma in Local Models." }
            val selected = LocalModelStorageRepository.resolveSelectedModel(context)
                ?: error("Gemma model missing. Import gemma-4-E2B-it.litertlm in Local Models.")
            check(GemmaArtifact.isVerified(selected)) {
                "Gemma file is not the verified E2B bundle. Re-download gemma-4-E2B-it.litertlm and import it again."
            }
            check(LocalModelSettingsRepository.getForModel(context, selected.id).modelRuntime == LocalModelRuntime.LITERT) {
                "Set Gemma runtime to LiteRT in Local Models."
            }
            LocalAiRuntime.update { it.copy(phase = LocalAiPhase.FINALIZING_STT, error = null) }
            val transcript = stt.recognizePcm16(pcm16, sampleRateHz) { partial ->
                LocalAiRuntime.update { it.copy(partialTranscript = partial) }
            }.trim()
            check(transcript.isNotBlank()) { "Vosk did not recognize speech. Try speaking closer to BV300." }
            currentCoroutineContext().ensureActive()
            LocalAiRuntime.update { it.copy(phase = LocalAiPhase.THINKING, transcript = transcript, partialTranscript = "") }

            val image = if (VisualIntentRouter.needsFreshPhoto(transcript)) {
                val requestStartedAtMs = System.currentTimeMillis()
                captureFreshPhoto(requestStartedAtMs).also { photo ->
                    check(FreshPhotoGuard.isFresh(photo, requestStartedAtMs)) {
                        "BV300 returned no fresh photo for this request"
                    }
                }
            } else null

            val chatId = assistantChatId()
            val history = ChatStore.listMessages(chatId).takeLast(10).map { message ->
                mapOf(
                    "role" to if (message.role == ChatRole.USER) "user" else "assistant",
                    "content" to message.content,
                )
            }
            val messages = listOf(
                mapOf("role" to "system", "content" to "Отвечай по-русски, кратко и понятно для голосового ответа в очках."),
            ) + history + mapOf("role" to "user", "content" to transcript)
            ChatStore.addMessage(chatId, ChatRole.USER, transcript)
            val reply = provider.streamChat(
                context = context,
                messages = messages,
                imagePaths = image?.let { listOf(it.absolutePath) }.orEmpty(),
            ).trim()
            currentCoroutineContext().ensureActive()
            check(reply.isNotBlank()) { "Gemma returned an empty response" }
            ChatStore.addMessage(chatId, ChatRole.ASSISTANT, reply)
            LocalAiRuntime.update { it.copy(phase = LocalAiPhase.SPEAKING) }
            return reply
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { provider.cancelGeneration() }
            LocalAiRuntime.update {
                it.copy(phase = LocalAiPhase.ERROR, error = cancelled.message ?: "Generation cancelled")
            }
            throw cancelled
        } catch (error: Throwable) {
            LocalAiRuntime.update { it.copy(phase = LocalAiPhase.ERROR, error = error.message ?: "Local assistant failed") }
            throw error
        } finally {
            turnMutex.unlock()
        }
    }

    private suspend fun assistantChatId(): String = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("bv300_local_assistant", Context.MODE_PRIVATE)
        prefs.getString("chat_id", null)?.takeIf { ChatStore.getThread(it) != null } ?: run {
            val created = ChatStore.createThread("BV300 Local AI")
            prefs.edit().putString("chat_id", created.id).apply()
            created.id
        }
    }
}
