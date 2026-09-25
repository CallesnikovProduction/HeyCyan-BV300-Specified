package com.fersaiyan.cyanbridge.localai.memory

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.fersaiyan.cyanbridge.localai.embedding.EmbeddingGemmaEngine
import com.fersaiyan.cyanbridge.localai.embedding.EmbeddingGemmaFiles
import com.fersaiyan.cyanbridge.localai.embedding.EmbeddingRole

/** Optional, fully local episodic memory. Failure must not prevent the main assistant reply. */
internal class SemanticConversationMemory(private val context: Context) {
    val repository = SemanticMemoryRepository(context)
    private val embedder = EmbeddingGemmaEngine(context)

    suspend fun retrieve(request: String, recentMessageIds: Set<String>): List<String> {
        if (!repository.isEnabled() || !EmbeddingGemmaFiles.isReady(context)) return emptyList()
        if (!isMeaningfulRequest(request)) return emptyList()
        val started = SystemClock.elapsedRealtime()
        val query = embedder.embed(request.take(900), EmbeddingRole.QUERY)
        val selected = SemanticMemoryRetriever.rank(query, repository.all(), recentMessageIds)
        Log.i(TAG, "retrievalMs=${SystemClock.elapsedRealtime() - started} matches=${selected.size}")
        return selected.map(SemanticMemoryRecord::text)
    }

    suspend fun indexCompletedTurn(chatId: String, userMessageId: String, request: String, reply: String) {
        if (!repository.isEnabled() || !EmbeddingGemmaFiles.isReady(context)) return
        if (!shouldIndex(request, reply)) return
        val text = "Пользователь: ${request.take(500)}\nАссистент: ${reply.take(700)}"
        val vector = embedder.embed(text, EmbeddingRole.DOCUMENT)
        repository.put(chatId, userMessageId, text, vector)
    }

    companion object {
        private const val TAG = "Bv300SemanticMemory"
        private val filler = setOf("да", "нет", "ага", "спасибо", "понял", "продолжай", "ок", "okay", "yes", "no")

        fun isMeaningfulRequest(text: String): Boolean = text.trim().length >= 12 && text.trim().lowercase() !in filler
        fun shouldIndex(request: String, reply: String): Boolean =
            isMeaningfulRequest(request) && reply.isNotBlank()
    }
}
