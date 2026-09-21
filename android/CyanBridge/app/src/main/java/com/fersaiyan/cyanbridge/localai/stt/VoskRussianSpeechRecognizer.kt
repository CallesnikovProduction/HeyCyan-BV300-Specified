package com.fersaiyan.cyanbridge.localai.stt

import android.content.Context
import com.fersaiyan.cyanbridge.localai.model.LocalModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/** Consumes the decoded 16 kHz mono PCM already supplied by the MoYoung BV300 SDK. */
class VoskRussianSpeechRecognizer(private val context: Context) : RussianSpeechRecognizer {
    override suspend fun recognizePcm16(
        pcm16: ByteArray,
        sampleRateHz: Int,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        require(sampleRateHz == 16_000) { "BV300 PCM must be 16 kHz" }
        require(pcm16.size >= 3_200) { "No usable speech audio received from BV300" }
        val model = cachedModel(context)
        Recognizer(model, 16_000f).use { recognizer ->
            val completed = mutableListOf<String>()
            var offset = 0
            while (offset < pcm16.size) {
                currentCoroutineContext().ensureActive()
                val length = minOf(3_200, pcm16.size - offset)
                val chunk = pcm16.copyOfRange(offset, offset + length)
                if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                    textFromJson(recognizer.result)?.let(completed::add)
                } else {
                    textFromJson(recognizer.partialResult, "partial")?.let(onPartial)
                }
                offset += length
            }
            textFromJson(recognizer.finalResult)?.let(completed::add)
            completed.joinToString(" ").trim()
        }
    }

    companion object {
        private val modelMutex = Mutex()
        private var model: Model? = null
        private var loadedPath: String? = null

        private suspend fun cachedModel(context: Context): Model = modelMutex.withLock {
            val directory = LocalModelManager.voskDirectory(context)
            check(LocalModelManager.isValidVoskDirectory(directory)) {
                "Vosk model missing. Import vosk-model-small-ru-0.22.zip in Local Models."
            }
            if (model == null || loadedPath != directory.absolutePath) {
                model?.close()
                model = withContext(Dispatchers.IO) { Model(directory.absolutePath) }
                loadedPath = directory.absolutePath
            }
            requireNotNull(model)
        }

        private fun textFromJson(json: String, field: String = "text"): String? =
            runCatching { JSONObject(json).optString(field).trim().takeIf(String::isNotBlank) }.getOrNull()
    }
}
