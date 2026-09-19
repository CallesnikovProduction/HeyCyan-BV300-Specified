package com.fersaiyan.cyanbridge.assistant

import android.content.Context
import com.fersaiyan.cyanbridge.ai.transcription.LocalMultimodalTranscriptionProvider
import java.io.File

/** Audio acquisition remains owned by the existing BV300 manager and MainActivity pipeline. */
interface AssistantVoiceInput {
    suspend fun transcribe(wavFile: File, languageTag: String?): String
}

class LocalAssistantVoiceInput(context: Context) : AssistantVoiceInput {
    private val provider = LocalMultimodalTranscriptionProvider(context)

    override suspend fun transcribe(wavFile: File, languageTag: String?): String =
        provider.transcribe(wavFile, "audio/wav", languageTag)
}
