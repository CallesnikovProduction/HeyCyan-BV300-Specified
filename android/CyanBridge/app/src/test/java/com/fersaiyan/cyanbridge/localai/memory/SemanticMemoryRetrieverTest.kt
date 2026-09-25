package com.fersaiyan.cyanbridge.localai.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticMemoryRetrieverTest {
    private fun record(id: String, x: Float, y: Float) = SemanticMemoryRecord(
        id, id, floatArrayOf(x, y),
    )

    @Test fun relevantOlderDiscussionRanksAboveUnrelatedTopics() {
        val records = listOf(record("gestures", 1f, 0f), record("tts", 0f, 1f), record("wifi", -1f, 0f))
        val hits = SemanticMemoryRetriever.rank(floatArrayOf(1f, 0f), records, emptySet(), threshold = .3f)
        assertEquals(listOf("gestures"), hits.map { it.messageId })
        assertEquals("tts", SemanticMemoryRetriever.rank(floatArrayOf(0f, 1f), records, emptySet()).first().messageId)
    }

    @Test fun topKThresholdAndRecentDuplicateFilteringAreEnforced() {
        val records = listOf(record("a", 1f, 0f), record("b", .9f, .1f), record("c", .8f, .2f))
        val hits = SemanticMemoryRetriever.rank(floatArrayOf(1f, 0f), records, setOf("a"), topK = 1, threshold = .5f)
        assertEquals(listOf("b"), hits.map { it.messageId })
        assertTrue(SemanticMemoryRetriever.rank(floatArrayOf(0f, 1f), records, emptySet(), threshold = .9f).isEmpty())
    }

    @Test fun meaninglessTinyTurnsCannotPolluteTheIndex() {
        assertFalse(SemanticConversationMemory.shouldIndex("да", "Да"))
        assertFalse(SemanticConversationMemory.shouldIndex("продолжай", "Хорошо"))
        assertTrue(SemanticConversationMemory.shouldIndex(
            "Мы говорили об управлении очками жестами?",
            "Да, обсуждали распознавание движения через камеру и MediaPipe, а также расход батареи.",
        ))
        assertTrue(SemanticConversationMemory.shouldIndex("Запомни слово: синий маяк", "Запомнил."))
    }

    @Test fun deviceMeasuredUnrelatedSentenceIsNotInjectedIntoContext() {
        val records = listOf(
            SemanticMemoryRecord("password", "relevant", floatArrayOf(.727f)),
            SemanticMemoryRecord("weather", "unrelated", floatArrayOf(.647f)),
        )
        assertEquals(listOf("password"),
            SemanticMemoryRetriever.rank(floatArrayOf(1f), records, emptySet()).map { it.messageId })
    }
}
