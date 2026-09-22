package com.fersaiyan.cyanbridge.localai

/** Turns arbitrary Gemma deltas into ordered, speakable RU/EN sentences. */
internal class SentenceChunker {
    private val pending = StringBuilder()
    private var periodAfterDigit = false

    fun append(fragment: String): List<String> {
        if (fragment.isEmpty()) return emptyList()
        val ready = mutableListOf<String>()
        var index = 0
        while (index < fragment.length) {
            val char = fragment[index]
            if (periodAfterDigit) {
                periodAfterDigit = false
                if (!char.isDigit()) emitIfSpeakable(ready)
            }
            if (pending.isEmpty() && (char.isWhitespace() || char.isTerminal())) {
                index++
                continue
            }
            pending.append(char)
            if (char.isTerminal()) {
                if (char == '.' && pending.length > 1 && pending[pending.lastIndex - 1].isDigit()) {
                    periodAfterDigit = true
                } else if (char == '.' && endsWithKnownAbbreviation()) {
                    // A period in "г.", "т.д.", "Dr.", etc. is not a sentence boundary.
                } else {
                    while (index + 1 < fragment.length && fragment[index + 1].isTerminal()) {
                        pending.append(fragment[++index])
                    }
                    emitIfSpeakable(ready)
                }
            } else if (pending.length >= MAX_UNPUNCTUATED_CHARS && char.isWhitespace()) {
                emitIfSpeakable(ready)
            }
            index++
        }
        return ready
    }

    fun finish(): List<String> {
        periodAfterDigit = false
        val ready = mutableListOf<String>()
        emitIfSpeakable(ready, allowShort = true)
        pending.clear()
        return ready
    }

    private fun emitIfSpeakable(output: MutableList<String>, allowShort: Boolean = false) {
        val chunk = pending.toString().trim()
        if (!chunk.any(Char::isLetterOrDigit)) {
            pending.clear()
            return
        }
        // A stray "1." or a single letter is held for the next sentence, not sent to TTS.
        if (!allowShort && chunk.count(Char::isLetterOrDigit) < 2) return
        output += chunk
        pending.clear()
    }

    private fun endsWithKnownAbbreviation(): Boolean {
        val tail = pending.toString().lowercase()
        val word = tail.substringAfterLast(' ').substringAfterLast('\n')
        return word in ABBREVIATIONS
    }

    private fun Char.isTerminal(): Boolean = this == '.' || this == '!' || this == '?' ||
        this == '。' || this == '！' || this == '？'

    private companion object {
        private const val MAX_UNPUNCTUATED_CHARS = 700
        private val ABBREVIATIONS = setOf("т.", "д.", "г.", "ул.", "им.", "др.", "etc.", "e.", "g.", "mr.", "mrs.", "dr.", "prof.")
    }
}
