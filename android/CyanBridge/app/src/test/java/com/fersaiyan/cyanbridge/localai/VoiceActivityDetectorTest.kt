package com.fersaiyan.cyanbridge.localai

import com.fersaiyan.cyanbridge.localai.audio.VoiceActivityDetector
import com.fersaiyan.cyanbridge.localai.audio.EndpointReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityDetectorTest {
    private val silence = ByteArray(3_200) // 100 ms, 16 kHz mono PCM16
    private val speech = ByteArray(3_200) { index -> if (index % 2 == 0) 0x10 else 0x27 }

    @Test fun ignoresInitialSilenceAndStopsAfterTrailingSilence() {
        val detector = VoiceActivityDetector()
        repeat(15) { assertFalse(detector.accept(silence)) }
        repeat(6) { assertFalse(detector.accept(speech)) }
        assertTrue(detector.speechDetected)
        repeat(11) { assertFalse(detector.accept(silence)) }
        assertTrue(detector.accept(silence))
        assertEquals(EndpointReason.TRAILING_SILENCE, detector.endpointReason)
    }

    @Test fun maximumDurationStopsSilentCapture() {
        val detector = VoiceActivityDetector(maxDurationMs = 1_000)
        repeat(9) { assertFalse(detector.accept(silence)) }
        assertTrue(detector.accept(silence))
        assertEquals(EndpointReason.MAX_DURATION, detector.endpointReason)
    }

    @Test fun shortThinkingPausesStayInOneUtterance() {
        for (pauseFrames in listOf(5, 8)) {
            val detector = VoiceActivityDetector()
            repeat(5) { assertFalse(detector.accept(speech)) }
            repeat(pauseFrames) { assertFalse(detector.accept(silence)) }
            repeat(4) { assertFalse(detector.accept(speech)) }
            repeat(11) { assertFalse(detector.accept(silence)) }
            assertTrue(detector.accept(silence))
        }
    }

    @Test fun isolatedNoiseDoesNotStartSpeech() {
        val detector = VoiceActivityDetector()
        repeat(10) {
            assertFalse(detector.accept(if (it == 4) speech else silence))
        }
        assertFalse(detector.speechDetected)
    }
}
