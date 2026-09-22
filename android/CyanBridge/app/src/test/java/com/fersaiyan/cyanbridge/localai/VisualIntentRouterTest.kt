package com.fersaiyan.cyanbridge.localai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualIntentRouterTest {
    @Test fun russianVisualQuestionsRequestFreshPhoto() {
        assertTrue(VisualIntentRouter.needsFreshPhoto("Что передо мной?"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("Что ты видишь?"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("Посмотри на это, пожалуйста"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("Сфоткай и реши уравнение"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("Прочитай, что здесь написано"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("Что происходит передо мной?"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("А какого он цвета?"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("Что написано на вывеске?"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("Можешь прочитать эту надпись?"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("Что у меня в руках?"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("Какого цвета это?"))
    }

    @Test fun englishVisualQuestionsRequestFreshPhoto() {
        assertTrue(VisualIntentRouter.needsFreshPhoto("What do you see?"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("What's in front of me?"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("Take a picture and solve the equation"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("Read what is written here"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("What does this sign say?"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("What is written on the board?"))
        assertTrue(VisualIntentRouter.needsFreshPhoto("What am I holding?"))
    }

    @Test fun ordinaryQuestionsStayTextOnly() {
        assertFalse(VisualIntentRouter.needsFreshPhoto("Сколько будет два плюс два?"))
        assertFalse(VisualIntentRouter.needsFreshPhoto("Сколько будет 2+2?"))
        assertFalse(VisualIntentRouter.needsFreshPhoto("Why is the sky blue?"))
    }
}
