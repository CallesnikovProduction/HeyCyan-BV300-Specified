package com.fersaiyan.cyanbridge.media.bv300

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungW620Manager
import com.fersaiyan.cyanbridge.devices.moyoung.MoyoungWifiCredentials
import com.fersaiyan.cyanbridge.glasses.GlassesSession
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.fersaiyan.cyanbridge.glasses.GlassesSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

internal data class Bv300TransferSummary(val downloaded: Int, val failed: Int, val skipped: Int)

/** Remote delete is deliberately absent: this SDK exposes a protobuf type but no confirmed safe deletion API. */
internal class Bv300MediaRepository(context: Context) {
    val remoteDeleteSupported: Boolean = false

    private val appContext = context.applicationContext
    private val manager = MoyoungW620Manager.getInstance(appContext)
    private val index = Bv300DownloadedIndex(appContext)
    private val transport = Bv300MediaTransport()
    private val phoneStore = Bv300PhoneMediaStore(appContext)
    private val phoneLibrary = Bv300PhoneLibrary(appContext)

    fun connected(): Boolean = manager.isConnected()

    suspend fun cached(): List<Bv300MediaItem> = withContext(Dispatchers.IO) {
        val indexed = index.load().map { item ->
            if (index.verified(item)) item.copy(remoteAvailable = false)
            else item.copy(localUri = null, localBytes = null, remoteAvailable = false)
        }
        withPhoneLibrary(indexed)
    }

    /** Delete only selected phone-side MediaStore copies; no glasses session or remote command is used. */
    suspend fun deleteLocal(requestedIds: Set<String>, visibleItems: List<Bv300MediaItem>): Bv300LocalDeleteSummary =
        withContext(Dispatchers.IO) {
            val deleted = mutableSetOf<String>()
            var failed = 0
            for (uriString in localDeleteTargets(visibleItems, requestedIds)) {
                coroutineContext.ensureActive()
                val uri = Uri.parse(uriString)
                if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) {
                    failed++
                    continue
                }
                try {
                    if (appContext.contentResolver.delete(uri, null, null) > 0) deleted += uriString
                    else failed++
                } catch (error: Exception) {
                    Log.w("Bv300Media", "Could not delete selected phone media", error)
                    failed++
                }
            }
            if (deleted.isNotEmpty()) {
                runCatching { index.save(afterLocalDelete(index.load(), deleted)) }
                    .onFailure { Log.w("Bv300Media", "Deleted phone media; index will reconcile on next load", it) }
            }
            Bv300LocalDeleteSummary(deleted, failed)
        }

    suspend fun refresh(onWaiting: () -> Unit = {}): List<Bv300MediaItem> = withMediaSession(onWaiting) { baseUrl ->
        readCatalog(baseUrl)
    }

    suspend fun download(
        requestedIds: Set<String>,
        onWaiting: () -> Unit,
        onCatalog: (List<Bv300MediaItem>) -> Unit,
        onItemStarted: (Bv300MediaItem, Int, Int) -> Unit,
        onProgress: (Bv300MediaItem, Long, Long) -> Unit,
        onItemFinished: (Bv300MediaItem, String?) -> Unit,
    ): Bv300TransferSummary = withMediaSession(onWaiting) { baseUrl ->
        val current = readCatalog(baseUrl).toMutableList()
        onCatalog(current.toList())
        val selected = current.filter { it.id in requestedIds && it.remoteAvailable }
        var downloaded = 0
        var failed = requestedIds.size - selected.size
        var skipped = 0
        selected.forEachIndexed { position, item ->
            coroutineContext.ensureActive()
            if (item.localUri != null && index.verified(item)) {
                skipped++
                return@forEachIndexed
            }
            onItemStarted(item, position + 1, selected.size)
            try {
                val saved = transport.file(baseUrl, item.remotePath) { input, expected, cancelled ->
                    phoneStore.save(item, input, expected, { done, total ->
                        onProgress(item, done, total)
                    }, cancelled)
                }
                val updated = item.copy(localUri = saved.uri, localBytes = saved.bytes)
                val at = current.indexOfFirst { it.id == item.id }
                current[at] = updated
                index.save(current)
                downloaded++
                onItemFinished(updated, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!manager.isConnected()) throw IOException("BV300 disconnected during media transfer", error)
                failed++
                onItemFinished(item, error.message ?: "Download failed")
            }
        }
        Bv300TransferSummary(downloaded, failed, skipped)
    }

    private suspend fun readCatalog(baseUrl: String): List<Bv300MediaItem> {
        val address = manager.state.value.deviceAddress ?: throw IOException("BV300 device identity unavailable")
        val content = transport.catalog(baseUrl)
        val remote = Bv300MediaCatalog.parse(address, content, System.currentTimeMillis())
        if (content.isNotBlank() && remote.isEmpty()) {
            throw IOException("BV300 media.config had no recognized photos, videos or audio; saved library was preserved")
        }
        val merged = reconcileMedia(remote, index.load(), index::verified)
        index.save(merged)
        return withPhoneLibrary(merged)
    }

    private fun withPhoneLibrary(items: List<Bv300MediaItem>): List<Bv300MediaItem> {
        val unindexed = runCatching {
            phoneLibrary.list(items.mapNotNullTo(mutableSetOf(), Bv300MediaItem::localUri))
        }.onFailure { Log.w("Bv300Media", "Phone-only library unavailable", it) }.getOrDefault(emptyList())
        return items + unindexed
    }

    private suspend fun <T> withMediaSession(onWaiting: () -> Unit, action: suspend (String) -> T): T =
        withContext(Dispatchers.IO) {
            if (!manager.isConnected()) throw IOException("BV300 is disconnected. Phone copies remain available.")
            val lease = acquireLease(onWaiting)
            try {
                val prefs = appContext.getSharedPreferences("moyoung_w620", Context.MODE_PRIVATE)
                val credentials = MoyoungWifiCredentials(
                    ssid = prefs.getString("file_wifi_ssid", "Glass-01").orEmpty(),
                    password = prefs.getString("file_wifi_password", "12345678").orEmpty(),
                )
                require(credentials.ssid.isNotBlank()) { "Set BV300 Wi-Fi credentials in glasses settings" }
                manager.withMediaCatalogConnection(credentials, action)
            } finally {
                GlassesSessionCoordinator.release(lease)
            }
        }

    private suspend fun acquireLease(onWaiting: () -> Unit): GlassesSessionLease {
        repeat(30) { attempt ->
            coroutineContext.ensureActive()
            GlassesSessionCoordinator.tryAcquireLease(GlassesSession.MEDIA_SYNC)?.let { return it }
            if (attempt == 0) onWaiting()
            delay(500L)
        }
        throw IOException("Media sync is waiting for another glasses operation; retry when it finishes")
    }
}
