package com.fersaiyan.cyanbridge.ai.live

import android.content.Context
import com.fersaiyan.cyanbridge.agent.ProSubscriptionRelayClient
import com.fersaiyan.cyanbridge.ai.router.AiProviderPrefs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class LiveTokenConfig(
    val token: String,
    val model: String,
    val websocketUrl: String,
    val expiresAtMs: Long,
    val reservationId: String,
    val authorizationHeader: String? = null,
    /** Optional API key reserved for explicit local/debug providers. Production Pro never receives one. */
    val apiKey: String? = null,
    /** Optional setup override retained for provider/test compatibility. */
    val setupJson: String? = null,
    val economy: Boolean = false,
    val freeTier: Boolean = false,
)

interface GeminiLiveTokenProvider {
    suspend fun requestToken(language: String, imagePrompt: String): LiveTokenConfig
}

class DefaultGeminiLiveTokenProvider(
    private val appContext: Context,
) : GeminiLiveTokenProvider {
    override suspend fun requestToken(language: String, imagePrompt: String): LiveTokenConfig {
        val account = ProSubscriptionRelayClient.fetchAccountInfo(appContext).getOrThrow()
        val authToken = account.apiToken.trim()
        check(authToken.isNotBlank()) { "Sign in to CyanBridge before starting Gemini Live" }
        val base = AiProviderPrefs.getRelayBaseUrl(appContext).trim().trimEnd('/')
        check(base.startsWith("https://")) { "Gemini Live requires a secure relay URL" }
        val httpUrl = base.toHttpUrl().newBuilder()
            .addPathSegments("api/pro/live/free")
            .addQueryParameter("language", language)
            .addQueryParameter("image_prompt", imagePrompt.take(400))
            .build()
            .toString()
        return LiveTokenConfig(
            token = "",
            model = "models/gemini-3.1-flash-live-preview",
            websocketUrl = httpUrl.replaceFirst("https://", "wss://"),
            expiresAtMs = System.currentTimeMillis() + 13 * 60 * 1000L,
            reservationId = "free-proxy",
            authorizationHeader = "Bearer $authToken",
            freeTier = true,
        )
    }
}

/** Direct provider for local end-to-end testing with a Gemini API key (bypasses Vercel relay). */
class DirectGeminiApiKeyLiveTokenProvider(
    private val apiKey: String,
    private val http: OkHttpClient = OkHttpClient(),
) : GeminiLiveTokenProvider {
    override suspend fun requestToken(language: String, imagePrompt: String): LiveTokenConfig {
        check(apiKey.isNotBlank()) { "Gemini API key required" }
        val now = System.currentTimeMillis()
        val expireTime = java.time.Instant.ofEpochMilli(now + 15 * 60 * 1000).toString()
        val newSessionExpireTime = java.time.Instant.ofEpochMilli(now + 60 * 1000).toString()
        val body = JSONObject()
            .put("uses", 1)
            .put("expireTime", expireTime)
            .put("newSessionExpireTime", newSessionExpireTime)
            .put(
                "fieldMask",
                "model,generationConfig,contextWindowCompression,systemInstruction,realtimeInputConfig,inputAudioTranscription,outputAudioTranscription",
            )
            .put("bidiGenerateContentSetup", JSONObject()
                .put("model", "models/gemini-3.1-flash-live-preview")
                .put("generationConfig", JSONObject().put("responseModalities", org.json.JSONArray().put("AUDIO")))
                .put("systemInstruction", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", "You are Gemini Live in CyanBridge smart glasses. Reply in $language."))))
                .put("realtimeInputConfig", JSONObject()
                    .put("automaticActivityDetection", JSONObject()
                        .put("disabled", false)
                        .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        .put("prefixPaddingMs", 40)
                        .put("silenceDurationMs", 500))
                    .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                    .put("turnCoverage", "TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO"))
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject()))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/auth_tokens")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        http.newCall(req).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = JSONObject(raw.ifBlank { "{}" })
            if (!response.isSuccessful) throw IllegalStateException("auth_tokens failed: $raw")
            val name = json.getString("name")
            val exp = json.optString("expireTime", expireTime)
            val expiresAt = java.time.Instant.parse(exp).toEpochMilli()
            val websocketUrl = "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("access_token", name)
                .build()
                .toString()
                .replaceFirst("https://", "wss://")
            return LiveTokenConfig(
                token = name,
                model = "models/gemini-3.1-flash-live-preview",
                websocketUrl = websocketUrl,
                expiresAtMs = expiresAt,
                reservationId = "direct",
                authorizationHeader = null,
                apiKey = null,
            )
        }
    }
}
