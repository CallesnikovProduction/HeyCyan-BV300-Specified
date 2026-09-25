package com.fersaiyan.cyanbridge.localai.memory

internal object SemanticMemoryRetriever {
    const val TOP_K = 3
    // Conservative floor for this model's query/document vectors: a device probe scored
    // an unrelated Russian weather sentence 0.647 and the matching memory 0.727.
    // Favor missing a weak memory over injecting an unrelated past answer into Gemma.
    const val MIN_SIMILARITY = 0.70f

    fun rank(
        query: FloatArray,
        records: List<SemanticMemoryRecord>,
        recentMessageIds: Set<String>,
        topK: Int = TOP_K,
        threshold: Float = MIN_SIMILARITY,
    ): List<SemanticMemoryRecord> {
        if (query.isEmpty() || topK <= 0) return emptyList()
        return records.asSequence()
            .filter { record -> record.messageId !in recentMessageIds && record.vector.size == query.size }
            .map { record -> record to query.indices.sumOf { i ->
                (query[i] * record.vector[i]).toDouble()
            }.toFloat() }
            .filter { (_, score) -> score.isFinite() && score >= threshold }
            .sortedByDescending { (_, score) -> score }
            .take(topK)
            .map { (record, _) -> record }
            .toList()
    }
}
