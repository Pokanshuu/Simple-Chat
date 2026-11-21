package com.simplechat.app.net

import com.simplechat.app.data.ImageGenConfig
import com.simplechat.app.data.OPENAI_DEFAULT_IMAGE_MODEL
import com.simplechat.app.data.usesGeminiInteractions
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Response
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class GeneratedImage(val bytes: ByteArray, val mime: String)

class ImageGenerationApi(
    private val client: OkHttpClient = ChatApi.defaultClient().newBuilder()
        .readTimeout(180, TimeUnit.SECONDS).callTimeout(4, TimeUnit.MINUTES)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generateIndependent(config: ImageGenConfig, chatBaseUrl: String, chatKey: String, prompt: String): GeneratedImage {
        require(config.enabled) { "Independent image API is disabled" }
        require(prompt.isNotBlank()) { "Prompt is empty" }
        require(config.modelId.isNotBlank()) { "Image model is missing" }
        if (config.adapter == "GEMINI") return generateGemini(config, chatBaseUrl, chatKey, prompt)
        if (config.adapter == "DASHSCOPE") return generateDashScope(config, chatBaseUrl, chatKey, prompt)
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
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer ${config.key(chatKey)}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return execute(request) { root ->
            val item = (root["data"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: throw IOException(errorMessage(root) ?: "Image API returned no image")
            val encoded = item.string("b64_json")
            if (encoded != null) decodeImage(encoded, item.string("mime_type") ?: "image/png")
            else item.string("url")?.let { downloadImage(it) }
                ?: throw IOException("Image API returned no image data")
        }
    }

    private suspend fun generateDashScope(config: ImageGenConfig, chatBaseUrl: String, chatKey: String, prompt: String): GeneratedImage {
        val path = "/api/v1/services/aigc/multimodal-generation/generation"
        val base = config.connectionBase(chatBaseUrl).trimEnd('/')
        val endpoint = if (base.endsWith(path)) base else base.removeSuffix("/api/v1") + path
        val body = buildJsonObject {
            put("model", config.modelId)
            putJsonObject("input") {
                putJsonArray("messages") { addJsonObject {
                    put("role", "user")
                    putJsonArray("content") { addJsonObject { put("text", prompt) } }
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

    internal suspend fun generateGemini(config: ImageGenConfig, chatBaseUrl: String, chatKey: String, prompt: String): GeneratedImage {
        require(config.modelId.isNotBlank()) { "Choose a Gemini image model" }
        val base = config.connectionBase(chatBaseUrl).trimEnd('/').removeSuffix("/chat/completions").removeSuffix("/openai")
        val model = config.modelId.trim().removePrefix("models/")
        val interactionsBase = if (base.toHttpUrlOrNull()?.host == "generativelanguage.googleapis.com") {
            base.removeSuffix("/v1beta").removeSuffix("/v1")
        } else base
        if (base.endsWith("/interactions") || usesGeminiInteractions(model) &&
            base.toHttpUrlOrNull()?.host == "generativelanguage.googleapis.com" && !base.endsWith(":generateContent")) {
            return generateGeminiInteraction(interactionsBase, model, config, chatKey, prompt)
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
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
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

    private suspend fun generateGeminiInteraction(base: String, model: String, config: ImageGenConfig, chatKey: String, prompt: String): GeneratedImage {
        val endpoint = when {
            base.endsWith("/interactions") -> base
            base.endsWith("/v1beta") || base.endsWith("/v1") -> "$base/interactions"
            else -> "$base/v1beta/interactions"
        }
        val body = buildJsonObject {
            put("model", model)
            put("input", prompt)
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

    suspend fun generateWithResponses(baseUrl: String, apiKey: String, model: String, prompt: String): GeneratedImage {
        require(prompt.isNotBlank()) { "Prompt is empty" }
        val endpoint = responsesUrl(baseUrl).toHttpUrlOrNull() ?: throw IOException("Invalid Responses URL")
        val body = buildJsonObject {
            put("model", model)
            put("input", prompt)
            put("store", false)
            putJsonArray("tools") { addJsonObject {
                put("type", "image_generation")
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
        cancellableRequest(request) { response ->
            val source = response.body.source()
            val limit = if (response.isSuccessful) MaxImageBytes * 4L / 3 + 65_536 else 65_536L
            if (source.request(limit + 1) && response.isSuccessful) throw IOException("Image response is too large")
            val text = source.readUtf8(minOf(source.buffer.size, limit))
            if (!response.isSuccessful) {
                val detail = runCatching { json.parseToJsonElement(text).jsonObject.let(::errorMessage) }.getOrNull()
                throw IOException(detail ?: "Image request failed (HTTP ${response.code})")
            }
            val root = json.parseToJsonElement(text) as? JsonObject ?: throw IOException("Invalid image response")
            parse(root)
        }
    }

    private suspend fun downloadImage(url: String): GeneratedImage = withContext(Dispatchers.IO) {
        val parsed = url.toHttpUrlOrNull()
            ?: throw IOException("Invalid image URL")
        if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw IOException("Image URL must use HTTPS")
        }
        val request = Request.Builder().url(parsed).get().build()
        cancellableRequest(request) { response ->
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
        }
    }

    private suspend fun <T> cancellableRequest(request: Request, action: suspend (Response) -> T): T {
        val call = (if (request.method == "GET") downloads else client).newCall(request)
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
