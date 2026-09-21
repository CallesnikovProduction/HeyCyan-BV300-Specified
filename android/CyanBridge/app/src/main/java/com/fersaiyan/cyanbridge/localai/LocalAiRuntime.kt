package com.fersaiyan.cyanbridge.localai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LocalAiPhase { IDLE, LISTENING, SPEECH_DETECTED, FINALIZING_STT, THINKING, SPEAKING, ERROR }

internal object LocalAiTransitions {
    fun allows(from: LocalAiPhase, to: LocalAiPhase): Boolean = when {
        from == to || to == LocalAiPhase.ERROR || to == LocalAiPhase.IDLE -> true
        from == LocalAiPhase.IDLE || from == LocalAiPhase.ERROR ->
            to == LocalAiPhase.LISTENING || to == LocalAiPhase.FINALIZING_STT
        from == LocalAiPhase.LISTENING ->
            to == LocalAiPhase.SPEECH_DETECTED || to == LocalAiPhase.FINALIZING_STT
        from == LocalAiPhase.SPEECH_DETECTED -> to == LocalAiPhase.FINALIZING_STT
        from == LocalAiPhase.FINALIZING_STT -> to == LocalAiPhase.THINKING
        from == LocalAiPhase.THINKING -> to == LocalAiPhase.SPEAKING
        else -> false
    }
}

data class LocalAiSnapshot(
    val phase: LocalAiPhase = LocalAiPhase.IDLE,
    val partialTranscript: String = "",
    val transcript: String = "",
    val error: String? = null,
)

object LocalAiRuntime {
    private val mutableState = MutableStateFlow(LocalAiSnapshot())
    val state = mutableState.asStateFlow()

    @Synchronized
    fun update(transform: (LocalAiSnapshot) -> LocalAiSnapshot) {
        val previous = mutableState.value
        val next = transform(previous)
        if (LocalAiTransitions.allows(previous.phase, next.phase)) mutableState.value = next
    }

    fun reset() { mutableState.value = LocalAiSnapshot() }
}
