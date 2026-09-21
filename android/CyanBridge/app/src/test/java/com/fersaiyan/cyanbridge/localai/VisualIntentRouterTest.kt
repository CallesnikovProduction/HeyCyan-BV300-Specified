package com.fersaiyan.cyanbridge.localai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualIntentRouterTest {
    @Test fun russianVisualQuestionsRequestFreshPhoto() {
        assertTrue(VisualIntentRouter.needsFreshPhoto("Что передо мной?"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("Посмотри на это, пожалуйста"))
    }

    @Test fun ordinaryQuestionsStayTextOnly() {
        assertFalse(VisualIntentRouter.needsFreshPhoto("Сколько будет два плюс два?"))
    }
}
