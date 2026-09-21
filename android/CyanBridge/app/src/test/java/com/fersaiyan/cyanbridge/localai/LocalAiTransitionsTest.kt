package com.fersaiyan.cyanbridge.localai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiTransitionsTest {
    @Test fun normalVoiceTurnFollowsExpectedPhases() {
        val phases = listOf(
            LocalAiPhase.IDLE,
            LocalAiPhase.LISTENING,
            LocalAiPhase.SPEECH_DETECTED,
            LocalAiPhase.FINALIZING_STT,
            LocalAiPhase.THINKING,
            LocalAiPhase.SPEAKING,
            LocalAiPhase.IDLE,
        )
        phases.zipWithNext().forEach { (from, to) -> assertTrue(LocalAiTransitions.allows(from, to)) }
    }

    @Test fun delayedButtonAndCancellationCannotRegressState() {
        assertFalse(LocalAiTransitions.allows(LocalAiPhase.THINKING, LocalAiPhase.LISTENING))
        assertFalse(LocalAiTransitions.allows(LocalAiPhase.FINALIZING_STT, LocalAiPhase.SPEECH_DETECTED))
        assertTrue(LocalAiTransitions.allows(LocalAiPhase.THINKING, LocalAiPhase.ERROR))
        assertTrue(LocalAiTransitions.allows(LocalAiPhase.ERROR, LocalAiPhase.IDLE))
    }
}
