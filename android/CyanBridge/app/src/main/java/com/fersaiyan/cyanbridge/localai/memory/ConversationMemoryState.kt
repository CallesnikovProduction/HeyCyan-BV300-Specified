package com.fersaiyan.cyanbridge.localai.memory

import android.content.Context
import org.json.JSONArray

internal data class ConversationMemoryState(
    val summary: String = "",
    val compactedThroughId: String? = null,
    val epoch: Int = 0,
    val interruptedAssistantIds: Set<String> = emptySet(),
    val pendingAssistantId: String? = null,
)

/** Chat messages live in Room; only the bounded compaction cursor and summary live here. */
internal class ConversationMemoryStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bv300_conversation_memory", Context.MODE_PRIVATE)

    fun load(chatId: String): ConversationMemoryState {
        val prefix = "$chatId."
        val interrupted = runCatching {
            JSONArray(prefs.getString(prefix + "interrupted", "[]"))
        }.getOrDefault(JSONArray())
        return ConversationMemoryState(
            summary = prefs.getString(prefix + "summary", "").orEmpty(),
            compactedThroughId = prefs.getString(prefix + "cursor", null),
            epoch = prefs.getInt(prefix + "epoch", 0),
            interruptedAssistantIds = (0 until interrupted.length()).mapNotNull(interrupted::optString).toSet(),
            pendingAssistantId = prefs.getString(prefix + "pending", null),
        )
    }

    fun save(chatId: String, state: ConversationMemoryState) {
        val prefix = "$chatId."
        check(prefs.edit()
            .putString(prefix + "summary", state.summary)
            .putString(prefix + "cursor", state.compactedThroughId)
            .putInt(prefix + "epoch", state.epoch)
            .putString(prefix + "interrupted", JSONArray(state.interruptedAssistantIds.toList()).toString())
            .putString(prefix + "pending", state.pendingAssistantId)
            .commit()) { "Could not persist BV300 conversation summary and cursor" }
    }
}
