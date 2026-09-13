package com.fersaiyan.cyanbridge.ai.live

import android.content.Context

/** Private remains the default for existing paid users. Changes apply to the next session. */
object GeminiLiveModePreferences {
    fun isEconomy(context: Context): Boolean =
        context.getSharedPreferences("gemini_live", Context.MODE_PRIVATE)
            .getBoolean("pro_economy", false)

    fun setEconomy(context: Context, enabled: Boolean) {
        context.getSharedPreferences("gemini_live", Context.MODE_PRIVATE)
            .edit().putBoolean("pro_economy", enabled).apply()
    }
}
