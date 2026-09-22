package com.fersaiyan.cyanbridge.localai

/** The strategy boundary for deciding whether a spoken turn needs a NEW BV300 image. */
fun interface VisualIntentDecision {
    fun requiresVision(transcript: String): Boolean
}

/** Lightweight semantic cues; no second model is loaded just to route a request. */
object VisualIntentRouter : VisualIntentDecision {
    private val cameraAction = Regex(
        "(?:^| )(?:сфоткай|сфотографируй|фотографируй|снимай|сними|сфотай|" +
            "(?:сделай|сделайте) (?:фото|снимок|фотографию)|" +
            "take (?:a )?(?:photo|picture)|snap (?:a )?(?:photo|picture)|" +
            "capture (?:this|that|an? image|an? photo)|photograph)(?= |$)",
    )
    private val visualAction = Regex(
        "(?:^| )(?:посмотри|взгляни|оглянись|покажи|опиши|рассмотри|" +
            "прочитай|прочитать|прочти|глянь|разгляди|считай|распознай|увидишь|видишь|видно|" +
            "look(?:ing)?(?: at| around)?|see|show|describe|read|identify|recognize)(?= |$)",
    )
    private val visualTarget = Regex(
        "(?:^| )(?:передо мной|перед мной|перед нами|вокруг меня|вокруг нас|здесь|тут|" +
            "написано|надпись|текст|предмет|объект|вещь|уравнение|задач[ауие]|доск[аеу]|" +
            "фото|снимок|фотографию|камер[ауые]|это|этот|эту|этим|него|его|" +
            "in front of me|around me|around us|here|this|that|these|those|" +
            "written|text|object|thing|equation|problem|photo|picture|camera|board|sign|page|label)(?= |$)",
    )
    private val sceneQuestion = Regex(
        "(?:^| )(?:что (?:ты видишь|вы видите|передо мной|перед нами|вокруг меня|вокруг нас|здесь|тут|происходит)|" +
            "что это за (?:предмет|объект|вещь)|куда мне идти|куда идти|что у меня перед глазами|" +
            "what(?: is| s) (?:in front of me|around me|around us|happening here|this|that)|" +
            "what (?:do|can) you see|what does (?:this|that|it)(?: [a-z]+)? say|" +
            "where (?:should|do) i go|which way (?:should i go|is it))(?= |$)",
    )
    private val visualAttribute = Regex(
        "(?:^| )(?:какого (?:он|она|это|этот|предмет) цвета|какой (?:у него|у неё|здесь) цвет|" +
            "какого цвета (?:это|этот|эта|оно|он|она|предмет)|какой это цвет|" +
            "what colo[u]?r (?:is|are) (?:it|this|that|he|she|they)|" +
            "what does (?:it|this|that) look like)(?= |$)",
    )
    private val readingOrHoldingQuestion = Regex(
        "(?:^| )(?:что (?:там |здесь |тут )?написано|что за (?:надпись|текст)|" +
            "что у меня в руках|что я держу|" +
            "what (?:is|s) written|what does (?:the|this|that) (?:sign|label|page|board) say|" +
            "what am i holding)(?= |$)",
    )

    override fun requiresVision(transcript: String): Boolean {
        val text = transcript.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        if (text.isEmpty()) return false
        return cameraAction.containsMatchIn(text) || sceneQuestion.containsMatchIn(text) ||
            readingOrHoldingQuestion.containsMatchIn(text) ||
            visualAttribute.containsMatchIn(text) ||
            (visualAction.containsMatchIn(text) && visualTarget.containsMatchIn(text))
    }

    fun needsFreshPhoto(transcript: String): Boolean = requiresVision(transcript)
}
