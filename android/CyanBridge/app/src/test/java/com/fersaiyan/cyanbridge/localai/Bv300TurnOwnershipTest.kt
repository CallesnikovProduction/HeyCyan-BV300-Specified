package com.fersaiyan.cyanbridge.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Bv300TurnOwnershipTest {
    @Test fun newButtonPressInvalidatesGeneratingTurnAndStartsListening() {
        val owner = Bv300TurnOwnership()
        assertEquals(null, owner.begin("A"))
        owner.updateIfCurrent("A") { it.copy(phase = LocalAiPhase.THINKING) }
        assertEquals("A", owner.begin("B"))
        assertEquals(LocalAiPhase.LISTENING, LocalAiRuntime.state.value.phase)
        assertFalse(owner.isCurrent("A"))
        assertTrue(owner.isCurrent("B"))
        assertFalse(owner.finish("A"))
    }

    @Test fun lateModelOrCameraResultCannotStealNewTurnState() {
        val owner = Bv300TurnOwnership()
        owner.begin("photo-A")
        owner.begin("B")
        owner.updateIfCurrent("photo-A") { it.copy(phase = LocalAiPhase.SPEAKING) }
        assertEquals(LocalAiPhase.LISTENING, LocalAiRuntime.state.value.phase)
        assertFalse(owner.finish("photo-A"))
        owner.updateIfCurrent("B") { it.copy(phase = LocalAiPhase.SPEECH_DETECTED) }
        assertEquals(LocalAiPhase.SPEECH_DETECTED, LocalAiRuntime.state.value.phase)
    }

    @Test fun speakingTurnIsPreemptedAndOldPlaybackCannotResumeOwnership() {
        val owner = Bv300TurnOwnership()
        owner.begin("A")
        owner.updateIfCurrent("A") { it.copy(phase = LocalAiPhase.SPEAKING) }
        assertEquals("A", owner.begin("B"))
        owner.updateIfCurrent("A") { it.copy(phase = LocalAiPhase.SPEAKING) }
        assertEquals(LocalAiPhase.LISTENING, LocalAiRuntime.state.value.phase)
        assertFalse(owner.finish("A"))
    }

    @Test fun invalidatedTurnCannotPublishReply() {
        val owner = Bv300TurnOwnership()
        val published = mutableListOf<String>()
        owner.begin("A")
        owner.begin("B")
        assertFalse(owner.publishIfCurrent("A") { published += "stale" })
        assertTrue(owner.publishIfCurrent("B") { published += "current" })
        assertEquals(listOf("current"), published)
    }
}
