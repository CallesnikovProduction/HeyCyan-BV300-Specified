package com.fersaiyan.cyanbridge.assistant

enum class TransportCapability { LOGIN_HANDOFF, OPEN_CHAT, AUTOMATIC_SEND, AUTOMATIC_RECEIVE }
enum class TransportStatus { LOGIN_REQUIRED, OPENED_UNVERIFIED, READY, UNAVAILABLE }

sealed interface TransportResult {
    data object OpenedForManualUse : TransportResult
    data object NotSupportedByCurrentAccountTransport : TransportResult {
        const val code = "NOT_SUPPORTED_BY_CURRENT_ACCOUNT_TRANSPORT"
    }
}

interface AssistantTransport {
    val capabilities: Set<TransportCapability>
    val status: TransportStatus
    fun connect(): TransportResult
    fun disconnect()
    fun startConversation(name: String): TransportResult
    fun sendUserMessage(utterance: UserUtterance): TransportResult
}

/** Consumer ChatGPT has only a browser handoff here; no account callback or automatic round-trip. */
class ChatGPTAccountTransport : AssistantTransport {
    override val capabilities = setOf(TransportCapability.LOGIN_HANDOFF, TransportCapability.OPEN_CHAT)
    override var status: TransportStatus = TransportStatus.LOGIN_REQUIRED
        private set

    override fun connect(): TransportResult {
        status = TransportStatus.OPENED_UNVERIFIED
        return TransportResult.OpenedForManualUse
    }

    override fun disconnect() {
        status = TransportStatus.LOGIN_REQUIRED
    }

    override fun startConversation(name: String): TransportResult =
        TransportResult.NotSupportedByCurrentAccountTransport

    override fun sendUserMessage(utterance: UserUtterance): TransportResult =
        TransportResult.NotSupportedByCurrentAccountTransport
}
