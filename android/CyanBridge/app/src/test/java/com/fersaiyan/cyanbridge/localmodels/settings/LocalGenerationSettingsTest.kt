package com.fersaiyan.cyanbridge.localmodels.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGenerationSettingsTest {
    @Test
    fun everyProfileStartsWithEditableDirectPrompt() {
        LocalModelPerformanceProfile.entries.forEach { profile ->
            val settings = LocalGenerationSettings.defaultsFor(entry = null, profile = profile)
            assertEquals(LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT, settings.systemPromptOverride)
        }
    }

    @Test
    fun defaultPromptPreservesTaskAndCurrentImageGroundingWithoutBrevityRules() {
        val prompt = LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT
        assertTrue(prompt.contains("user's actual request directly"))
        assertTrue(prompt.contains("image attached to the current request"))
        assertTrue(prompt.contains("task the user asked for"))
        assertTrue(!prompt.contains("at most 8 words"))
        assertTrue(!prompt.contains("one clear sentence"))
        assertTrue(!prompt.contains("shortest complete answer"))
    }

    @Test
    fun oldDefaultsMigrateWithoutReplacingCustomPrompts() {
        assertEquals(
            LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT,
            LocalGenerationSettings.migrateDefaultSystemPrompt(
                LocalGenerationSettings.LEGACY_EIGHT_WORD_SYSTEM_PROMPT,
            ),
        )
        assertEquals(
            LocalGenerationSettings.DEFAULT_SYSTEM_PROMPT,
            LocalGenerationSettings.migrateDefaultSystemPrompt(
                LocalGenerationSettings.PREVIOUS_DEFAULT_SYSTEM_PROMPT,
            ),
        )
        assertEquals("Custom prompt", LocalGenerationSettings.migrateDefaultSystemPrompt("Custom prompt"))
    }
}
