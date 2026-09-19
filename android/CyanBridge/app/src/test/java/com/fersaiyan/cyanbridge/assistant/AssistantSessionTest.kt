package com.fersaiyan.cyanbridge.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSessionTest {
    @Test fun transcriptBecomesPreparedOnlyAfterCapture() {
        val session = AssistantSession()
        session.startListening(UtteranceSource.BV300)
        assertEquals(AssistantPhase.LISTENING, session.snapshot.phase)
        session.transcribing()
        assertEquals(AssistantPhase.TRANSCRIBING, session.snapshot.phase)
        session.prepare("  Hello  ", 42L, UtteranceSource.BV300)
        assertEquals(AssistantPhase.READY_TO_SEND, session.snapshot.phase)
        assertEquals(UserUtterance("Hello", 42L, UtteranceSource.BV300), session.snapshot.utterance)
    }

    @Test fun errorCanRecoverWithoutRetainingTranscript() {
        val session = AssistantSession()
        session.startListening(UtteranceSource.BV300)
        session.transcribing()
        session.fail("STT failed", sttFailed = true)
        assertEquals(AssistantPhase.ERROR, session.snapshot.phase)
        assertEquals("Failure", session.snapshot.lastStt)
        session.reset()
        assertEquals(AssistantPhase.IDLE, session.snapshot.phase)
        assertNull(session.snapshot.utterance)
        assertNull(session.snapshot.error)
    }

    @Test fun transportNeverFakesVerifiedAccountOrSend() {
        val transport = ChatGPTAccountTransport()
        assertEquals(TransportStatus.LOGIN_REQUIRED, transport.status)
        assertTrue(TransportCapability.LOGIN_HANDOFF in transport.capabilities)
        assertTrue(TransportCapability.OPEN_CHAT in transport.capabilities)
        assertFalse(TransportCapability.AUTOMATIC_SEND in transport.capabilities)
        assertFalse(TransportCapability.AUTOMATIC_RECEIVE in transport.capabilities)
        assertEquals(TransportResult.OpenedForManualUse, transport.connect())
        assertEquals(TransportStatus.OPENED_UNVERIFIED, transport.status)
        assertEquals(TransportResult.NotSupportedByCurrentAccountTransport,
            transport.startConversation("BV300 Glasses"))
        assertEquals(TransportResult.NotSupportedByCurrentAccountTransport,
            transport.sendUserMessage(UserUtterance("Hi", 1L, UtteranceSource.BV300)))
        transport.disconnect()
        assertEquals(TransportStatus.LOGIN_REQUIRED, transport.status)
    }

    @Test fun validWaitAndSpeechTransitionReturnsIdle() {
        val session = AssistantSession()
        session.startListening(UtteranceSource.BV300)
        session.transcribing()
        session.prepare("Hi", 1L, UtteranceSource.BV300)
        session.waiting()
        session.speaking()
        session.speechFinished()
        assertEquals(AssistantPhase.IDLE, session.snapshot.phase)
        assertEquals("Success", session.snapshot.lastTts)
    }
}
