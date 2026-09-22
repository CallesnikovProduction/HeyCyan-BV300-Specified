package com.fersaiyan.cyanbridge.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimpleSpokenArithmeticTest {
    @Test fun correctsObservedGemmaMistakes() {
        assertEquals("Семь плюс шесть равно тринадцать.",
            SimpleSpokenArithmetic.answerIfUnambiguous("сколько будет семь плюс шесть"))
        assertEquals("Девять минус два равно семь.",
            SimpleSpokenArithmetic.answerIfUnambiguous("сколько будет девять минус два"))
    }

    @Test fun doesNotInterceptOpenEndedQuestions() {
        assertNull(SimpleSpokenArithmetic.answerIfUnambiguous("почему семь плюс шесть равно тринадцать"))
        assertNull(SimpleSpokenArithmetic.answerIfUnambiguous("сколько будет семь плюс неизвестное число"))
    }
}
