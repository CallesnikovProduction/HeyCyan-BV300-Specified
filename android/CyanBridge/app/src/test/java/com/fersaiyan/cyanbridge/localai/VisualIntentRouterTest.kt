package com.fersaiyan.cyanbridge.localai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualIntentRouterTest {
    @Test fun russianVisualRequestsRequireFreshPhoto() = assertVision(
        "Что передо мной?",
        "Что тут?",
        "Что здесь находится?",
        "Что тут нарисовано?",
        "Что изображено?",
        "Посмотри на это.",
        "Посмотри-ка, чё скажешь?",
        "Глянь, что это.",
        "Сфоткай.",
        "Сфоткай и скажи, что это.",
        "Сфотографируй это.",
        "Сделай фотографию.",
        "Сделай фото и объясни.",
        "Сними это и расскажи.",
        "Прочитай, что здесь написано.",
        "Прочитай этот текст.",
        "Переведи эту надпись.",
        "Что написано на табличке?",
        "Реши этот пример.",
        "Реши пример передо мной.",
        "Сфоткай и реши пример.",
        "Сфотографируй и реши уравнение.",
        "Посмотри на пример и реши его.",
        "Посчитай предметы передо мной.",
        "Сколько здесь машин?",
        "Что это за предмет?",
        "Что это за штука?",
        "Какой это цветок?",
        "Кто передо мной?",
        "Кто это?",
        "Что за растение?",
        "Определи по виду.",
        "Включи камеру.",
        "Сфотографируй и реши это уравнение",
        "Посмотри-ка, чё тут",
        "Че это?",
        "Чо это?",
        "Глянь-ка, что за знак здесь",
        "Прочти надпись на экране",
        "Сфотографируй!",
    )

    @Test fun englishVisualRequestsRequireFreshPhoto() = assertVision(
        "What do you see?",
        "What's in front of me?",
        "Take a picture and solve the equation",
        "Read what is written here",
        "What does this sign say?",
        "What is written on the board?",
        "What am I holding?",
        "Look at this.",
        "Translate this label.",
        "How many cars are here?",
        "Which flower is this?",
        "Capture this image",
        "What is here?",
        "Use the camera.",
    )

    @Test fun textOnlyRequestsAndContextualFollowUpsDoNotRequestFreshPhoto() = assertNoVision(
        "Сколько будет два плюс два?",
        "Реши два плюс два.",
        "Расскажи про квантовую физику.",
        "Что такое TCP?",
        "Переведи hello на русский.",
        "Напиши короткое стихотворение.",
        "Почему небо голубое?",
        "Сколько километров в миле?",
        "А почему?",
        "Объясни подробнее.",
        "Повтори.",
        "Продолжай.",
        "А оно ядовитое?",
        "Переведи это на русский.",
        "How much is two plus two?",
        "Explain relativity.",
        "Translate hello into Russian.",
        "Why is the sky blue?",
        "Tell me more.",
    )

    private fun assertVision(vararg phrases: String) {
        phrases.forEach { phrase ->
            assertTrue("Expected fresh vision for: $phrase", VisualIntentRouter.requiresVision(phrase))
        }
    }

    private fun assertNoVision(vararg phrases: String) {
        phrases.forEach { phrase ->
            assertFalse("Unexpected fresh vision for: $phrase", VisualIntentRouter.requiresVision(phrase))
        }
    }
}
