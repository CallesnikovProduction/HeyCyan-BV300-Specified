package com.fersaiyan.cyanbridge.assistant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AssistantRuntime {
    val session = AssistantSession()
    private val mutableSnapshot = MutableStateFlow(session.snapshot)
    val snapshot = mutableSnapshot.asStateFlow()

    @Synchronized
    fun update(action: AssistantSession.() -> Unit) {
        session.action()
        mutableSnapshot.value = session.snapshot
    }
}

object AssistantPreferences {
    private const val FILE = "bv300_assistant"
    private const val MANUAL_MODE = "manual_chatgpt_mode"
    private const val CONVERSATION = "desired_conversation"

    fun manualMode(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(MANUAL_MODE, false)

    fun setManualMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(MANUAL_MODE, enabled).apply()
    }

    fun conversationName(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(CONVERSATION, "BV300 Glasses") ?: "BV300 Glasses"

    fun setConversationName(context: Context, name: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(CONVERSATION, name.trim().take(80).ifBlank { "BV300 Glasses" }).apply()
    }
}
