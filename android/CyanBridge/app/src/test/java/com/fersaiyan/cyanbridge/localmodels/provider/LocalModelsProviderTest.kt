package com.fersaiyan.cyanbridge.localmodels.provider

import com.fersaiyan.cyanbridge.localmodels.templates.PromptMessage
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelsProviderTest {

    @Test
    fun multimodalPromptKeepsConfiguredAndRuntimeSystemInstructions() {
        val prompt = buildMultimodalPrompt(
            configuredSystemPrompt = "Always answer in Russian.",
            messages = listOf(
                PromptMessage("System", "Keep answers brief."),
                PromptMessage("User", "Describe this image."),
            ),
        )

        assertTrue(prompt.contains("Always answer in Russian."))
        assertTrue(prompt.contains("Keep answers brief."))
        assertTrue(prompt.contains("User request: Describe this image."))
    }

    @Test
    fun multimodalPromptKeepsRecentConversationAndOriginalVisualQuestion() {
        val prompt = buildMultimodalPrompt(
            configuredSystemPrompt = "",
            messages = listOf(
                PromptMessage("system", "BV300 fresh image only"),
                PromptMessage("user", "На прошлом снимке была книга"),
                PromptMessage("assistant", "Да, книга на столе"),
                PromptMessage("user", "Сфоткай и прочитай, что здесь написано"),
            ),
        )
        assertTrue(prompt.contains("На прошлом снимке была книга"))
        assertTrue(prompt.contains("Да, книга на столе"))
        assertTrue(prompt.contains("User request: Сфоткай и прочитай, что здесь написано"))
    }
}
