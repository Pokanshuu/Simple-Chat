package com.simplechat.app.data

import com.simplechat.app.R
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Stored per chat profile. Independent credentials are always encrypted at rest. */
@Serializable
data class ServiceApiConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val reuseChatKey: Boolean = true,
    val apiKeyEncrypted: String = "",
    val searchPath: String = "/v1/tools/search",
    val fetchPath: String = "/v1/tools/fetch",
    val filesPath: String = "/files",
    val count: Int = 5,
    val purpose: String = "text_recognition",
    val longTextChars: Int = 16_000,
) {
    override fun toString() = "ServiceApiConfig(enabled=$enabled, reuseChatKey=$reuseChatKey)"

    fun endpoint(chatBaseUrl: String, path: String): String {
        val configured = baseUrl.ifBlank { chatBaseUrl }
        val base = (com.simplechat.app.net.officialChatRoot(configured) ?: configured).trim().trimEnd('/')
            .removeSuffix("/anthropic/v1").removeSuffix("/anthropic")
        val url = base.toHttpUrlOrNull() ?: throw AppTextException(R.string.service_invalid_url)
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null ||
            !path.startsWith('/') || path.startsWith("//") || '?' in path || '#' in path ||
            path.split('/').any { it == ".." || it == "." || '%' in it || 92.toChar() in it }) {
            throw AppTextException(R.string.service_invalid_url)
        }
        val root = if (base.endsWith("/v1") && path.startsWith("/v1/")) base.removeSuffix("/v1") else base
        return root + path
    }

    fun key(chatKey: String): String {
        val key = if (reuseChatKey) chatKey else SecretBox.decrypt(apiKeyEncrypted)
        if (key.isBlank()) throw AppTextException(R.string.service_key_missing)
        if (!ApiKey.isHeaderSafe(key)) throw AppTextException(R.string.error_api_key_chars)
        return key
    }
}
