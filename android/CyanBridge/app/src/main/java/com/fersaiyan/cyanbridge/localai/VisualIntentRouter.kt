package com.fersaiyan.cyanbridge.localai

/** Narrow Russian command detection until BV300 image-question routing is model-driven. */
object VisualIntentRouter {
    private val cues = listOf(
        "что передо мной",
        "что ты видишь",
        "посмотри",
        "опиши что передо мной",
        "посмотри на это",
    )

    fun needsFreshPhoto(transcript: String): Boolean {
        val normalized = transcript.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return cues.any(normalized::contains)
    }
}
