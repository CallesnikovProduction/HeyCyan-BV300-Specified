package com.fersaiyan.cyanbridge.shared.chat

enum class ChatRole {
    USER,
    ASSISTANT,
}

data class ChatMessage(
    val id: String,
    val chatId: String,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
    /** App-private BV300 photo filename, never a public URI or a cache path. */
    val imageAttachmentName: String? = null,
)

data class ChatThread(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)
