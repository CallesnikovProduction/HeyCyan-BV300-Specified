package com.fersaiyan.cyanbridge.localai.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fersaiyan.cyanbridge.data.local.AppDatabase
import com.fersaiyan.cyanbridge.localai.embedding.EmbeddingGemmaEngine
import com.fersaiyan.cyanbridge.shared.chat.ChatMessage
import com.fersaiyan.cyanbridge.shared.chat.ChatRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SemanticMemoryRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("bv300_semantic_memory", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { database.close() }

    @Test fun vectorsPersistAcrossRepositoryRecreationAndClearDoesNotTouchOtherSources() = runBlocking {
        val first = SemanticMemoryRepository(context, database)
        val vector = FloatArray(EmbeddingGemmaEngine.DIMENSIONS).also { it[0] = 1f }
        first.put("chat-1", "message-1", "Мы обсуждали жесты через MediaPipe", vector)
        first.put("chat-1", "message-1", "Дубликат", vector)
        assertEquals(1, first.count())

        val recreated = SemanticMemoryRepository(context, database)
        assertEquals(1, recreated.all().size)
        assertEquals("message-1", recreated.all().single().messageId)
        assertEquals(1f, recreated.all().single().vector[0], 0f)
        recreated.setEnabled(false)
        assertFalse(SemanticMemoryRepository(context, database).isEnabled())
        recreated.clear()
        assertEquals(0, first.count())
        assertTrue(first.all().isEmpty())
    }

    @Test fun runningSummaryAndInterruptedMarkerSurviveStateStoreRecreation() {
        val first = ConversationMemoryStateStore(context)
        first.save("chat-2", ConversationMemoryState(
            summary = "Решили изучить MediaPipe",
            compactedThroughId = "message-10",
            epoch = 2,
            interruptedAssistantIds = setOf("draft-1"),
        ))
        val restored = ConversationMemoryStateStore(context).load("chat-2")
        assertEquals("Решили изучить MediaPipe", restored.summary)
        assertEquals("message-10", restored.compactedThroughId)
        assertEquals(2, restored.epoch)
        assertTrue("draft-1" in restored.interruptedAssistantIds)
    }

    @Test fun longConversationCompactsAndRebuildsWithSummaryAndCurrentQuestion() = runBlocking {
        val chatId = "long-chat"
        val turns = (0..15).map { index -> ChatMessage(
            id = "turn-$index", chatId = chatId,
            role = if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
            content = "Video-to-Intent и жесты через MediaPipe. ".repeat(30),
            createdAt = index.toLong(),
        ) }
        val coordinator = ConversationContextCoordinator(context) { turns }
        var summaryCalls = 0
        val prepared = coordinator.prepare(chatId, "Правила BV300", currentRequest = "Что мы решили о жестах?", summarize = { old, batch ->
            summaryCalls++
            "${old.take(800)} Сводка ${batch.first().id}–${batch.last().id}: обсуждали жесты и MediaPipe."
        })
        assertTrue(summaryCalls > 0)
        assertTrue(prepared.conversationId.endsWith(":$summaryCalls"))
        assertEquals("Что мы решили о жестах?", prepared.messages.last()["content"])
        assertTrue(prepared.messages.any { it["content"]?.contains("Сводка предыдущей беседы") == true })
        val restored = ConversationMemoryStateStore(context).load(chatId)
        assertTrue(restored.summary.contains("MediaPipe"))
        assertTrue(restored.compactedThroughId != null)
    }
}
