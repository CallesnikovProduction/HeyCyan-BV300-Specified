package com.fersaiyan.cyanbridge.localmodels.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtVisualFallbackTest {
    @Test fun failedVisualGenerationNeverRetriesAsTextOnly() {
        assertFalse(canRetryWithoutImage(hasUserContents = true, imagePaths = listOf("/private/bv300_request.jpg")))
    }

    @Test fun existingNonVisualMediaFallbackIsUnchanged() {
        assertTrue(canRetryWithoutImage(hasUserContents = true, imagePaths = emptyList()))
        assertFalse(canRetryWithoutImage(hasUserContents = false, imagePaths = emptyList()))
    }
}
