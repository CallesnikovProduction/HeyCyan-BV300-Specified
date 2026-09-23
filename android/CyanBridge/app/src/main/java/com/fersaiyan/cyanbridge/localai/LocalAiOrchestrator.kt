package com.fersaiyan.cyanbridge.localai

import android.content.Context
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
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
import kotlinx.coroutines.withContext
import java.io.File

/** Processes a BV300 utterance; only its current requestId may publish a result. */
class LocalAiOrchestrator(
    private val context: Context,
    private val stt: RussianSpeechRecognizer,
    private val captureFreshPhoto: suspend (String, Long) -> File,
    private val ownership: Bv300TurnOwnership = bv300TurnOwnership,
    private val visualIntent: VisualIntentDecision = VisualIntentRouter,
) {
    private val provider = LocalModelsProvider()

    internal suspend fun process(
        pcm16: ByteArray,
        sampleRateHz: Int,
        requestId: String,
        timings: Bv300StreamingTimings,
        onSentence: (String) -> Unit,
    ): String {
        ownership.requireCurrent(requestId)
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
            updateIfCurrent(requestId) { it.copy(phase = LocalAiPhase.FINALIZING_STT, error = null) }
            val transcript = stt.recognizePcm16(pcm16, sampleRateHz) { partial ->
                updateIfCurrent(requestId) { it.copy(partialTranscript = partial) }
            }.trim()
            check(transcript.isNotBlank()) { "Vosk did not recognize speech. Try speaking closer to BV300." }
            ensureCurrent(requestId)
            updateIfCurrent(requestId) { it.copy(phase = LocalAiPhase.THINKING, transcript = transcript, partialTranscript = "") }

            val image = if (visualIntent.requiresVision(transcript)) {
                val requestStartedAtMs = System.currentTimeMillis()
                captureFreshPhoto(requestId, requestStartedAtMs).also { photo ->
                    check(FreshPhotoGuard.isOwnedFresh(photo, requestId, requestStartedAtMs)) {
                        "BV300 returned no fresh photo belonging to this request"
                    }
                }
            } else null
            ensureCurrent(requestId)

            val chatId = assistantChatId()
            val history = ChatStore.listMessages(chatId).takeLast(10).map { message ->
                mapOf(
                    "role" to if (message.role == ChatRole.USER) "user" else "assistant",
                    "content" to message.content,
                )
            }
            val messages = listOf(
                mapOf("role" to "system", "content" to Bv300VoicePrompt.SYSTEM),
            ) + history + mapOf("role" to "user", "content" to Bv300VoicePrompt.userContent(transcript))
            ensureCurrent(requestId)
            ChatStore.addMessage(chatId, ChatRole.USER, transcript, imageAttachmentName = image?.name)
            ensureCurrent(requestId)
            val arithmetic = if (image == null) SimpleSpokenArithmetic.answerIfUnambiguous(transcript) else null
            if (arithmetic != null) {
                if (!ownership.publishIfCurrent(requestId) {
                    ChatStore.addMessage(chatId, ChatRole.ASSISTANT, arithmetic)
                }) throw CancellationException("BV300 request was preempted before publishing its reply")
                timings.firstSentence()
                onSentence(arithmetic)
                return arithmetic
            }

            val sentenceChunker = SentenceChunker()
            val streamed = StringBuilder()
            var assistantMessageId: String? = null
            var lastChatPublishNanos = 0L
            fun publishDraft(force: Boolean = false) {
                val content = streamed.toString().trim()
                if (content.isBlank()) return
                val now = System.nanoTime()
                if (!force && assistantMessageId != null && now - lastChatPublishNanos < 180_000_000L) return
                if (!ownership.publishIfCurrent(requestId) {
                    val id = assistantMessageId
                    if (id == null) {
                        assistantMessageId = ChatStore.addMessage(chatId, ChatRole.ASSISTANT, content).id
                    } else {
                        check(ChatStore.updateAssistantMessage(chatId, id, content)) { "Assistant draft disappeared" }
                    }
                }) throw CancellationException("BV300 request was preempted while streaming its reply")
                lastChatPublishNanos = now
            }
            fun acceptDelta(delta: String) {
                if (delta.isEmpty()) return
                ownership.requireCurrent(requestId)
                timings.firstToken()
                streamed.append(delta)
                publishDraft()
                sentenceChunker.append(delta).forEach { sentence ->
                    timings.firstSentence()
                    onSentence(sentence)
                }
            }

            updateIfCurrent(requestId) { it.copy(modelGenerating = true) }
            val modelReply = try {
                provider.streamChat(
                    context = context,
                    messages = messages,
                    onToken = ::acceptDelta,
                    imagePaths = image?.let { listOf(it.absolutePath) }.orEmpty(),
                ).trim()
            } finally {
                updateIfCurrent(requestId) { it.copy(modelGenerating = false) }
                timings.generationFinished()
                if (ownership.isCurrent(requestId)) publishDraft(force = true)
            }
            ensureCurrent(requestId)
            val streamedRaw = streamed.toString()
            val reply = when {
                streamedRaw.isBlank() -> modelReply
                modelReply.startsWith(streamedRaw) -> modelReply
                else -> streamedRaw.trim() // Never replace already-spoken tokens with a divergent retry/result.
            }
            check(reply.isNotBlank()) { "Gemma returned an empty response" }
            if (streamedRaw.isBlank()) {
                acceptDelta(reply)
            } else if (reply.length > streamedRaw.length && reply.startsWith(streamedRaw)) {
                acceptDelta(reply.substring(streamedRaw.length))
            }
            sentenceChunker.finish().forEach { sentence ->
                timings.firstSentence()
                onSentence(sentence)
            }
            publishDraft(force = true)
            ensureCurrent(requestId)
            return reply
        } catch (cancelled: CancellationException) {
            if (ownership.isCurrent(requestId)) {
                withContext(NonCancellable + Dispatchers.IO) { provider.cancelGeneration() }
            }
            throw cancelled
        } catch (error: Throwable) {
            updateIfCurrent(requestId) { it.copy(phase = LocalAiPhase.ERROR, error = error.message ?: "Local assistant failed") }
            throw error
        }
    }

    private suspend fun ensureCurrent(requestId: String) {
        currentCoroutineContext().ensureActive()
        ownership.requireCurrent(requestId)
    }

    private fun updateIfCurrent(requestId: String, transform: (LocalAiSnapshot) -> LocalAiSnapshot) {
        ownership.updateIfCurrent(requestId, transform)
    }

    private suspend fun assistantChatId(): String = withContext(Dispatchers.IO) {
        val sessionId = MoyoungW620Manager.getInstance(context).connectionSessionId
            ?: error("BV300 disconnected before starting the local assistant")
        val prefs = context.getSharedPreferences("bv300_local_assistant", Context.MODE_PRIVATE)
        prefs.getString("chat_id", null)?.takeIf {
            prefs.getString("connection_session_id", null) == sessionId && ChatStore.getThread(it) != null
        } ?: run {
            val created = ChatStore.createThread("BV300 Local AI · ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
            prefs.edit().putString("chat_id", created.id).putString("connection_session_id", sessionId).apply()
            created.id
        }
    }
}
