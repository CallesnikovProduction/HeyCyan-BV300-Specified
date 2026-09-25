package com.fersaiyan.cyanbridge.media.bv300

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Small durable mapping from a glasses path to a verified public MediaStore URI. */
internal class Bv300DownloadedIndex(private val context: Context) {
    private val prefs = context.getSharedPreferences("bv300_media_index", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<Bv300MediaItem> {
        val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrDefault(JSONArray())
        return buildList {
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                val type = runCatching { Bv300MediaType.valueOf(json.getString("type")) }.getOrNull() ?: continue
                add(Bv300MediaItem(
                    id = json.optString("id"),
                    remotePath = json.optString("remotePath"),
                    fileName = json.optString("fileName"),
                    type = type,
                    localUri = json.optString("localUri").ifBlank { null },
                    localBytes = json.optLong("localBytes").takeIf { it > 0 },
                    remoteAvailable = false,
                    lastSeenAtMs = json.optLong("lastSeenAtMs"),
                ))
            }
        }.filter { it.id.isNotBlank() && it.remotePath.isNotBlank() }
    }

    @Synchronized
    fun save(items: List<Bv300MediaItem>) {
        val array = JSONArray()
        items.filter { it.remotePath.isNotBlank() }.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("remotePath", item.remotePath)
                put("fileName", item.fileName)
                put("type", item.type.name)
                put("localUri", item.localUri.orEmpty())
                put("localBytes", item.localBytes ?: 0L)
                put("lastSeenAtMs", item.lastSeenAtMs)
            })
        }
        check(prefs.edit().putString("items", array.toString()).commit()) { "Could not save BV300 media index" }
    }

    fun verified(item: Bv300MediaItem): Boolean {
        val uri = item.localUri?.let(Uri::parse) ?: return false
        val expected = item.localBytes?.takeIf { it > 0 } ?: return false
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize == expected &&
                    context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true
            } == true
        }.getOrDefault(false)
    }
}

/** A stale database entry must never make Download New skip a missing phone file. */
internal fun reconcileMedia(
    remote: List<Bv300MediaItem>,
    indexed: List<Bv300MediaItem>,
    verified: (Bv300MediaItem) -> Boolean,
): List<Bv300MediaItem> {
    val known = indexed.associateBy(Bv300MediaItem::id)
    val remoteIds = remote.mapTo(mutableSetOf(), Bv300MediaItem::id)
    val available = remote.map { item ->
        val old = known[item.id]
        if (old != null && verified(old)) item.copy(localUri = old.localUri, localBytes = old.localBytes)
        else item
    }
    val phoneOnly = indexed.filter { it.id !in remoteIds && verified(it) }
        .map { it.copy(remoteAvailable = false) }
    return available + phoneOnly
}
