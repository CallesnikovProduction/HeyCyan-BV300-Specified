package com.fersaiyan.cyanbridge.localai.memory

import android.content.Context
import com.fersaiyan.cyanbridge.chat.ChatStore
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatRole

internal data class PreparedConversationContext(
    val conversationId: String,
    val messages: List<Map<String, String>>,
)

/** Keeps Room's raw chat separate from a bounded, persistent summary and the native KV epoch. */
internal class ConversationContextCoordinator(
    context: Context,
    private val loadMessages: (String) -> List<ChatMessage> = ChatStore::listMessages,
) {
    private val states = ConversationMemoryStateStore(context)

    suspend fun prepare(
        chatId: String,
        system: String,
        budgetSystem: String = system,
        currentRequest: String,
        summarize: suspend (previousSummary: String, oldTurns: List<ChatMessage>) -> String,
        retrievedMemories: List<String> = emptyList(),
        mediaReserve: Int = 0,
        excludedMessageIds: Set<String> = emptySet(),
        recovery: Boolean = false,
        assertTurnCurrent: suspend () -> Unit = {},
    ): PreparedConversationContext {
        assertTurnCurrent()
        var state = states.load(chatId)
        // A process death or barge-in may have left a streamed draft. Never treat it as a
        // completed assistant conclusion when reconstructing a conversation.
        state.pendingAssistantId?.let { pending ->
            state = state.copy(
                pendingAssistantId = null,
                interruptedAssistantIds = state.interruptedAssistantIds + pending,
            )
            states.save(chatId, state)
        }
        val all = loadMessages(chatId).filterNot {
            it.id in state.interruptedAssistantIds || it.id in excludedMessageIds
        }
        val cursorIndex = all.indexOfFirst { it.id == state.compactedThroughId }
        if (state.compactedThroughId != null && cursorIndex < 0) {
            // Chat was edited/deleted; a stale cursor must not erase the remaining messages.
            state = ConversationMemoryState(epoch = state.epoch + 1)
            states.save(chatId, state)
        }
        var history = if (cursorIndex >= 0) all.drop(cursorIndex + 1) else all
        val retrieved = ConversationContextPolicy.retrievalContext(if (recovery) emptyList() else retrievedMemories)
        val requestWithRetrieval = if (retrieved.isBlank()) currentRequest else "$retrieved\nТекущий запрос пользователя:\n$currentRequest"

        while (true) {
            val summaryText = state.summary.take(ConversationContextPolicy.SUMMARY_MAX_CHARS)
            val compactCount = ConversationContextPolicy.oldMessagesToCompact(
                budgetSystem, summaryText, history, requestWithRetrieval, mediaReserve, recovery,
            )
            if (compactCount == 0) {
                break
            }
            val batch = history.take(compactCount.coerceAtMost(6)).takeWhileWithinSummaryBudget()
            val newSummary = summarize(summaryText, batch)
                .trim()
            check(newSummary.isNotBlank()) { "Gemma returned an empty conversation summary" }
            check(newSummary.length <= ConversationContextPolicy.SUMMARY_MAX_CHARS) {
                "Gemma conversation summary exceeded its safe size; raw history remains intact"
            }
            assertTurnCurrent()
            state = state.copy(
                summary = newSummary,
                compactedThroughId = batch.last().id,
                epoch = state.epoch + 1,
            )
            states.save(chatId, state)
            history = history.drop(batch.size)
        }

        // The retry must not reuse a failed native Conversation/KV state, even if nothing
        // needed compaction. Persist the epoch before constructing its replacement.
        if (recovery) {
            assertTurnCurrent()
            state = state.copy(epoch = state.epoch + 1)
            states.save(chatId, state)
        }

        val contextMessages = buildList {
            add(mapOf("role" to "system", "content" to system))
            if (state.summary.isNotBlank()) {
                add(mapOf("role" to "user", "content" to ConversationContextPolicy.summaryContext(state.summary)))
            }
            history.forEach { message ->
                add(mapOf(
                    "role" to if (message.role == ChatRole.USER) "user" else "assistant",
                    "content" to message.content,
                ))
            }
            add(mapOf("role" to "user", "content" to requestWithRetrieval))
        }
        return PreparedConversationContext("$chatId:${state.epoch}", contextMessages)
    }

    fun markDraft(chatId: String, assistantMessageId: String) {
        val state = states.load(chatId)
        if (state.pendingAssistantId != assistantMessageId) {
            states.save(chatId, state.copy(pendingAssistantId = assistantMessageId))
        }
    }

    fun markCompleted(chatId: String, assistantMessageId: String?) {
        if (assistantMessageId == null) return
        val state = states.load(chatId)
        if (state.pendingAssistantId == assistantMessageId) {
            states.save(chatId, state.copy(pendingAssistantId = null))
        }
    }

    fun markInterrupted(chatId: String, assistantMessageId: String?) {
        if (assistantMessageId == null) return
        val state = states.load(chatId)
        states.save(chatId, state.copy(
            pendingAssistantId = if (state.pendingAssistantId == assistantMessageId) null else state.pendingAssistantId,
            interruptedAssistantIds = state.interruptedAssistantIds + assistantMessageId,
        ))
    }

    fun rollover(chatId: String) {
        val state = states.load(chatId)
        states.save(chatId, state.copy(epoch = state.epoch + 1))
    }

    private fun List<ChatMessage>.takeWhileWithinSummaryBudget(): List<ChatMessage> {
        val result = ArrayList<ChatMessage>()
        var chars = 0
        for (message in this) {
            val next = message.content.length + 32
            if (chars + next > 10_500) break
            result += message
            chars += next
        }
        check(result.isNotEmpty()) { "Old conversation turn is too long to compact without losing content" }
        return result
    }
}
