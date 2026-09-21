package com.fersaiyan.cyanbridge.localai.model

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** Fixed model locations for the personal BV300 assistant. The Gemma importer already lives in Local Models. */
object LocalModelManager {
    private const val VOSK_DIRECTORY = "vosk-model"
    private const val MAX_VOSK_UNPACKED_BYTES = 512L * 1024L * 1024L

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
