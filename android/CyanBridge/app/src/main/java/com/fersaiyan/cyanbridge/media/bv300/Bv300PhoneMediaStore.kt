package com.fersaiyan.cyanbridge.media.bv300

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream

internal data class SavedBv300Media(val uri: String, val bytes: Long)

/** Pending MediaStore row on Android 10+; private staging file on older Android. */
internal class Bv300PhoneMediaStore(private val context: Context) {
    private val resolver = context.contentResolver

    fun save(
        item: Bv300MediaItem,
        input: InputStream,
        expectedBytes: Long,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): SavedBv300Media {
        val staged = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File.createTempFile("bv300_", ".pending", context.cacheDir)
        } else null
        var uri: Uri? = null
        try {
            if (staged == null) uri = insert(item, pending = true)
            val output = if (staged != null) staged.outputStream() else
                resolver.openOutputStream(requireNotNull(uri), "w")
                    ?: throw IOException("Could not open pending MediaStore item")
            var bytes = 0L
            output.buffered(128 * 1024).use { destination ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    if (isCancelled()) throw IOException("BV300 transfer cancelled")
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    destination.write(buffer, 0, read)
                    bytes += read
                    onProgress(bytes, expectedBytes)
                }
                destination.flush()
            }
            if (!verifyTransferBytes(bytes, expectedBytes)) {
                throw IOException("Incomplete BV300 download: $bytes / $expectedBytes bytes")
            }
            if (isCancelled()) throw IOException("BV300 transfer cancelled")
            if (staged != null) {
                if (staged.length() != bytes) throw IOException("Staged BV300 file size mismatch")
                uri = insert(item, pending = true)
                resolver.openOutputStream(requireNotNull(uri), "w")?.use { out ->
                    staged.inputStream().use { it.copyTo(out, 128 * 1024) }
                } ?: throw IOException("Could not publish BV300 media")
            }
            val finalUri = requireNotNull(uri)
            if (!readable(finalUri, bytes)) throw IOException("MediaStore item failed verification")
            if (isCancelled()) throw IOException("BV300 transfer cancelled")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val published = resolver.update(finalUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
                if (published != 1 || !readable(finalUri, bytes)) {
                    throw IOException("Could not publish verified BV300 media")
                }
            } else {
                val published = resolver.update(finalUri, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.fileName)
                }, null, null)
                if (published != 1) throw IOException("Could not publish verified BV300 media")
            }
            return SavedBv300Media(finalUri.toString(), bytes)
        } catch (failure: Throwable) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            throw failure
        } finally {
            staged?.delete()
        }
    }

    private fun insert(item: Bv300MediaItem, pending: Boolean): Uri {
        val (collection, directory, mime) = when (item.type) {
            Bv300MediaType.PHOTO -> Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES, imageMime(item.fileName))
            Bv300MediaType.VIDEO -> Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES, videoMime(item.fileName))
            Bv300MediaType.AUDIO -> Triple(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MUSIC, audioMime(item.fileName))
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,
                if (pending && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) ".pending-${item.fileName}" else item.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/BlackVingadorre/")
                put(MediaStore.MediaColumns.IS_PENDING, if (pending) 1 else 0)
            }
        }
        return resolver.insert(collection, values) ?: throw IOException("Could not create MediaStore item")
    }

    private fun readable(uri: Uri, expectedBytes: Long): Boolean = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { fd ->
            fd.statSize == expectedBytes && resolver.openInputStream(uri)?.use { it.read() >= 0 } == true
        } == true
    }.getOrDefault(false)

    private fun imageMime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "heic" -> "image/heic"
        else -> "image/jpeg"
    }

    private fun videoMime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mov" -> "video/quicktime"
        "m4v" -> "video/x-m4v"
        else -> "video/mp4"
    }

    private fun audioMime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        else -> "audio/opus"
    }
}
