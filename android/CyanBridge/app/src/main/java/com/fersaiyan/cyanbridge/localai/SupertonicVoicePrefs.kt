package com.fersaiyan.cyanbridge.localai

import android.content.Context

/** Supertonic archive contains five female styles at speaker IDs 0..4. */
internal object SupertonicVoicePrefs {
    val femaleVoices: List<String> = (1..5).map { "F$it" }
    private const val FILE = "bv300_supertonic_voice"
    private const val KEY_SPEAKER = "speaker_id"
    private const val KEY_SPEED = "speed"

    fun speakerId(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_SPEAKER, 0).coerceIn(0, 4)

    fun setSpeakerId(context: Context, id: Int) {
        require(id in 0..4)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY_SPEAKER, id).apply()
    }

    fun speed(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat(KEY_SPEED, 1.0f).coerceIn(0.7f, 2.0f)

    fun setSpeed(context: Context, speed: Float) {
        require(speed in 0.7f..2.0f)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putFloat(KEY_SPEED, speed).apply()
    }
}
