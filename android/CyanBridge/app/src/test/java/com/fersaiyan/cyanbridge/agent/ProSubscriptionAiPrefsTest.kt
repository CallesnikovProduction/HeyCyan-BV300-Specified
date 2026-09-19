package com.fersaiyan.cyanbridge.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProSubscriptionAiPrefsTest {
    @Test
    fun selectedVisionModelIsRespected() {
        assertFalse(
            ProSubscriptionAiPrefs.shouldUseGeminiLiveForQuestions(
                questionsModel = "deepseek/deepseek-v4-flash-vision-exp",
            ),
        )
    }

    @Test
    fun liveModelRoutesToGeminiLive() {
        assertTrue(
            ProSubscriptionAiPrefs.shouldUseGeminiLiveForQuestions(
                questionsModel = "google/gemini-3.1-flash-live-preview",
            ),
        )
        assertFalse(
            ProSubscriptionAiPrefs.shouldUseGeminiLiveForQuestions(
                questionsModel = "deepseek/deepseek-v4-flash-vision-exp",
            ),
        )
    }
}
