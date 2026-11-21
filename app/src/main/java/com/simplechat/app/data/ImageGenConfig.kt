package com.simplechat.app.data

import kotlinx.serialization.Serializable
import com.simplechat.app.net.normalizedGeminiModel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Serializable
data class ImageGenConfig(
    val enabled: Boolean = false,
    val adapter: String = "OPENAI_IMAGES",
    val reuseChatKey: Boolean = true,
    val baseUrl: String = "",
    val modelId: String = "",
    val apiKeyEncrypted: String = "",
    val nativeResponsesEnabled: Boolean = false,
    val size: String = "1024x1024",
    val aspectRatio: String = "1:1",
    val geminiImageSize: String = "1K",
    val modelIds: List<String> = emptyList(),
    val modelCatalogBase: String = "",
) {
    override fun toString() = "ImageGenConfig(enabled=$enabled, adapter=$adapter, reuseChatKey=$reuseChatKey)"

    internal fun previewAspectRatio(adapter: String): Float? {
        val dimensions = when (adapter) {
            "GEMINI" -> aspectRatio.split(':')
            "OPENAI_IMAGES", "DASHSCOPE", "RESPONSES" -> size.split('x')
            else -> return null
        }
        val width = dimensions.firstOrNull()?.toFloatOrNull() ?: return null
        val height = dimensions.getOrNull(1)?.toFloatOrNull() ?: return null
        return (width / height).takeIf { it.isFinite() && it > 0f }
    }

    internal fun connectionBase(chatBaseUrl: String): String {
        val target = baseUrl.ifBlank { chatBaseUrl }.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid image API URL")
        require(target.username.isEmpty() && target.password.isEmpty() && target.query == null && target.fragment == null) {
            "Image API URL must not include credentials, a query, or a fragment"
        }
        if (reuseChatKey) {
            val chat = chatBaseUrl.toHttpUrlOrNull()
            require(chat != null && target.scheme == chat.scheme && target.host == chat.host && target.port == chat.port) {
                "Use a separate key for a different image API host"
            }
        }
        return target.toString()
    }

    fun key(chatKey: String): String {
        val key = if (reuseChatKey) chatKey else SecretBox.decrypt(apiKeyEncrypted)
        require(key.isNotBlank()) { "Image API key is missing" }
        require(ApiKey.isHeaderSafe(key)) { "Image API key contains invalid characters" }
        return key
    }
}

internal fun StoredSettings.nativeImageAdapter(model: String = modelId): String? {
    if (!imageGenApi.nativeResponsesEnabled || imageGenApi.enabled || model.isBlank()) return null
    if (isGeminiImageModel(model)) {
        return "GEMINI"
    }
    if (resolvedApiStyle != com.simplechat.app.net.ApiStyle.OPENAI_RESPONSES) return null
    return "RESPONSES"
}

// Official model capabilities checked 2026-11-21. Custom Images API model IDs are user-entered.
internal const val GEMINI_NANO_BANANA = "gemini-nano-banana-2.1"
internal const val OPENAI_DEFAULT_IMAGE_MODEL = "gpt-image-2.6-sunburst"
private fun isGeminiImageModel(model: String): Boolean = normalizedGeminiModel(model) in geminiImageModels

internal fun usesGeminiInteractions(model: String): Boolean = normalizedGeminiModel(model) in geminiInteractionsModels

private val geminiInteractionsModels = setOf(
    GEMINI_NANO_BANANA, "gemini-3-pro-image", "gemini-3.1-flash-image",
    "gemini-3.1-flash-lite-image",
)

private val geminiImageModels = geminiInteractionsModels + "gemini-2.5-flash-image"
