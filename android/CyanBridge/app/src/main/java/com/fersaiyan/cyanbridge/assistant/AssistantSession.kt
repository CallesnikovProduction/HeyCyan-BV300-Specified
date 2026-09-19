package com.fersaiyan.cyanbridge.assistant

enum class AssistantPhase { IDLE, LISTENING, TRANSCRIBING, READY_TO_SEND, WAITING_FOR_ASSISTANT, SPEAKING, ERROR }
enum class UtteranceSource { BV300, PHONE }

data class UserUtterance(val text: String, val timestampMs: Long, val source: UtteranceSource)

data class AssistantSnapshot(
    val phase: AssistantPhase = AssistantPhase.IDLE,
    val utterance: UserUtterance? = null,
    val error: String? = null,
    val lastTrigger: String = "None",
    val lastStt: String = "Not tested",
    val lastTts: String = "Not tested",
)

/** In-memory orchestration only. Device, microphone and transport implementations stay outside. */
class AssistantSession {
    var snapshot = AssistantSnapshot()
        private set

    fun startListening(source: UtteranceSource) {
        snapshot = AssistantSnapshot(
            phase = AssistantPhase.LISTENING,
            lastTrigger = source.name,
            lastStt = snapshot.lastStt,
            lastTts = snapshot.lastTts,
        )
    }

    fun transcribing() {
        require(snapshot.phase == AssistantPhase.LISTENING)
        snapshot = snapshot.copy(phase = AssistantPhase.TRANSCRIBING)
    }

    fun prepare(text: String, timestampMs: Long, source: UtteranceSource) {
        require(snapshot.phase == AssistantPhase.TRANSCRIBING)
        val normalized = text.trim()
        require(normalized.isNotEmpty())
        snapshot = snapshot.copy(
            phase = AssistantPhase.READY_TO_SEND,
            utterance = UserUtterance(normalized, timestampMs, source),
            lastStt = "Success",
            error = null,
        )
    }

    fun waiting() {
        require(snapshot.phase == AssistantPhase.READY_TO_SEND)
        snapshot = snapshot.copy(phase = AssistantPhase.WAITING_FOR_ASSISTANT)
    }

    fun speaking() {
        require(snapshot.phase == AssistantPhase.WAITING_FOR_ASSISTANT)
        snapshot = snapshot.copy(phase = AssistantPhase.SPEAKING)
    }

    fun speechFinished() {
        require(snapshot.phase == AssistantPhase.SPEAKING)
        snapshot = snapshot.copy(phase = AssistantPhase.IDLE, utterance = null, lastTts = "Success")
    }

    fun recordTts(status: String) {
        snapshot = snapshot.copy(lastTts = status)
    }

    fun fail(reason: String, sttFailed: Boolean = false) {
        snapshot = snapshot.copy(
            phase = AssistantPhase.ERROR,
            error = reason,
            lastStt = if (sttFailed) "Failure" else snapshot.lastStt,
        )
    }

    fun reset() {
        snapshot = snapshot.copy(phase = AssistantPhase.IDLE, utterance = null, error = null)
    }
}
