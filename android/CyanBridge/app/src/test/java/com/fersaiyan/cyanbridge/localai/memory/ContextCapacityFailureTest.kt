package com.fersaiyan.cyanbridge.localai.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCapacityFailureTest {
    @Test fun onlyCapacityFailuresAreRetried() {
        assertTrue(ContextCapacityFailure.isRecoverable(IllegalStateException("KV cache capacity exceeded")))
        assertTrue(ContextCapacityFailure.isRecoverable(IllegalStateException("failed", IllegalArgumentException("prefill token limit"))))
        assertFalse(ContextCapacityFailure.isRecoverable(IllegalStateException("image file missing")))
        assertFalse(ContextCapacityFailure.isRecoverable(OutOfMemoryError("heap allocation failed")))
    }
}
