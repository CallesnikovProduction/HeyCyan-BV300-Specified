package com.fersaiyan.cyanbridge.localai.embedding

enum class EmbeddingRole { QUERY, DOCUMENT }

/** Keeps tokenizer/model implementation out of memory storage and orchestration. */
interface TextEmbedder {
    suspend fun embed(text: String, role: EmbeddingRole): FloatArray
}
