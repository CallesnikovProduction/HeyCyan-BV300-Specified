package com.fersaiyan.cyanbridge.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bv300VoicePromptTest {
    @Test fun systemPromptGroundsOnlyCurrentAttachedImageAndRespectsUserTask() {
        assertTrue(Bv300VoicePrompt.SYSTEM.contains("Blackview BV300"))
        assertTrue(Bv300VoicePrompt.SYSTEM.contains("свежий кадр BV300 для этого запроса"))
        assertTrue(Bv300VoicePrompt.SYSTEM.contains("выполни по нему именно задачу"))
        assertTrue(Bv300VoicePrompt.SYSTEM.contains("реши или объясни"))
        assertFalse(Bv300VoicePrompt.SYSTEM.contains("всегда описывай"))
        assertFalse(Bv300VoicePrompt.SYSTEM.contains("описывай то, что видит пользователь"))
        assertFalse(Bv300VoicePrompt.SYSTEM.contains("одним предложением"))
    }

    @Test fun imageDoesNotRewriteOrAugmentRecognizedUserText() {
        listOf(
            "Сфотографируй и реши этот пример",
            "Прочитай этот текст",
            "Переведи эту надпись",
            "Что тут нарисовано?",
        ).forEach { transcript ->
            assertEquals(transcript, Bv300VoicePrompt.userContent(transcript))
        }
    }
}
