package com.fersaiyan.cyanbridge.agent

import android.content.Context
import android.content.SharedPreferences
import android.util.Patterns
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Locale

object ProSubscriptionServerPrefs {
    private const val PREFS_NAME = "pro_subscription_server_prefs"
    private const val SECRET_PREFS_NAME = "pro_subscription_server_secrets"
    private const val LEGACY_CHECKOUT_PREFS_NAME = "pending_legacy_checkout_prefs"
    private const val LEGACY_DONATION_PREFS_NAME = "pending_donation_prefs"
    private const val KEY_VERIFY_URL = "verify_url"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_ACCOUNT_EMAIL = "account_email"
    private const val KEY_VERIFIED_ACCOUNT_EMAIL = "verified_account_email"
    private const val KEY_LEGACY_CREDENTIALS_CLEARED = "legacy_credentials_cleared"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun secretPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            SECRET_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getVerifyUrl(context: Context): String =
        prefs(context).getString(KEY_VERIFY_URL, "").orEmpty().trim()

    fun setVerifyUrl(context: Context, url: String?) {
        val value = url?.trim().orEmpty()
        prefs(context).edit().apply {
            if (value.isBlank()) remove(KEY_VERIFY_URL) else putString(KEY_VERIFY_URL, value)
        }.apply()
    }

    fun getApiToken(context: Context): String {
        clearLegacyPlaintextCredentials(context)
        val encryptedPrefs = secretPrefs(context)
        val encrypted = encryptedPrefs.getString(KEY_API_TOKEN, "").orEmpty().trim()
        if (encrypted.isNotBlank()) return encrypted

        // Migrate the legacy plaintext token once, then remove the old copy.
        val legacy = prefs(context).getString(KEY_API_TOKEN, "").orEmpty().trim()
        if (legacy.isBlank()) return ""

        val saved = encryptedPrefs.edit().putString(KEY_API_TOKEN, legacy).commit()
        prefs(context).edit().remove(KEY_API_TOKEN).commit()
        return if (saved) legacy else ""
    }

    fun setApiToken(context: Context, token: String?) {
        clearLegacyPlaintextCredentials(context)
        val value = token?.trim().orEmpty()
        val saved = secretPrefs(context).edit().apply {
            if (value.isBlank()) remove(KEY_API_TOKEN) else putString(KEY_API_TOKEN, value)
        }.commit()
        check(saved) { "Unable to securely store the server account token" }
        prefs(context).edit().remove(KEY_API_TOKEN).commit()
    }

    fun getAccountEmail(context: Context): String =
        sanitizeAccountEmail(prefs(context).getString(KEY_ACCOUNT_EMAIL, ""))

    fun setAccountEmail(context: Context, email: String?) {
        val value = sanitizeAccountEmail(email)
        prefs(context).edit().apply {
            if (value.isBlank()) remove(KEY_ACCOUNT_EMAIL) else putString(KEY_ACCOUNT_EMAIL, value)
            if (value != getVerifiedAccountEmail(context)) remove(KEY_VERIFIED_ACCOUNT_EMAIL)
        }.apply()
    }

    fun getVerifiedAccountEmail(context: Context): String =
        sanitizeAccountEmail(prefs(context).getString(KEY_VERIFIED_ACCOUNT_EMAIL, ""))

    fun isAccountEmailVerified(context: Context, email: String?): Boolean =
        normalizeAccountEmail(email) == getVerifiedAccountEmail(context)

    fun setVerifiedAccountEmail(context: Context, email: String?) {
        val value = sanitizeAccountEmail(email)
        prefs(context).edit().apply {
            if (value.isBlank()) remove(KEY_VERIFIED_ACCOUNT_EMAIL) else putString(KEY_VERIFIED_ACCOUNT_EMAIL, value)
            if (value.isBlank()) remove(KEY_ACCOUNT_EMAIL) else putString(KEY_ACCOUNT_EMAIL, value)
        }.apply()
    }

    fun normalizeAccountEmail(email: String?): String =
        email?.trim()?.lowercase(Locale.US).orEmpty()

    fun isUsableAccountEmail(email: String?): Boolean {
        val normalized = normalizeAccountEmail(email)
        if (normalized.isBlank()) return false
        if (normalized.startsWith("relay_")) return false
        if (normalized.endsWith("@cyanbridge.placeholder")) return false
        return Patterns.EMAIL_ADDRESS.matcher(normalized).matches()
    }

    private fun sanitizeAccountEmail(email: String?): String {
        val normalized = normalizeAccountEmail(email)
        return if (isUsableAccountEmail(normalized)) normalized else ""
    }

    private fun clearLegacyPlaintextCredentials(context: Context) {
        val serverPrefs = prefs(context)
        if (serverPrefs.getBoolean(KEY_LEGACY_CREDENTIALS_CLEARED, false)) return

        context.getSharedPreferences(LEGACY_CHECKOUT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        context.getSharedPreferences(LEGACY_DONATION_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("api_token")
            .remove("email")
            .remove("subscription_id")
            .apply()
        serverPrefs.edit().putBoolean(KEY_LEGACY_CREDENTIALS_CLEARED, true).apply()
    }
}
