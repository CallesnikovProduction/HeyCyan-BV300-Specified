package com.fersaiyan.cyanbridge.localai.stt

interface RussianSpeechRecognizer {
    suspend fun recognizePcm16(
        pcm16: ByteArray,
        sampleRateHz: Int,
        onPartial: (String) -> Unit = {},
    ): String
}
