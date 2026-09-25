package com.fersaiyan.cyanbridge.localai

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.localai.model.LocalModelManager
import com.fersaiyan.cyanbridge.localai.model.FreshPhotoGuard
import com.fersaiyan.cyanbridge.localai.model.GemmaArtifact
import com.fersaiyan.cyanbridge.localai.memory.ConversationCompactor
import com.fersaiyan.cyanbridge.localai.memory.ConversationContextCoordinator
import com.fersaiyan.cyanbridge.localai.memory.ConversationContextPolicy
import com.fersaiyan.cyanbridge.localai.memory.ContextCapacityFailure
import com.fersaiyan.cyanbridge.localai.memory.SemanticConversationMemory
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
    private val conversationContext = ConversationContextCoordinator(context)
    private val compactor = ConversationCompactor(context, provider)
    private val semanticMemory = SemanticConversationMemory(context)

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
            val modelSettings = LocalModelSettingsRepository.getForModel(context, selected.id)
            check(modelSettings.modelRuntime == LocalModelRuntime.LITERT) {
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
            val arithmetic = if (image == null) SimpleSpokenArithmetic.answerIfUnambiguous(transcript) else null
            if (arithmetic != null) {
                ensureCurrent(requestId)
                ChatStore.addMessage(chatId, ChatRole.USER, transcript)
                if (!ownership.publishIfCurrent(requestId) {
                    ChatStore.addMessage(chatId, ChatRole.ASSISTANT, arithmetic)
                }) throw CancellationException("BV300 request was preempted before publishing its reply")
                conversationContext.rollover(chatId) // The native conversation did not see this deterministic answer.
                timings.firstSentence()
                onSentence(arithmetic)
                return arithmetic
            }
            val recentMessageIds = ChatStore.listMessages(chatId).takeLast(12).mapTo(mutableSetOf()) { it.id }
            val retrieved = try {
                semanticMemory.retrieve(transcript, recentMessageIds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("Bv300LocalAI", "Semantic retrieval unavailable; continuing without older memories", error)
                emptyList()
            }
            val requestContent = Bv300VoicePrompt.userContent(transcript)
            val budgetSystem = modelSettings.systemPromptOverride + "\n" + Bv300VoicePrompt.SYSTEM
            val mediaReserve = if (image != null) 1500 else 0
            // Keep the user's transcript in Room even if compaction or native inference fails.
            // Exclude it from reconstructed history because it is the current request below.
            val userMessage = ChatStore.addMessage(chatId, ChatRole.USER, transcript, imageAttachmentName = image?.name)
            val prepared = conversationContext.prepare(
                chatId = chatId,
                system = Bv300VoicePrompt.SYSTEM,
                budgetSystem = budgetSystem,
                currentRequest = requestContent,
                summarize = compactor::compact,
                retrievedMemories = retrieved,
                mediaReserve = mediaReserve,
                excludedMessageIds = setOf(userMessage.id),
                assertTurnCurrent = { ensureCurrent(requestId) },
            )
            ensureCurrent(requestId)

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
                        conversationContext.markDraft(chatId, checkNotNull(assistantMessageId))
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
                suspend fun generate(messages: List<Map<String, String>>, conversationId: String): String =
                    provider.streamChat(
                        context = context,
                        messages = messages,
                        onToken = ::acceptDelta,
                        imagePaths = image?.let { listOf(it.absolutePath) }.orEmpty(),
                        conversationId = conversationId,
                        contextSizeOverride = ConversationContextPolicy.ENGINE_TOKENS,
                        maxTokens = ConversationContextPolicy.OUTPUT_RESERVE,
                    ).trim()
                try {
                    generate(prepared.messages, prepared.conversationId)
                } catch (error: Exception) {
                    if (error is CancellationException || streamed.isNotEmpty() ||
                        !ContextCapacityFailure.isRecoverable(error)) throw error
                    ensureCurrent(requestId)
                    Log.w("Bv300LocalAI", "Context capacity failure; compacting and retrying current turn once", error)
                    val recovered = conversationContext.prepare(
                        chatId = chatId,
                        system = Bv300VoicePrompt.SYSTEM,
                        budgetSystem = budgetSystem,
                        currentRequest = requestContent,
                        summarize = compactor::compact,
                        mediaReserve = mediaReserve,
                        excludedMessageIds = setOf(userMessage.id),
                        recovery = true,
                        assertTurnCurrent = { ensureCurrent(requestId) },
                    )
                    ensureCurrent(requestId)
                    generate(recovered.messages, recovered.conversationId)
                }
            } catch (error: Throwable) {
                conversationContext.markInterrupted(chatId, assistantMessageId)
                throw error
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
            conversationContext.markCompleted(chatId, assistantMessageId)
            try {
                semanticMemory.indexCompletedTurn(chatId, userMessage.id, transcript, reply)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("Bv300LocalAI", "Semantic indexing unavailable; answer remains valid", error)
            }
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
            ChatStore.getThread(it) != null
        } ?: run {
            val created = ChatStore.createThread("BV300 Local AI · ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
            prefs.edit().putString("chat_id", created.id).putString("connection_session_id", sessionId).apply()
            created.id
        }
    }
}
