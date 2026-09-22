package com.fersaiyan.cyanbridge.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupertonicSpeechPlanTest {
    @Test fun emptyTextHasNoChunks() {
        assertTrue(SupertonicSpeechPlan.plan("  \n  ").isEmpty())
    }

    @Test fun russianAndEnglishAreRoutedSeparately() {
        assertEquals("ru", SupertonicSpeechPlan.languageFor("Три плюс пять равно восемь"))
        assertEquals("en", SupertonicSpeechPlan.languageFor("Three plus five equals eight"))
    }

    @Test fun longResponseIsChunkedWithoutLosingWords() {
        val words = (1..500).map { "слово$it" }
        val chunks = SupertonicSpeechPlan.plan(words.joinToString(" "))
        assertTrue(chunks.size > 10)
        assertTrue(chunks.all { it.text.length <= SupertonicSpeechPlan.MAX_CHARS_PER_CHUNK })
        assertEquals(words, chunks.flatMap { it.text.split(' ') })
    }

    @Test fun longUnbrokenTextDoesNotSplitSurrogatePair() {
        val chunks = SupertonicSpeechPlan.plan("а".repeat(219) + "😀" + "б".repeat(250))
        assertTrue(chunks.all { it.text.length <= SupertonicSpeechPlan.MAX_CHARS_PER_CHUNK })
        assertEquals("а".repeat(219) + "😀" + "б".repeat(250), chunks.joinToString("" ) { it.text })
    }
}
