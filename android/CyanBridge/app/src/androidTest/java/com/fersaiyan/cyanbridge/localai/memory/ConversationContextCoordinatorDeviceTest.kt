package com.fersaiyan.cyanbridge.localai.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises persistence and rollover without loading Gemma or mutating the real chat database. */
@RunWith(AndroidJUnit4::class)
class ConversationContextCoordinatorDeviceTest {
    @Test fun compactionPersistsBeforeEvictionAndKeepsRecentTurns() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chatId = "test-${UUID.randomUUID()}"
        val history = (0 until 24).map { index ->
            ChatMessage(
                id = "turn-$index", chatId = chatId,
                role = if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
                content = "Тема $index: продолжаем обсуждать BV300 и память. ".repeat(30),
                createdAt = index.toLong(),
            )
        }
        val coordinator = ConversationContextCoordinator(context) { history }
        var summaryCalls = 0
        val first = coordinator.prepare(chatId, "Системная инструкция", currentRequest = "Что решили?",
            summarize = { previous, old ->
                summaryCalls++
                "${previous.takeLast(300)} Сохранено до ${old.last().id}.".trim()
            })
        assertTrue(summaryCalls > 0)
        assertTrue(first.messages.any { it["content"]?.contains("Сводка предыдущей беседы") == true })
        assertTrue(first.messages.any { it["content"]?.contains("Что решили?") == true })
        assertTrue(first.messages.any { it["content"]?.contains("Тема 23") == true })
        val saved = ConversationMemoryStateStore(context).load(chatId)
        assertTrue(saved.summary.isNotBlank())
        assertTrue(saved.compactedThroughId != null)
        val second = coordinator.prepare(chatId, "Системная инструкция", currentRequest = "А затем?",
            summarize = { _, _ -> error("No repeated compaction expected") })
        assertEquals(first.conversationId, second.conversationId)
        assertTrue(second.messages.any { it["content"]?.contains("Тема 23") == true })
        val recovered = coordinator.prepare(chatId, "Системная инструкция", currentRequest = "А затем?",
            summarize = { old, _ -> old.ifBlank { "Память BV300" } }, recovery = true)
        assertFalse(first.conversationId == recovered.conversationId)
    }

    @Test fun failedSummaryLeavesRawHistoryAndCursorUntouched() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chatId = "test-${UUID.randomUUID()}"
        val history = (0 until 18).map { index ->
            ChatMessage(id = "turn-$index", chatId = chatId,
                role = if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
                content = "Важный факт $index. ".repeat(90), createdAt = index.toLong())
        }
        val coordinator = ConversationContextCoordinator(context) { history }
        val before = ConversationMemoryStateStore(context).load(chatId)
        val failed = runCatching {
            coordinator.prepare(chatId, "Система", currentRequest = "Повтори факт",
                summarize = { _, _ -> error("Summarizer unavailable") })
        }
        assertTrue(failed.isFailure)
        assertEquals(before, ConversationMemoryStateStore(context).load(chatId))
        val retried = coordinator.prepare(chatId, "Система", currentRequest = "Повтори факт",
            summarize = { _, old -> "Важные факты: ${old.joinToString { it.id }}" })
        assertTrue(retried.messages.any { it["content"]?.contains("Важные факты") == true })
    }
}
