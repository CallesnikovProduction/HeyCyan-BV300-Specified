package com.fersaiyan.cyanbridge.media.bv300

internal data class Bv300LocalDeleteSummary(val deletedUris: Set<String>, val failed: Int)

/** Only selected entries with a phone copy are deletion candidates. */
internal fun localDeleteTargets(items: List<Bv300MediaItem>, requestedIds: Set<String>): List<String> =
    items.asSequence().filter { it.id in requestedIds }.mapNotNull(Bv300MediaItem::localUri).distinct().toList()

/** Keep the remote catalog entry so a deleted local copy can be downloaded again. */
internal fun afterLocalDelete(items: List<Bv300MediaItem>, deletedUris: Set<String>): List<Bv300MediaItem> =
    items.mapNotNull { item ->
        if (item.localUri !in deletedUris) item
        else if (item.remotePath.isBlank()) null
        else item.copy(localUri = null, localBytes = null)
    }
