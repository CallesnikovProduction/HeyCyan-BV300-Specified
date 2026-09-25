package com.fersaiyan.cyanbridge.localai.embedding

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** User-imported EmbeddingGemma assets. Neither file is packaged in the APK. */
object EmbeddingGemmaFiles {
    private const val MODEL_NAME = "embeddinggemma-300M_seq512_mixed-precision.tflite"
    private const val TOKENIZER_NAME = "sentencepiece.model"

    fun directory(context: Context): File = File(context.filesDir, "local_models/embeddinggemma")
    fun model(context: Context): File = File(directory(context), MODEL_NAME)
    fun tokenizer(context: Context): File = File(directory(context), TOKENIZER_NAME)
    fun isReady(context: Context): Boolean = validModel(model(context)) && validTokenizer(tokenizer(context))

    fun importModel(context: Context, uri: Uri): File = importFile(context, uri, model(context), 300L * 1024 * 1024, ::validModel)
    fun importTokenizer(context: Context, uri: Uri): File = importFile(context, uri, tokenizer(context), 20L * 1024 * 1024, ::validTokenizer)

    private fun validModel(file: File): Boolean = file.isFile && file.length() in 100_000_000L..300_000_000L &&
        runCatching { RandomAccessFile(file, "r").use { raf ->
            raf.seek(4)
            val marker = ByteArray(4)
            raf.readFully(marker)
            String(marker, Charsets.US_ASCII) == "TFL3"
        } }.getOrDefault(false)

    private fun validTokenizer(file: File): Boolean = file.isFile && file.length() in 100_000L..20_000_000L

    private fun importFile(
        context: Context,
        uri: Uri,
        target: File,
        maxBytes: Long,
        valid: (File) -> Boolean,
    ): File {
        check(!EmbeddingGemmaEngine.isLoaded()) { "Restart the app before replacing a loaded EmbeddingGemma model" }
        check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
        val staging = File(target.parentFile, "${target.name}.new")
        val backup = File(target.parentFile, "${target.name}.previous")
        check(!staging.exists()) { "An EmbeddingGemma import is already in progress" }
        try {
            context.contentResolver.openInputStream(uri).use { raw ->
                checkNotNull(raw) { "Cannot open EmbeddingGemma file" }
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = raw.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= maxBytes) { "EmbeddingGemma file exceeds the supported size" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(valid(staging)) { "This is not the expected EmbeddingGemma file" }
            check(!backup.exists()) { "Previous model backup needs recovery before another import" }
            if (target.exists()) check(target.renameTo(backup)) { "Cannot preserve previous EmbeddingGemma file" }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Cannot finish EmbeddingGemma import")
            }
            backup.delete()
            return target
        } finally {
            staging.delete()
        }
    }
}
