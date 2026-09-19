package com.fersaiyan.cyanbridge.agent

import android.content.Context
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

object ProSubscriptionRelayClient {
    data class ModelOption(
        val id: String,
        val label: String,
        val quotaMultiplier: Int,
        val supportsVision: Boolean = false,
    )

    data class LiveModeOption(
        val id: String,
        val label: String,
        val description: String,
        val quotaMultiplier: Int,
    ) {
        val displayLabel: String
            get() = if (label.contains(Regex("\\d+\\s*[x×]", RegexOption.IGNORE_CASE))) {
                label
            } else {
                "$label · ${quotaMultiplier}×"
            }
    }

    data class ModelCatalog(
        val models: List<ModelOption>,
        val liveModes: List<LiveModeOption>,
    )

    data class AccountInfo(
        val apiToken: String,
        val email: String,
        val emailVerified: Boolean,
    )

    private const val CONNECT_TIMEOUT_MS = 7000
    private const val READ_TIMEOUT_MS = 15000
    private const val RELAY_DOWN_HINT =
        "Server may be down or this app may need an update to use the new server address."


    fun fetchModelCatalog(context: Context): Result<ModelCatalog> = runCatching {
        val candidates = listOf("/models", "/v1/models")
        val seen = linkedMapOf<String, ModelOption>()
        val seenLiveModes = linkedMapOf<String, LiveModeOption>()

        for (path in candidates) {
            val parsed = runCatching {
                val json = requestGetJson(context, endpoint(context, path))
                parseModels(json) to parseLiveModes(json)
            }.getOrDefault(emptyList<ModelOption>() to emptyList())
            parsed.first.forEach { option ->
                val key = option.id.trim().lowercase()
                if (key.isNotBlank() && !seen.containsKey(key)) {
                    seen[key] = option
                }
            }
            parsed.second.forEach { option ->
                val key = option.id.trim().lowercase()
                if (key.isNotBlank() && !seenLiveModes.containsKey(key)) {
                    seenLiveModes[key] = option
                }
            }
        }

        if (seen.isEmpty()) {
            throw IllegalStateException("No models returned by relay")
        }
        ModelCatalog(
            models = seen.values.toList(),
            liveModes = resolveLiveModes(seenLiveModes.values.toList()),
        )
    }

    fun fetchAvailableModels(context: Context): Result<List<ModelOption>> =
        fetchModelCatalog(context).map(ModelCatalog::models)

    internal fun defaultLiveModes(): List<LiveModeOption> = listOf(
        LiveModeOption(
            id = "economy",
            label = "Gemini Live Economy",
            description = "Lower-cost conversations. Usage costs 7 quota tokens per Gemini token. Google may use conversations to improve its models.",
            quotaMultiplier = 7,
        ),
        LiveModeOption(
            id = "private",
            label = "Gemini Live Private",
            description = "Conversations are not used to train Google's models under its paid API terms. Standard Pro Live usage pricing applies.",
            quotaMultiplier = 36,
        ),
    )

    internal fun resolveLiveModes(serverModes: List<LiveModeOption>): List<LiveModeOption> {
        val byId = serverModes.associateBy { it.id.trim().lowercase() }
        return defaultLiveModes().map { fallback -> byId[fallback.id] ?: fallback }
    }

    fun fetchAccountInfo(context: Context): Result<AccountInfo> = runCatching {
        val existingToken = ProSubscriptionServerPrefs.getApiToken(context).trim()

        val payload = if (existingToken.isNotBlank()) {
            runCatching {
                requestGetJson(context, endpoint(context, "/auth/me"))
            }.getOrElse {
                requestPostJson(
                    context,
                    endpoint(context, "/auth/register"),
                    JSONObject().put("api_token", existingToken)
                )
            }
        } else {
            requestPostJson(context, endpoint(context, "/auth/register"), JSONObject())
        }

        parseAccount(payload).also { account ->
            if (account.apiToken.isNotBlank()) {
                ProSubscriptionServerPrefs.setApiToken(context, account.apiToken)
            }
            if (account.email.isNotBlank()) {
                ProSubscriptionServerPrefs.setAccountEmail(context, account.email)
                if (account.emailVerified) {
                    ProSubscriptionServerPrefs.setVerifiedAccountEmail(context, account.email)
                }
            }
        }
    }

    private fun parseModels(payload: JSONObject): List<ModelOption> {
        val out = linkedMapOf<String, ModelOption>()

        fun putOption(id: String, label: String, quotaMultiplier: Int, supportsVision: Boolean = false) {
            val cleanId = id.trim()
            if (cleanId.isBlank()) return
            val key = cleanId.lowercase()
            if (out.containsKey(key)) return

            val cleanLabel = label.trim().ifBlank { cleanId }
            out[key] = ModelOption(
                id = cleanId,
                label = cleanLabel,
                quotaMultiplier = quotaMultiplier.coerceAtLeast(1),
                supportsVision = supportsVision,
            )
        }

        fun readModelArray(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.opt(i)
                when (item) {
                    is String -> {
                        val clean = item.trim()
                        if (clean.isNotBlank()) putOption(clean, clean, 1)
                    }

                    is JSONObject -> {
                        val id = item.optString("id").trim()
                        val model = item.optString("model").trim()
                        val name = item.optString("name").trim()
                        val label = item.optString("label").trim()
                        val displayName = item.optString("display_name").trim()
                        val multiplier = intOrNull(item, "quota_multiplier")
                            ?: intOrNull(item, "multiplier")
                            ?: 1
                        val supportsVision = booleanOrNull(item, "supports_vision")
                            ?: booleanOrNull(item, "supportsVision")
                            ?: jsonArrayContains(item.optJSONArray("input_modalities"), "image")
                            ?: jsonArrayContains(item.optJSONArray("inputModalities"), "image")
                            ?: false
                        val pick = when {
                            id.isNotBlank() -> id
                            model.isNotBlank() -> model
                            name.isNotBlank() -> name
                            else -> ""
                        }
                        val pickLabel = when {
                            label.isNotBlank() -> label
                            displayName.isNotBlank() -> displayName
                            name.isNotBlank() -> name
                            else -> pick
                        }
                        if (pick.isNotBlank()) {
                            putOption(pick, pickLabel, multiplier, supportsVision)
                        }
                    }
                }
            }
        }

        readModelArray(payload.optJSONArray("data"))
        readModelArray(payload.optJSONArray("models"))
        readModelArray(payload.optJSONObject("result")?.optJSONArray("models"))

        return out.values.toList()
    }

    internal fun parseLiveModes(payload: JSONObject): List<LiveModeOption> {
        val array = payload.optJSONArray("live_modes")
            ?: payload.optJSONArray("liveModes")
            ?: payload.optJSONObject("result")?.optJSONArray("live_modes")
            ?: return emptyList()
        val out = linkedMapOf<String, LiveModeOption>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim().lowercase()
            if (id.isBlank() || out.containsKey(id)) continue
            val label = item.optString("label").trim().ifBlank { id }
            val description = item.optString("description").trim()
            val multiplier = intOrNull(item, "quota_multiplier")
                ?: intOrNull(item, "quotaMultiplier")
                ?: continue
            if (multiplier < 1) continue
            out[id] = LiveModeOption(
                id = id,
                label = label,
                description = description,
                quotaMultiplier = multiplier,
            )
        }
        return out.values.toList()
    }

    private fun parseAccount(payload: JSONObject): AccountInfo {
        return AccountInfo(
            apiToken = payload.optString("api_token").trim(),
            email = payload.optString("email").trim(),
            emailVerified = payload.optBoolean("email_verified", false),
        )
    }

    private fun intOrNull(json: JSONObject, key: String): Int? {
        if (!json.has(key) || json.isNull(key)) return null
        val raw = json.opt(key)
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }

    private fun booleanOrNull(json: JSONObject, key: String): Boolean? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val raw = json.opt(key)) {
            is Boolean -> raw
            is String -> raw.trim().lowercase().let { value ->
                when (value) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun jsonArrayContains(array: JSONArray?, expected: String): Boolean? {
        if (array == null) return null
        return (0 until array.length()).any { index ->
            array.optString(index).equals(expected, ignoreCase = true)
        }
    }

    private fun endpoint(context: Context, path: String): String {
        val base = AiProviderPrefs.getRelayBaseUrl(context).trim().trimEnd('/')
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "Relay URL must start with http:// or https://"
        }
        return "$base$path"
    }

    private fun requestGetJson(context: Context, url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "GET"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json")
        addAuthHeader(context, conn)

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream ?: conn.inputStream)).use { it.readText() }
        conn.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $body")
        }
        return JSONObject(body.ifBlank { "{}" })
    }

    private fun requestPostJson(context: Context, url: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        addAuthHeader(context, conn)
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = BufferedReader(InputStreamReader(stream ?: conn.inputStream)).use { it.readText() }
        conn.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code: $response")
        }
        return JSONObject(response.ifBlank { "{}" })
    }

    private fun addAuthHeader(context: Context, conn: HttpURLConnection) {
        val token = ProSubscriptionServerPrefs.getApiToken(context)
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
    }

    fun relayUnavailableHint(error: Throwable?): String? {
        var cur = error
        while (cur != null) {
            when (cur) {
                is UnknownHostException,
                is ConnectException,
                is SocketTimeoutException,
                is IOException,
                -> return RELAY_DOWN_HINT
            }
            cur = cur.cause
        }

        val msg = error?.message.orEmpty().lowercase()
        if (
            msg.contains("failed to connect") ||
            msg.contains("connection refused") ||
            msg.contains("timeout") ||
            msg.contains("unreachable") ||
            msg.contains("unable to resolve host") ||
            msg.contains("no address associated") ||
            msg.contains("relay unavailable")
        ) {
            return RELAY_DOWN_HINT
        }
        return null
    }

    fun relayUnavailableHintFromText(text: String): String? {
        return relayUnavailableHint(IllegalStateException(text))
    }
}
