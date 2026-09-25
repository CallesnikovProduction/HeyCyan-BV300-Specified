package com.fersaiyan.cyanbridge.localai.memory

/** Only failures that can plausibly be repaired by shrinking and rebuilding the conversation. */
internal object ContextCapacityFailure {
    fun isRecoverable(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .take(6)
        .mapNotNull { it.message?.lowercase() }
        .any { message ->
            listOf("context length", "context window", "context overflow", "kv cache", "prefill",
                "too many tokens", "token limit", "input too long", "maxnumtokens")
                .any(message::contains)
        }
}
