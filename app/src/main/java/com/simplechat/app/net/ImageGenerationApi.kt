package com.simplechat.app.net

import com.simplechat.app.data.ImageGenConfig
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.ApiImage
import com.simplechat.app.data.OPENAI_DEFAULT_IMAGE_MODEL
import com.simplechat.app.data.usesGeminiInteractions
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.EOFException
import java.net.SocketException
import java.net.ProtocolException
import kotlinx.coroutines.delay
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Response
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class GeneratedImage(val bytes: ByteArray, val mime: String)

class ImageGenerationApi(
    private val client: OkHttpClient = ChatApi.defaultClient().newBuilder()
        .readTimeout(180, TimeUnit.SECONDS).callTimeout(4, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** List models without creating an image or consuming image generation quota. */
    suspend fun fetchModels(config: ImageGenConfig, chatBaseUrl: String, chatKey: String): List<String> =
        withTimeoutOrNull(30_000) {
            withContext(Dispatchers.IO) {
                val request = imageModelListingRequest(config, chatBaseUrl, chatKey)
                cancellableRequest(request) { response ->
                    if (!response.isSuccessful) throw ServiceApi.HttpFailure(response.code, null)
                    val source = response.body.source()
                    val limit = 2 * 1024 * 1024L
                    if (source.request(limit + 1)) throw IOException("Model listing is too large")
                    parseRemoteModelCatalog(json, source.readUtf8()).ids.map {
                        if (config.adapter == "GEMINI") it.removePrefix("models/") else it
                    }.distinct()
                }
            }
        } ?: throw SocketTimeoutException("Model listing timed out")

    suspend fun generateIndependent(
        config: ImageGenConfig, chatBaseUrl: String, chatKey: String, prompt: String,
        references: List<Attachment> = emptyList(),
    ): GeneratedImage {
        require(config.enabled) { "Independent image API is disabled" }
        require(prompt.isNotBlank()) { "Prompt is empty" }
        require(config.modelId.isNotBlank()) { "Image model is missing" }
        if (config.adapter == "GEMINI") return generateGemini(config, chatBaseUrl, chatKey, prompt, references)
        if (config.adapter == "DASHSCOPE") return generateDashScope(config, chatBaseUrl, chatKey, prompt, references)
        require(config.adapter == "OPENAI_IMAGES") { "Unsupported image API protocol" }
        val base = config.connectionBase(chatBaseUrl).trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/responses")
        val endpoint = when {
            base.endsWith("/images/generations") -> base
            base.endsWith("/v1") -> "$base/images/generations"
            else -> "$base/v1/images/generations"
        }
        val body = buildJsonObject {
            put("model", config.modelId)
            put("prompt", prompt)
            put("size", config.size)
            put("n", 1)
        }
        val request = Request.Builder().header("Authorization", "Bearer ${config.key(chatKey)}")
        if (references.isEmpty()) {
            request.url(endpoint).post(body.toString().toRequestBody("application/json".toMediaType()))
        } else {
            val editBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", config.modelId).addFormDataPart("prompt", prompt)
                .addFormDataPart("size", config.size)
            references.forEach { reference ->
                val bytes = Base64.getDecoder().decode(reference.data)
                editBody.addFormDataPart("image[]", reference.name, bytes.toRequestBody(ApiImage.legalMediaType(reference.mime).toMediaType()))
            }
            request.url(endpoint.removeSuffix("/generations") + "/edits").post(editBody.build())
        }
        return execute(request.build()) { root ->
            val item = (root["data"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw IOException(errorMessage(root) ?: "Image API returned no image")
            val encoded = item.string("b64_json")
            if (encoded != null) decodeImage(encoded, item.string("mime_type") ?: "image/png")
            else item.string("url")?.let { downloadImage(it) }
                ?: throw IOException("Image API returned no image data")
        }
    }

    private suspend fun generateDashScope(
        config: ImageGenConfig, chatBaseUrl: String, chatKey: String, prompt: String, references: List<Attachment>,
    ): GeneratedImage {
        val path = "/api/v1/services/aigc/multimodal-generation/generation"
        val base = config.connectionBase(chatBaseUrl).trimEnd('/')
        val endpoint = if (base.endsWith(path)) base else base.removeSuffix("/api/v1") + path
        val body = buildJsonObject {
            put("model", config.modelId)
            putJsonObject("input") {
                putJsonArray("messages") { addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        references.forEach { image -> addJsonObject { put("image", image.toDataUrl()) } }
                        addJsonObject { put("text", prompt) }
                    }
                } }
            }
            putJsonObject("parameters") { put("size", config.size.replace('x', '*')) }
        }
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer ${config.key(chatKey)}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return execute(request) { root ->
            val output = root["output"] as? JsonObject
            val choice = (output?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
            val message = choice?.get("message") as? JsonObject
            val parts = message?.get("content") as? JsonArray
            val image = parts?.firstNotNullOfOrNull { (it as? JsonObject)?.string("image") }
                ?: throw IOException(errorMessage(root) ?: "DashScope returned no image")
            if (image.startsWith("data:")) decodeImage(image, "image/png") else downloadImage(image)
        }
    }

    internal suspend fun generateGemini(
        config: ImageGenConfig, chatBaseUrl: String, chatKey: String, prompt: String,
        references: List<Attachment> = emptyList(),
    ): GeneratedImage {
        require(config.modelId.isNotBlank()) { "Choose a Gemini image model" }
        val base = config.connectionBase(chatBaseUrl).trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/openai")
        val model = config.modelId.trim().removePrefix("google/").removePrefix("models/")
        val images = references.filter { it.isImage && !it.remoteImage && it.data.isNotBlank() }
        val interactionsBase = if (base.toHttpUrlOrNull()?.host == "generativelanguage.googleapis.com") {
            base.removeSuffix("/v1beta").removeSuffix("/v1")
        } else base
        if (base.endsWith("/interactions") || usesGeminiInteractions(model) &&
            base.toHttpUrlOrNull()?.host == "generativelanguage.googleapis.com" && !base.endsWith(":generateContent")) {
            return generateGeminiInteraction(interactionsBase, model, config, chatKey, prompt, images)
        }
        val encodedModel = java.net.URLEncoder.encode(model, Charsets.UTF_8.name())
        val endpoint = when {
            base.contains(":generateContent") -> base
            base.endsWith("/v1beta") || base.endsWith("/v1") -> "$base/models/$encodedModel:generateContent"
            else -> "$base/v1beta/models/$encodedModel:generateContent"
        }
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                        images.forEach { image -> addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", ApiImage.legalMediaType(image.mime))
                                put("data", image.data)
                            }
                        } }
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add("TEXT"); add("IMAGE") })
                put("imageConfig", buildJsonObject {
                    put("aspectRatio", config.aspectRatio)
                    if (config.geminiImageSize != "1K") put("imageSize", config.geminiImageSize)
                })
            })
        }
        val request = Request.Builder().url(endpoint)
            .header("x-goog-api-key", config.key(chatKey))
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return execute(request) { root ->
            val candidates = root["candidates"] as? JsonArray ?: JsonArray(emptyList())
            val parts = candidates.mapNotNull { it as? JsonObject }
                .flatMap { (it["content"] as? JsonObject)?.get("parts") as? JsonArray ?: JsonArray(emptyList()) }
                .mapNotNull { it as? JsonObject }
            val image = parts.firstNotNullOfOrNull { part ->
                if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) return@firstNotNullOfOrNull null
                val data = (part["inlineData"] as? JsonObject) ?: (part["inline_data"] as? JsonObject)
                val encoded = data?.string("data") ?: return@firstNotNullOfOrNull null
                decodeImage(encoded, data.string("mimeType") ?: data.string("mime_type") ?: "image/png")
            }
            image ?: throw IOException(parts.firstNotNullOfOrNull { it.string("text") }
                ?: errorMessage(root) ?: "Gemini returned no image")
        }
    }

    private suspend fun generateGeminiInteraction(
        base: String, model: String, config: ImageGenConfig, chatKey: String, prompt: String,
        images: List<Attachment>,
    ): GeneratedImage {
        val endpoint = when {
            base.endsWith("/interactions") -> base
            base.endsWith("/v1beta") || base.endsWith("/v1") -> "$base/interactions"
            else -> "$base/v1beta/interactions"
        }
        val body = buildJsonObject {
            put("model", model)
            if (images.isEmpty()) put("input", prompt) else putJsonArray("input") {
                addJsonObject { put("type", "text"); put("text", prompt) }
                images.forEach { image -> addJsonObject {
                    put("type", "image")
                    put("mime_type", ApiImage.legalMediaType(image.mime))
                    put("data", image.data)
                } }
            }
            put("store", false)
            putJsonObject("response_format") {
                put("type", "image")
                put("aspect_ratio", config.aspectRatio)
                if (config.geminiImageSize != "1K") put("image_size", config.geminiImageSize)
            }
        }
        val request = Request.Builder().url(endpoint)
            .header("x-goog-api-key", config.key(chatKey))
            .header("Api-Revision", "2026-05-20")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return execute(request) { root ->
            val image = (root["steps"] as? JsonArray).orEmpty().asSequence()
                .filterIsInstance<JsonObject>().filter { it.string("type") == "model_output" }
                .flatMap { (it["content"] as? JsonArray).orEmpty().asSequence().filterIsInstance<JsonObject>() }
                .firstOrNull { it.string("type") == "image" && !it.string("data").isNullOrBlank() }
            val data = image?.string("data") ?: throw IOException(errorMessage(root) ?: "Gemini returned no image")
            decodeImage(data, image.string("mime_type") ?: "image/png")
        }
    }

    suspend fun generateWithResponses(
        baseUrl: String, apiKey: String, model: String, prompt: String,
        references: List<Attachment> = emptyList(), size: String? = null,
    ): GeneratedImage {
        require(prompt.isNotBlank()) { "Prompt is empty" }
        val endpoint = responsesUrl(baseUrl).toHttpUrlOrNull() ?: throw IOException("Invalid Responses URL")
        val body = buildJsonObject {
            put("model", model)
            if (references.isEmpty()) put("input", prompt) else putJsonArray("input") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject { put("type", "input_text"); put("text", prompt) }
                        references.forEach { image -> addJsonObject {
                            put("type", "input_image"); put("image_url", image.toDataUrl())
                        } }
                    }
                }
            }
            put("store", false)
            putJsonArray("tools") { addJsonObject {
                put("type", "image_generation")
                if (size != null) put("size", size)
                if (endpoint.isHttps && endpoint.host == "api.openai.com" && endpoint.port == 443) {
                    put("model", OPENAI_DEFAULT_IMAGE_MODEL)
                }
            } }
            put("tool_choice", buildJsonObject { put("type", "image_generation") })
        }
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return execute(request) { root ->
            val output = root["output"] as? JsonArray ?: JsonArray(emptyList())
            val call = output.mapNotNull { it as? JsonObject }.firstOrNull { it.string("type") == "image_generation_call" }
                ?: throw IOException(errorMessage(root) ?: "Model returned no generated image")
            val result = call.string("result") ?: throw IOException("Generated image result is empty")
            decodeImage(result, "image/png")
        }
    }

    private suspend fun <T> execute(request: Request, parse: suspend (JsonObject) -> T): T = withContext(Dispatchers.IO) {
        val root = retryImageTransport { readImageResponse(request) }
        parse(root)
    }

    private suspend fun readImageResponse(request: Request): JsonObject =
        cancellableRequest(request) { response ->
            val source = response.body.source()
            val limit = if (response.isSuccessful) MaxImageBytes * 4L / 3 + 65_536 else 65_536L
            if (source.request(limit + 1) && response.isSuccessful) throw IOException("Image response is too large")
            val text = source.readUtf8(minOf(source.buffer.size, limit))
            if (!response.isSuccessful) {
                val detail = runCatching { json.parseToJsonElement(text).jsonObject.let(::errorMessage) }.getOrNull()
                throw IOException(detail ?: "Image request failed (HTTP ${response.code})")
            }
            json.parseToJsonElement(text) as? JsonObject ?: throw IOException("Invalid image response")
        }

    private suspend fun downloadImage(url: String): GeneratedImage = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull()
            ?: throw IOException("Invalid image URL")
        if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw IOException("Image URL must use HTTPS")
        }
        val request = Request.Builder().url(parsed).get().build()
        retryImageTransport { cancellableRequest(request, downloads) { response ->
            if (!response.isSuccessful) throw IOException("Image download failed (HTTP ${response.code})")
            val body = response.body
            if (body.contentLength() > MaxImageBytes) throw IOException("Generated image is too large")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MaxImageBytes) throw IOException("Generated image is too large")
                    output.write(buffer, 0, count)
                }
            }
            GeneratedImage(output.toByteArray(), body.contentType()?.let { "image/${it.subtype}" } ?: "image/png")
        } }
    }

    private suspend fun <T> cancellableRequest(
        request: Request,
        requestClient: OkHttpClient = client,
        action: suspend (Response) -> T,
    ): T {
        val call = requestClient.newCall(request)
        val cancellation = call.cancelWithCoroutine()
        try {
            return call.execute().use { action(it) }
        } finally {
            cancellation.cancel()
            currentCoroutineContext().ensureActive()
        }
    }

    private val downloads by lazy {
        client.newBuilder().proxy(java.net.Proxy.NO_PROXY).dns { host ->
            okhttp3.Dns.SYSTEM.lookup(host).also { addresses ->
                if (addresses.any { address ->
                    val bytes = address.address
                    val first = bytes.first().toInt() and 255
                    val second = bytes[1].toInt() and 255
                    address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                        address.isSiteLocalAddress || address.isMulticastAddress ||
                        (bytes.size == 16 && first in 252..253) ||
                        (bytes.size == 4 && (first == 0 || first == 100 && second in 64..127))
                }) throw java.net.UnknownHostException("Image URL must resolve to a public address")
            }
        }.build()
    }

    private fun decodeImage(value: String, mime: String): GeneratedImage {
        val encoded = value.substringAfter(',', value)
        if (encoded.length > MaxImageBytes * 4L / 3 + 4) throw IOException("Generated image is too large")
        val bytes = try { Base64.getDecoder().decode(encoded) }
        catch (_: IllegalArgumentException) { throw IOException("Invalid base64 image") }
        if (bytes.isEmpty() || bytes.size > MaxImageBytes) throw IOException("Generated image is empty or too large")
        return GeneratedImage(bytes, mime.takeIf { it in setOf("image/png", "image/jpeg", "image/webp") } ?: "image/png")
    }

    private fun errorMessage(root: JsonObject): String? =
        (root["error"] as? JsonObject)?.string("message") ?: root.string("message")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object { private const val MaxImageBytes = 20 * 1024 * 1024 }
}

internal fun imageModelListingRequest(config: ImageGenConfig, chatBaseUrl: String, chatKey: String): Request {
    val base = config.connectionBase(chatBaseUrl).trimEnd('/')
        .removeSuffix("/chat/completions").removeSuffix("/responses")
    val endpoint = when (config.adapter) {
        "GEMINI" -> {
            val root = base.substringBefore("/models/").removeSuffix("/models")
                .removeSuffix("/openai").removeSuffix("/interactions")
            if (root.endsWith("/v1") || root.endsWith("/v1beta")) "$root/models" else "$root/v1beta/models"
        }
        "DASHSCOPE" -> {
            val root = base.substringBefore("/api/v1").substringBefore("/compatible-mode/v1")
            "$root/compatible-mode/v1/models"
        }
        else -> {
            val root = base.removeSuffix("/images/generations").removeSuffix("/models")
            if (root.endsWith("/v1")) "$root/models" else "$root/v1/models"
        }
    }
    val key = config.key(chatKey)
    val url = endpoint.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid image model listing URL")
    val listingUrl = if (config.adapter == "GEMINI" && url.host == "generativelanguage.googleapis.com") {
        url.newBuilder().addQueryParameter("pageSize", "500").build()
    } else url
    return Request.Builder().url(listingUrl).get().header("Accept", "application/json").apply {
        if (config.adapter == "GEMINI") header("x-goog-api-key", key)
        else header("Authorization", "Bearer $key")
    }.build()
}

/** Reopen a broken connection once. Provider responses and cancellations are terminal. */
internal suspend fun <T> retryImageTransport(block: suspend () -> T): T {
    try {
        return block()
    } catch (error: IOException) {
        val droppedConnection = error is SocketException || error is EOFException ||
            error is SocketTimeoutException || error is ProtocolException && error.message?.contains("unexpected end", ignoreCase = true) == true ||
            error.cause is EOFException
        if (!droppedConnection) throw error
        currentCoroutineContext().ensureActive()
        delay(350)
        return block()
    }
}
