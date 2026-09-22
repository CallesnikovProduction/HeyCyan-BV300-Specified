package com.fersaiyan.cyanbridge.localai

import org.junit.Assert.assertTrue
import org.junit.Test

class Bv300VoicePromptTest {
    @Test fun voicePromptExplainsTransportAndImageBoundary() {
        assertTrue(Bv300VoicePrompt.SYSTEM.contains("BV300"))
        assertTrue(Bv300VoicePrompt.SYSTEM.contains("динамиках"))
        assertTrue(Bv300VoicePrompt.SYSTEM.contains("если к текущему запросу не приложен снимок"))
        assertTrue(Bv300VoicePrompt.SYSTEM.contains("живой, тёплый разговор"))
        assertTrue(Bv300VoicePrompt.SYSTEM.contains("не сокращай полезное объяснение искусственно"))
    }

    @Test fun attachedPhotoIsExplicitOnlyForVisualTurn() {
        val question = "Что передо мной?"
        assertTrue(Bv300VoicePrompt.userContent(question, true).contains("уже приложен новый снимок"))
        assertTrue(Bv300VoicePrompt.userContent(question, true).contains(question))
        assertTrue(Bv300VoicePrompt.userContent(question, false) == question)
    }
}
