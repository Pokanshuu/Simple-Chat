package com.simplechat.app.data

import kotlinx.serialization.Serializable

/** Local retry data. Credentials and attachment bytes always come from their original stores. */
@Serializable
data class ImageRequest(
    val prompt: String,
    val modelId: String,
    val adapter: String,
    val independent: Boolean,
    val size: String? = null,
    val aspectRatio: String? = null,
    val geminiImageSize: String? = null,
    val usesReferences: Boolean = false,
    val referenceMessageId: String? = null,
) {
    internal fun previewAspectRatio(): Float? = ImageGenConfig(
        size = size.orEmpty(), aspectRatio = aspectRatio.orEmpty(),
    ).previewAspectRatio(adapter)

    internal fun encode(): String = apiProfileJson.encodeToString(this)

    companion object {
        internal fun decode(raw: String?): ImageRequest? = raw?.let {
            runCatching { apiProfileJson.decodeFromString<ImageRequest>(it) }.getOrNull()
        }
    }
}
