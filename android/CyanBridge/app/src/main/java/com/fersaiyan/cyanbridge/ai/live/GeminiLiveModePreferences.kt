package com.fersaiyan.cyanbridge.ai.live

import android.content.Context

/** Economy is the default Live route for Pro users. Changes apply to the next session. */
object GeminiLiveModePreferences {
    fun isEconomy(context: Context): Boolean =
        context.getSharedPreferences("gemini_live", Context.MODE_PRIVATE)
            .getBoolean("pro_economy", true)

    fun setEconomy(context: Context, enabled: Boolean) {
        context.getSharedPreferences("gemini_live", Context.MODE_PRIVATE)
            .edit().putBoolean("pro_economy", enabled).apply()
    }
}
