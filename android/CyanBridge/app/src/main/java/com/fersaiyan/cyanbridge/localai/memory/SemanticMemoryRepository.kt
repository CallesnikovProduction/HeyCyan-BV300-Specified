package com.fersaiyan.cyanbridge.localai.memory

import android.content.Context
import com.fersaiyan.cyanbridge.data.local.AppDatabase
import com.fersaiyan.cyanbridge.data.local.entity.LocalEmbeddingStoreEntity
import com.fersaiyan.cyanbridge.data.local.entity.MemoryChunk
import com.fersaiyan.cyanbridge.localai.embedding.EmbeddingGemmaEngine
import com.fersaiyan.cyanbridge.ui.MyApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

internal data class SemanticMemoryRecord(
    val messageId: String,
    val text: String,
    val vector: FloatArray,
)

/** Reuses Room's text chunks and vector table without mixing in the legacy 64-D hash index. */
internal class SemanticMemoryRepository(
    private val context: Context,
    private val database: AppDatabase = MyApplication.database,
) {
    private val prefs = context.applicationContext.getSharedPreferences("bv300_semantic_memory", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean("enabled", true)
    fun setEnabled(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply() }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        database.memoryChunkDao().listBySource(SOURCE).size
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            val chunks = database.memoryChunkDao().listBySource(SOURCE)
            chunks.forEach { database.memoryVaultDao().deleteEmbedding(ref(it.id)) }
            database.memoryChunkDao().deleteBySource(SOURCE)
        }
    }

    suspend fun put(chatId: String, messageId: String, text: String, vector: FloatArray) = withContext(Dispatchers.IO) {
        require(vector.size == EmbeddingGemmaEngine.DIMENSIONS)
        writeMutex.withLock {
            val dao = database.memoryChunkDao()
            if (dao.listBySourceAndSourceId(SOURCE, "$chatId:$messageId").isNotEmpty()) return@withLock
            val now = System.currentTimeMillis()
            val id = dao.insert(MemoryChunk(
                source = SOURCE,
                sourceId = "$chatId:$messageId",
                tsMs = now,
                text = text,
                createdAt = now,
                updatedAt = now,
            ))
            val json = JSONArray().also { array -> vector.forEach { array.put(it) } }
            database.memoryVaultDao().upsertEmbedding(LocalEmbeddingStoreEntity(
                memoryRef = ref(id),
                embeddingJson = json.toString(),
                tagsJson = "[]",
                modelVersion = VERSION,
                updatedAt = now,
            ))
        }
    }

    suspend fun all(): List<SemanticMemoryRecord> = withContext(Dispatchers.IO) {
        val vectors = database.memoryVaultDao().listEmbeddings()
            .filter { it.modelVersion == VERSION && it.memoryRef.startsWith("$SOURCE:") }
            .associateBy { it.memoryRef }
        database.memoryChunkDao().listBySource(SOURCE).mapNotNull { chunk ->
            val stored = vectors[ref(chunk.id)] ?: return@mapNotNull null
            val array = runCatching { JSONArray(stored.embeddingJson) }.getOrNull() ?: return@mapNotNull null
            if (array.length() != EmbeddingGemmaEngine.DIMENSIONS) return@mapNotNull null
            SemanticMemoryRecord(
                messageId = chunk.sourceId.orEmpty().substringAfterLast(':'),
                text = chunk.text,
                vector = FloatArray(array.length()) { array.optDouble(it).toFloat() },
            )
        }
    }

    private fun ref(id: Long): String = "$SOURCE:$id"

    companion object {
        private const val SOURCE = "bv300_conversation"
        private const val VERSION = "embeddinggemma_300m_seq512_v1"
        private val writeMutex = Mutex()
    }
}
