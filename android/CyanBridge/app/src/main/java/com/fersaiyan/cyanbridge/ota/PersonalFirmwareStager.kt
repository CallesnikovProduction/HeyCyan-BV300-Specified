package com.fersaiyan.cyanbridge.ota

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/** Copies a user-selected firmware document into private OTA staging storage. */
class PersonalFirmwareStager(private val context: Context) {
    fun copy(uri: Uri, target: OtaTarget): File {
        val displayName = displayName(uri)
            ?: throw IllegalArgumentException("The selected document has no filename")
        if (!target.isExpectedFirmwareFilename(displayName)) {
            throw IllegalArgumentException("Select a ${target.expectedFirmwareExtension()} file for this target")
        }

        val otaDir = File(context.filesDir, "ota/personal")
        if (!otaDir.exists() && !otaDir.mkdirs()) {
            throw IllegalStateException("Could not create private OTA storage")
        }
        val targetName = target.name.lowercase()
        val outputFile = File(
            otaDir,
            "personal_${targetName}_${System.currentTimeMillis()}${target.expectedFirmwareExtension()}",
        )
        val stagingFile = File(otaDir, ".${outputFile.name}.partial")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(stagingFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("The selected document cannot be read")

            if (stagingFile.length() <= 0L) {
                throw IllegalArgumentException("The selected firmware file is empty")
            }
            if (!stagingFile.renameTo(outputFile)) {
                throw IllegalStateException("Could not finalize the selected firmware file")
            }
            return outputFile
        } finally {
            if (stagingFile.exists()) stagingFile.delete()
        }
    }

    private fun displayName(uri: Uri): String? {
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.trim()?.takeIf { it.isNotEmpty() }
    }
}
