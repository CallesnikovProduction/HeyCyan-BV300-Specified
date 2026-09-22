package com.fersaiyan.cyanbridge.localai

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceChunkerTest {
    @Test fun emitsImmediatelyAtTerminalPunctuation() {
        val chunker = SentenceChunker()
        assertEquals(listOf("Да, конечно."), chunker.append("Да, конечно."))
    }

    @Test fun reconstructsFragmentedSentenceAndPreservesOrder() {
        val chunker = SentenceChunker()
        assertEquals(emptyList<String>(), chunker.append("Это"))
        assertEquals(emptyList<String>(), chunker.append(" интересно"))
        assertEquals(listOf("Это интересно."), chunker.append("."))
        assertEquals(listOf("А теперь..."), chunker.append(" А теперь..."))
    }

    @Test fun flushesMeaningfulUnterminatedTail() {
        val chunker = SentenceChunker()
        chunker.append("Здесь есть важная деталь")
        assertEquals(listOf("Здесь есть важная деталь"), chunker.finish())
    }

    @Test fun avoidsDecimalAndCommonAbbreviationSplits() {
        val chunker = SentenceChunker()
        assertEquals(emptyList<String>(), chunker.append("Температура 3."))
        assertEquals(listOf("Температура 3.14 градуса."), chunker.append("14 градуса."))
        assertEquals(emptyList<String>(), chunker.append("Это г."))
        assertEquals(listOf("Это г. Москва."), chunker.append(" Москва."))
    }

    @Test fun doesNotSendPunctuationOrOneCharacterGarbage() {
        val chunker = SentenceChunker()
        assertEquals(emptyList<String>(), chunker.append("..."))
        assertEquals(emptyList<String>(), chunker.append("1."))
        assertEquals(listOf("1. Это пример."), chunker.append(" Это пример."))
    }
}
