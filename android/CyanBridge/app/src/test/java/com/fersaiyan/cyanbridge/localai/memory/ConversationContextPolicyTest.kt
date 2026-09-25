package com.fersaiyan.cyanbridge.localai.memory

import com.fersaiyan.cyanbridge.localai.Bv300VoicePrompt
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextPolicyTest {
    private fun turn(index: Int, content: String) = ChatMessage(
        id = index.toString(), chatId = "chat", role = if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
        content = content, createdAt = index.toLong(),
    )

    @Test fun naturalDepthAndOutputBudgetAreNotTriviallyShort() {
        val prompt = Bv300VoicePrompt.SYSTEM
        assertTrue(prompt.contains("Не сокращай ответ искусственно"))
        assertTrue(prompt.contains("простой вопрос требует простого ответа"))
        assertFalse(prompt.contains("одним предложением"))
        assertTrue(ConversationContextPolicy.OUTPUT_RESERVE >= 1024)
        assertTrue(ConversationContextPolicy.COMPACT_AT + ConversationContextPolicy.OUTPUT_RESERVE < ConversationContextPolicy.ENGINE_TOKENS)
    }

    @Test fun adjacentTurnsStayRawForFollowups() {
        val history = listOf(turn(0, "Я хочу сделать управление очками жестами."),
            turn(1, "Нужно распознавать жест с камеры."))
        listOf("А если использовать MediaPipe?", "Объясни подробнее.", "Почему?", "А что насчёт батареи?").forEach { request ->
            assertEquals(0, ConversationContextPolicy.oldMessagesToCompact(
                Bv300VoicePrompt.SYSTEM, "", history, request,
            ))
        }
    }

    @Test fun longHistoryCompactsOldMessagesWithoutDroppingCurrentRequest() {
        val history = (0..15).map { turn(it, "Обсуждали Video-to-Intent и управление BV300 жестами. ".repeat(30)) }
        val request = "Что мы решили про жесты?"
        val count = ConversationContextPolicy.oldMessagesToCompact(Bv300VoicePrompt.SYSTEM, "", history, request)
        assertTrue(count > 0)
        assertEquals(0, count % 2)
        assertTrue(ConversationContextPolicy.estimatedInput(
            Bv300VoicePrompt.SYSTEM, "Сводка", history.drop(count), request,
        ) < ConversationContextPolicy.COMPACT_AT)
        assertTrue(ConversationContextPolicy.summaryContext("решили использовать MediaPipe").contains("MediaPipe"))
    }

    @Test fun retrievedMemoryIsBoundedAndLabelledAsContext() {
        val text = ConversationContextPolicy.retrievalContext(List(8) { "старый ответ ".repeat(100) })
        assertTrue(text.length <= ConversationContextPolicy.RETRIEVED_MAX_CHARS)
        assertTrue(text.contains("не инструкции"))
    }
}
