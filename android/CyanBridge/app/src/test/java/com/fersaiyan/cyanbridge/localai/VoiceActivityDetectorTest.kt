package com.fersaiyan.cyanbridge.localai

import com.fersaiyan.cyanbridge.localai.audio.VoiceActivityDetector
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
        repeat(9) { assertFalse(detector.accept(silence)) }
        assertTrue(detector.accept(silence))
    }

    @Test fun maximumDurationStopsSilentCapture() {
        val detector = VoiceActivityDetector(maxDurationMs = 1_000)
        repeat(9) { assertFalse(detector.accept(silence)) }
        assertTrue(detector.accept(silence))
    }
}
