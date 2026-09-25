package com.fersaiyan.cyanbridge.media.bv300

import java.util.Locale

enum class Bv300MediaType { PHOTO, VIDEO, AUDIO }

data class Bv300MediaItem(
    val id: String,
    val remotePath: String,
    val fileName: String,
    val type: Bv300MediaType,
    val localUri: String? = null,
    val localBytes: Long? = null,
    val remoteAvailable: Boolean = true,
    val lastSeenAtMs: Long = 0L,
)

/** MoYoung's media.config is a newline-separated list of relative file paths. */
object Bv300MediaCatalog {
    fun parse(deviceAddress: String, content: String, seenAtMs: Long): List<Bv300MediaItem> =
        content.lineSequence().mapNotNull { raw ->
            val path = raw.trim().replace("\r", "")
            if (path.isBlank() || path.startsWith('/') || path.contains('\\') ||
                path.split('/').any { it.isBlank() || it == "." || it == ".." } ||
                path.any { it.isISOControl() } || path.contains('?') || path.contains('#')
            ) return@mapNotNull null
            val name = path.substringAfterLast('/')
            val type = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
                "jpg", "jpeg", "png", "heic" -> Bv300MediaType.PHOTO
                "mp4", "mov", "m4v" -> Bv300MediaType.VIDEO
                "opus", "ogg", "wav", "mp3", "m4a" -> Bv300MediaType.AUDIO
                else -> return@mapNotNull null
            }
            Bv300MediaItem(
                id = "${deviceAddress.lowercase(Locale.US)}:$path",
                remotePath = path,
                fileName = name,
                type = type,
                lastSeenAtMs = seenAtMs,
            )
        }.distinctBy(Bv300MediaItem::id).toList()
}

fun downloadNewIds(items: List<Bv300MediaItem>): Set<String> = items.asSequence()
    .filter { it.remoteAvailable && it.localUri == null }
    .map(Bv300MediaItem::id)
    .toSet()

internal fun verifyTransferBytes(actual: Long, expected: Long): Boolean =
    actual > 0L && (expected <= 0L || actual == expected)
