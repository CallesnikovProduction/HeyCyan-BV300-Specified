package com.fersaiyan.cyanbridge.localai

/** The strategy boundary for deciding whether a spoken turn needs a NEW BV300 image. */
fun interface VisualIntentDecision {
    fun requiresVision(transcript: String): Boolean
}

/**
 * Cheap, compositional routing from the current utterance to a fresh-camera decision.
 * It deliberately does not infer the task Gemma should perform.
 */
object VisualIntentRouter : VisualIntentDecision {
    private val captureAction = Regex(
        "(?:^| )(?:сфотк[\\p{L}]*|сфотограф[\\p{L}]*|фотографируй[\\p{L}]*|сним(?:и|ай)[\\p{L}]*|" +
            "(?:сделай|сделайте) (?:фото|снимок|фотографию)|" +
            "take (?:a )?(?:photo|picture)|snap (?:a )?(?:photo|picture)|" +
            "capture(?: this| that| an? (?:image|photo))?|photograph)(?= |$)",
    )
    private val cameraOperation = Regex(
        "(?:^| )(?:(?:включи|используй|запусти|активируй) (?:эту )?камеру|" +
            "(?:use|turn on|open) (?:the )?camera)(?= |$)",
    )
    private val perceptionAction = Regex(
        "(?:^| )(?:посмотр[\\p{L}]*|глянь[\\p{L}]*|гляди[\\p{L}]*|взглян[\\p{L}]*|оглянись|рассмотр[\\p{L}]*|" +
            "прочит[\\p{L}]*|прочт[\\p{L}]*|распозн[\\p{L}]*|определ[\\p{L}]*|разгляди[\\p{L}]*|" +
            "look(?: at| around)?|see|show|read|identify|recognize|inspect|count)(?= |$)",
    )
    private val taskAction = Regex(
        "(?:^| )(?:реш[\\p{L}]*|посчитай[\\p{L}]*|сосчитай[\\p{L}]*|вычисл[\\p{L}]*|прочит[\\p{L}]*|прочт[\\p{L}]*|" +
            "перевед[\\p{L}]*|объясн[\\p{L}]*|определ[\\p{L}]*|найд[\\p{L}]*|распозн[\\p{L}]*|скажи|" +
            "сколько|how many|solve|calculate|translate|explain|find|tell me|which|what color)(?= |$)",
    )
    private val deicticContext = Regex(
        "(?:^| )(?:передо мной|перед нами|вокруг меня|вокруг нас|" +
            "здесь|тут|на картинке|на фото|на фотографии|на табличке|на экране|" +
            "in front of me|in front of us|around me|around us|here|there|on (?:this )?(?:photo|picture|image|screen|sign|page))(?= |$)",
    )
    private val visualObject = Regex(
        "(?:^| )(?:пример[\\p{L}]*|задач[\\p{L}]*|уравнен[\\p{L}]*|надпис[\\p{L}]*|написан[\\p{L}]*|" +
            "текст[\\p{L}]*|таблич[\\p{L}]*|знак[\\p{L}]*|цвет[\\p{L}]*|цветок[\\p{L}]*|растен[\\p{L}]*|" +
            "машин[\\p{L}]*|автомобил[\\p{L}]*|предмет[\\p{L}]*|объект[\\p{L}]*|штук[\\p{L}]*|" +
            "рисун[\\p{L}]*|картин[\\p{L}]*|изображен[\\p{L}]*|экран[\\p{L}]*|документ[\\p{L}]*|" +
            "страниц[\\p{L}]*|книг[\\p{L}]*|человек[\\p{L}]*|кто|" +
            "equation[\\p{L}]*|problem[\\p{L}]*|text|writing|written|sign|label|board|flower[\\p{L}]*|plant[\\p{L}]*|" +
            "car[\\p{L}]*|vehicle[\\p{L}]*|object[\\p{L}]*|thing|drawing|picture|image|screen|document|page|book|person|people)(?= |$)",
    )
    private val visualDeictic = Regex(
        "(?:^| )(?:это|этот|эта|эту|эти|этим|этом|этих|него|неё|его|её|" +
            "this|that|these|those)(?= |$)",
    )
    private val sceneQuestion = Regex(
        "(?:^| )(?:что (?:ты видишь|вы видите|тут|здесь|передо мной|перед нами|" +
            "вокруг меня|вокруг нас|изображено|нарисовано|происходит|находится|написано)|" +
            "ч[ео] (?:тут|здесь|это)|ч[ео] скажешь|что это(?: за)?|что за (?:предмет|объект|вещь|цветок|растение|штука)|кто передо мной|" +
            "какой (?:это |передо мной )?(?:цветок|растение|автомобиль|предмет)|" +
            "кто это|определи по виду|" +
            "what(?: is| s) (?:in front of me|around me|around us|happening here|here|there|shown|pictured|written|this|that)|" +
            "what (?:do|can) you see|what does (?:this|that|it)(?: [a-z]+)? say|" +
            "what am i holding|who is in front of me|which (?:flower|car|vehicle) is this)(?= |$)",
    )
    private val attributeQuestion = Regex(
        "(?:^| )(?:какого цвета|какой цвет|какой это|какой передо мной|сколько (?:здесь|тут)|" +
            "what colo[u]?r|how many)(?= |$)",
    )
    private val colloquialLookQuestion = Regex(
        "(?:^| )(?:посмотри ка че скажешь|глянь че|глянь ч[её]|глянь что это)(?= |$)",
    )

    override fun requiresVision(transcript: String): Boolean {
        val text = normalize(transcript)
        if (text.isEmpty()) return false

        if (captureAction.containsMatchIn(text) || cameraOperation.containsMatchIn(text)) return true
        if (sceneQuestion.containsMatchIn(text) || colloquialLookQuestion.containsMatchIn(text)) return true

        val hasPerception = perceptionAction.containsMatchIn(text)
        val hasTask = taskAction.containsMatchIn(text)
        val hasDeictic = deicticContext.containsMatchIn(text)
        val hasObject = visualObject.containsMatchIn(text)
        val hasVisualPronoun = visualDeictic.containsMatchIn(text)

        // "Look at this" / "read this" are direct requests to inspect current visual context.
        if (hasPerception && (hasDeictic || hasObject || hasVisualPronoun)) return true

        // Task + concrete visual object/context: "solve this example", "count cars here".
        if (hasTask && (hasObject || hasDeictic)) return true

        // Attribute questions need a visible referent; ordinary text questions do not.
        return attributeQuestion.containsMatchIn(text) && (hasObject || hasDeictic || hasVisualPronoun)
    }

    fun needsFreshPhoto(transcript: String): Boolean = requiresVision(transcript)

    private fun normalize(transcript: String): String = transcript
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
