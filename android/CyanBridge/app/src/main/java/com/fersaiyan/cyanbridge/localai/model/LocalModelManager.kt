package com.fersaiyan.cyanbridge.localai.model

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import com.fersaiyan.cyanbridge.localai.SupertonicTts
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** Fixed model locations for the personal BV300 assistant. The Gemma importer already lives in Local Models. */
object LocalModelManager {
    private const val VOSK_DIRECTORY = "vosk-model"
    private const val MAX_VOSK_UNPACKED_BYTES = 512L * 1024L * 1024L
    private const val SUPERTONIC_DIRECTORY = "supertonic3-tts"
    private const val SUPERTONIC_ARCHIVE_ROOT = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    private const val MAX_SUPERTONIC_UNPACKED_BYTES = 180L * 1024L * 1024L
    private val supertonicFiles = setOf(
        "duration_predictor.int8.onnx", "text_encoder.int8.onnx",
        "vector_estimator.int8.onnx", "vocoder.int8.onnx",
        "tts.json", "unicode_indexer.bin", "voice.bin",
    )

    fun supertonicDirectory(context: Context): File = File(context.filesDir, SUPERTONIC_DIRECTORY)

    fun hasSupertonic(context: Context): Boolean = isValidSupertonicDirectory(supertonicDirectory(context))

    fun isValidSupertonicDirectory(directory: File): Boolean =
        directory.isDirectory && supertonicFiles.all { File(directory, it).isFile && File(directory, it).length() > 0L }

    /** Imports only the seven fixed Supertonic model files from the official tar.bz2. */
    fun importSupertonicArchive(context: Context, uri: Uri): File {
        val target = supertonicDirectory(context)
        val staging = File(context.filesDir, "$SUPERTONIC_DIRECTORY-import")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create Supertonic import directory" }
        try {
            var totalBytes = 0L
            context.contentResolver.openInputStream(uri).use { raw ->
                checkNotNull(raw) { "Cannot open Supertonic archive" }
                TarArchiveInputStream(BZip2CompressorInputStream(raw.buffered())).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        val parts = entry.name.trimEnd('/').split('/')
                        require(parts.firstOrNull() == SUPERTONIC_ARCHIVE_ROOT) { "Unexpected Supertonic archive" }
                        if (entry.isDirectory) continue
                        if (entry.isFile && parts.size == 2 && parts[1] in setOf("LICENSE", "README.md")) continue
                        require(entry.isFile && parts.size == 2 && parts[1] in supertonicFiles) {
                            "Unexpected Supertonic archive entry: ${entry.name}"
                        }
                        require(entry.size in 1..MAX_SUPERTONIC_UNPACKED_BYTES) { "Invalid Supertonic file size" }
                        val output = File(staging, parts[1])
                        require(!output.exists()) { "Duplicate Supertonic file" }
                        FileOutputStream(output).use { file ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = tar.read(buffer)
                                if (read < 0) break
                                totalBytes += read
                                require(totalBytes <= MAX_SUPERTONIC_UNPACKED_BYTES) { "Supertonic archive is too large" }
                                file.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            require(isValidSupertonicDirectory(staging)) { "Supertonic archive is incomplete" }
            SupertonicTts.release()
            val backup = File(context.filesDir, "$SUPERTONIC_DIRECTORY-backup")
            backup.deleteRecursively()
            if (target.exists()) check(target.renameTo(backup)) { "Cannot replace Supertonic model" }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Cannot finish Supertonic import")
            }
            backup.deleteRecursively()
            return target
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun voskDirectory(context: Context): File = File(context.filesDir, VOSK_DIRECTORY)

    fun hasVosk(context: Context): Boolean = isValidVoskDirectory(voskDirectory(context))

    fun isValidVoskDirectory(directory: File): Boolean =
        directory.isDirectory &&
            File(directory, "am/final.mdl").isFile &&
            File(directory, "conf/model.conf").isFile &&
            File(directory, "graph/HCLr.fst").isFile

    /** Imports the user-selected Vosk ZIP via SAF; no broad storage permission is required. */
    fun importVoskZip(context: Context, uri: Uri): File {
        val target = voskDirectory(context)
        val staging = File(context.filesDir, "$VOSK_DIRECTORY-import")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create Vosk import directory" }
        try {
            var totalBytes = 0L
            context.contentResolver.openInputStream(uri).use { raw ->
                checkNotNull(raw) { "Cannot open Vosk ZIP" }
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val parts = entry.name.replace('\\', '/').split('/').filter(String::isNotBlank)
                        // The official archive has a single top-level model directory.
                        val relative = parts.dropWhile { it == "vosk-model-small-ru-0.22" }
                        if (relative.isEmpty()) continue
                        require(relative.none { it == "." || it == ".." }) { "Unsafe Vosk ZIP entry" }
                        val file = File(staging, relative.joinToString(File.separator))
                        require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            "Unsafe Vosk ZIP path"
                        }
                        if (entry.isDirectory) {
                            check(file.mkdirs() || file.isDirectory)
                        } else {
                            check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
                            FileOutputStream(file).use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val read = zip.read(buffer)
                                    if (read < 0) break
                                    totalBytes += read
                                    require(totalBytes <= MAX_VOSK_UNPACKED_BYTES) { "Vosk archive is too large" }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(isValidVoskDirectory(staging)) { "Archive is not vosk-model-small-ru-0.22" }
            // Preserve a working model until the replacement has been validated.
            val backup = File(context.filesDir, "$VOSK_DIRECTORY-backup")
            backup.deleteRecursively()
            if (target.exists()) check(target.renameTo(backup)) { "Cannot replace existing Vosk model" }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Cannot finish Vosk import")
            }
            backup.deleteRecursively()
            return target
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }
}
