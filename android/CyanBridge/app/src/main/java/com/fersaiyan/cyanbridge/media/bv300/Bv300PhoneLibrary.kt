package com.fersaiyan.cyanbridge.media.bv300

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.fersaiyan.cyanbridge.media.SyncedMediaFolder

/** Public app-created media without a trustworthy BV300 remote identity stays phone-only. */
internal class Bv300PhoneLibrary(private val context: Context) {
    fun list(excludingUris: Set<String>): List<Bv300MediaItem> {
        val folders = listOf(
            SyncedMediaFolder.relativePathWithTrailingSlash,
            "${Environment.DIRECTORY_PICTURES}/BlackVingadorre/",
            "${Environment.DIRECTORY_MOVIES}/BlackVingadorre/",
            "${Environment.DIRECTORY_MUSIC}/BlackVingadorre/",
        )
        val mediaTypes = listOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
            MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO,
        )
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val pathColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH
            else MediaStore.MediaColumns.DATA
        projection += pathColumn
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${mediaTypes.joinToString { "?" }}) AND (" +
            folders.joinToString(" OR ") { "$pathColumn LIKE ?" } + ")"
        val folderArgs = if (Build.VERSION.SDK_INT >= 29) folders.map { "$it%" }
            else folders.map { "%/$it%" }
        val args = (mediaTypes.map(Int::toString) + folderArgs).toTypedArray()
        val found = mutableListOf<Bv300MediaItem>()
        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"), projection.toTypedArray(), selection, args,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (cursor.moveToNext()) {
                val bytes = cursor.getLong(sizeColumn)
                if (bytes <= 0L) continue
                val type = when (cursor.getInt(typeColumn)) {
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> Bv300MediaType.PHOTO
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> Bv300MediaType.VIDEO
                    MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> Bv300MediaType.AUDIO
                    else -> continue
                }
                val id = cursor.getLong(idColumn)
                val collection = when (type) {
                    Bv300MediaType.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    Bv300MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    Bv300MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val uri = ContentUris.withAppendedId(collection, id).toString()
                if (uri in excludingUris) continue
                found += Bv300MediaItem(
                    id = "phone:$uri",
                    remotePath = "",
                    fileName = cursor.getString(nameColumn) ?: "media_$id",
                    type = type,
                    localUri = uri,
                    localBytes = bytes,
                    remoteAvailable = false,
                )
            }
        }
        return found
    }
}
