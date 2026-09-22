package com.fersaiyan.cyanbridge.localai

import android.content.Context
import android.util.Log
import com.fersaiyan.cyanbridge.localai.model.LocalModelManager
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** App-private Supertonic 3 engine; serialized RU/EN synthesis keeps one selected speaker. */
internal object SupertonicTts {
    private var engine: OfflineTts? = null

    @Synchronized
    fun release() {
        engine?.release()
        engine = null
    }

    @Synchronized
    fun synthesize(context: Context, text: String, destination: File) {
        val directory = LocalModelManager.supertonicDirectory(context)
        check(LocalModelManager.isValidSupertonicDirectory(directory)) {
            "Import Supertonic 3 TTS from Documents/llms in Local Models first"
        }
        val model = engine ?: OfflineTts(
            config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = File(directory, "duration_predictor.int8.onnx").absolutePath,
                        textEncoder = File(directory, "text_encoder.int8.onnx").absolutePath,
                        vectorEstimator = File(directory, "vector_estimator.int8.onnx").absolutePath,
                        vocoder = File(directory, "vocoder.int8.onnx").absolutePath,
                        ttsJson = File(directory, "tts.json").absolutePath,
                        unicodeIndexer = File(directory, "unicode_indexer.bin").absolutePath,
                        voiceStyle = File(directory, "voice.bin").absolutePath,
                    ),
                    numThreads = 2,
                ),
            ),
        ).also { engine = it }
        try {
            check(model.numSpeakers() >= 5) { "Supertonic model has fewer than five voice styles" }
            RandomAccessFile(destination, "rw").use { output ->
                output.setLength(0)
                output.write(ByteArray(44))
                var sampleRate = 0
                var dataBytes = 0L
                val chunks = SupertonicSpeechPlan.plan(text)
                check(chunks.isNotEmpty()) { "Nothing to speak" }
                Log.i("SupertonicTts", "Synthesis chars=${text.length} chunks=${chunks.size} speaker=${SupertonicVoicePrefs.speakerId(context)} languages=${chunks.map { it.language }.distinct()}")
                chunks.forEachIndexed { index, chunk ->
                    val generated = model.generateWithConfig(
                        chunk.text,
                        GenerationConfig(
                            sid = SupertonicVoicePrefs.speakerId(context),
                            speed = SupertonicVoicePrefs.speed(context),
                            numSteps = 8,
                            extra = mapOf("lang" to chunk.language),
                        ),
                    )
                    check(generated.samples.isNotEmpty()) { "Supertonic returned no audio" }
                    if (sampleRate == 0) sampleRate = generated.sampleRate
                    check(generated.sampleRate == sampleRate) { "Supertonic changed sample rate" }
                    for (sample in generated.samples) {
                        val pcm = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
                        output.write(pcm and 0xff)
                        output.write((pcm ushr 8) and 0xff)
                    }
                    dataBytes += generated.samples.size * 2L
                    if (index != chunks.lastIndex) {
                        repeat(sampleRate / 6) { output.write(0); output.write(0) }
                        dataBytes += sampleRate / 6 * 2L
                    }
                    check(dataBytes <= Int.MAX_VALUE - 44L) { "Spoken response is too long" }
                }
                check(sampleRate > 0)
                output.seek(0)
                output.write(wavHeader(sampleRate, dataBytes.toInt()))
                Log.i("SupertonicTts", "WAV ready bytes=$dataBytes sampleRate=$sampleRate")
            }
        } catch (error: Throwable) {
            destination.delete()
            release()
            throw error
        }
    }

    private fun wavHeader(sampleRate: Int, dataBytes: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRate)
            putInt(sampleRate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
}
