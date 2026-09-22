package com.fersaiyan.cyanbridge.localai

/** Text-only planning, kept separate from the native synthesizer so length and language are testable. */
internal object SupertonicSpeechPlan {
    const val MAX_CHARS_PER_CHUNK = 220

    data class Chunk(val text: String, val language: String)

    fun plan(text: String): List<Chunk> {
        var remaining = text.trim().replace(Regex("\\s+"), " ")
        if (remaining.isEmpty()) return emptyList()
        val chunks = mutableListOf<Chunk>()
        while (remaining.length > MAX_CHARS_PER_CHUNK) {
            var boundary = remaining.lastIndexOf(' ', MAX_CHARS_PER_CHUNK).takeIf { it >= 100 }
                ?: MAX_CHARS_PER_CHUNK
            if (boundary < remaining.length && Character.isLowSurrogate(remaining[boundary])) boundary--
            val part = remaining.substring(0, boundary).trim()
            if (part.isNotEmpty()) chunks += Chunk(part, languageFor(part))
            remaining = remaining.substring(boundary).trimStart()
        }
        if (remaining.isNotEmpty()) chunks += Chunk(remaining, languageFor(remaining))
        return chunks
    }

    fun languageFor(text: String): String {
        val cyrillic = text.count { it in '\u0400'..'\u052f' }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        return if (latin > cyrillic) "en" else "ru"
    }
}
