package com.fersaiyan.cyanbridge.media.bv300

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** HTTP endpoints already used by the MoYoung SDK after its FILE Wi-Fi handshake. */
internal class Bv300MediaTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun catalog(baseUrl: String): String = request(baseUrl, "media.config") { input, _, _ ->
        input.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    suspend fun <T> file(
        baseUrl: String,
        remotePath: String,
        consume: (InputStream, Long, () -> Boolean) -> T,
    ): T = request(baseUrl, remotePath, consume)

    private suspend fun <T> request(
        baseUrl: String,
        relativePath: String,
        consume: (InputStream, Long, () -> Boolean) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val base = URI(baseUrl)
        require(base.scheme == "http" || base.scheme == "https") { "Invalid BV300 media URL" }
        require(base.host != null && base.rawQuery == null && base.rawFragment == null)
        val encoded = relativePath.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        val url = baseUrl.trimEnd('/') + "/" + encoded
        val call: Call = client.newCall(Request.Builder().url(url).get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!response.isSuccessful) throw IOException("BV300 HTTP ${response.code} for $relativePath")
                        val body = response.body ?: throw IOException("Empty BV300 response for $relativePath")
                        body.byteStream().use { input ->
                            val result = consume(input, body.contentLength()) { !continuation.isActive }
                            if (continuation.isActive) continuation.resume(result)
                        }
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
