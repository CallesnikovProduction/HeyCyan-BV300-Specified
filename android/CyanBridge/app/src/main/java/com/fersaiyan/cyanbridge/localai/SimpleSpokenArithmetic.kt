package com.fersaiyan.cyanbridge.localai

/** Narrow deterministic guard for unambiguous one-step arithmetic heard through BV300. */
internal object SimpleSpokenArithmetic {
    private val words = listOf(
        "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
        "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
        "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать", "двадцать",
    )
    private val pattern = Regex(
        "^(?:сколько будет\\s+)?([а-яё0-9]+)\\s+(плюс|минус)\\s+([а-яё0-9]+)[?.!]*$",
        RegexOption.IGNORE_CASE,
    )

    fun answerIfUnambiguous(transcript: String): String? {
        val match = pattern.matchEntire(transcript.trim()) ?: return null
        val left = number(match.groupValues[1]) ?: return null
        val right = number(match.groupValues[3]) ?: return null
        val result = when (match.groupValues[2].lowercase()) {
            "плюс" -> left + right
            "минус" -> left - right
            else -> return null
        }
        val resultText = words.getOrNull(result) ?: result.toString()
        return "${match.groupValues[1].replaceFirstChar { it.uppercase() }} ${match.groupValues[2].lowercase()} ${match.groupValues[3]} равно $resultText."
    }

    private fun number(token: String): Int? = token.toIntOrNull() ?: words.indexOf(token.lowercase()).takeIf { it >= 0 }
}
