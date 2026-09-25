package com.fersaiyan.cyanbridge.localai.embedding

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GeckoEmbeddingModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Optional
import kotlin.math.sqrt

/** Official local RAG runner accepts a TFLite model plus its separate SentencePiece file. */
class EmbeddingGemmaEngine(private val context: Context) : TextEmbedder {
    override suspend fun embed(text: String, role: EmbeddingRole): FloatArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(EmbeddingGemmaFiles.isReady(context)) { "Import EmbeddingGemma and sentencepiece.model in Local Models" }
            val active = runner ?: run {
                val started = SystemClock.elapsedRealtime()
                GeckoEmbeddingModel(
                    EmbeddingGemmaFiles.model(context).absolutePath,
                    Optional.of(EmbeddingGemmaFiles.tokenizer(context).absolutePath),
                    false, // CPU first; measure coexistence with Gemma on the phone before trying GPU.
                ).also {
                    runner = it
                    Log.i(TAG, "EmbeddingGemma initMs=${SystemClock.elapsedRealtime() - started}")
                }
            }
            val task = if (role == EmbeddingRole.QUERY) EmbedData.TaskType.RETRIEVAL_QUERY
                else EmbedData.TaskType.RETRIEVAL_DOCUMENT
            val request = EmbeddingRequest.create(listOf(EmbedData.create(text, task)))
            val started = SystemClock.elapsedRealtime()
            val output = active.getEmbeddings(request).get().map(Number::toFloat).toFloatArray()
            Log.i(TAG, "EmbeddingGemma role=$role embedMs=${SystemClock.elapsedRealtime() - started}")
            require(output.size == DIMENSIONS && output.all(Float::isFinite)) {
                "EmbeddingGemma returned an invalid embedding (${output.size} dimensions)"
            }
            var normSquared = 0.0
            output.forEach { normSquared += it.toDouble() * it }
            val norm = sqrt(normSquared).toFloat()
            require(norm > 0f) { "EmbeddingGemma returned a zero vector" }
            FloatArray(output.size) { output[it] / norm }
        }
    }

    companion object {
        const val DIMENSIONS = 768
        private const val TAG = "EmbeddingGemma"
        // The assistant can run from Activity or foreground Service; both share one native runner.
        private val mutex = Mutex()
        @Volatile
        private var runner: GeckoEmbeddingModel? = null
        fun isLoaded(): Boolean = runner != null
    }
}
