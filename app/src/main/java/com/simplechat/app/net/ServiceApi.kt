package com.simplechat.app.net

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Raw DeepSeek Search/Fetch and OpenAI-shaped Files, also usable with compatible gateways. */
class ServiceApi(private val client: OkHttpClient = ChatApi.defaultClient().newBuilder()
    .readTimeout(240, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
    .callTimeout(12, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()) {
    class HttpFailure(val status: Int, val detail: String?) : java.io.IOException("Service HTTP $status")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        val cancellation = call.cancelWithCoroutine()
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body.readErrorText()
                    val detail = runCatching { json.parseToJsonElement(raw.orEmpty()).jsonObject["error"]
                        ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
                    throw HttpFailure(response.code, detail)
                }
                val body = response.body
                val text = body.readErrorText(4L * 1024 * 1024 + 1)
                if (text.toByteArray().size > 4 * 1024 * 1024) throw AppTextException(R.string.service_bad_response)
                runCatching { json.parseToJsonElement(text).jsonObject }
                    .getOrElse { throw AppTextException(R.string.service_bad_response) }
            }
        } finally { cancellation.cancel(); currentCoroutineContext().ensureActive() }
    }

    suspend fun search(url: String, key: String, query: String, count: Int): JsonArray {
        val result = post(url, key, searchBody(query, count))
        return result["results"] as? JsonArray ?: throw AppTextException(R.string.service_bad_response)
    }

    suspend fun fetch(url: String, key: String, pageUrl: String): JsonObject = post(url, key, buildJsonObject {
        put("url", pageUrl)
    })

    private suspend fun post(url: String, key: String, body: JsonObject) = execute(
        Request.Builder().url(url).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())

    suspend fun upload(url: String, key: String, file: File, name: String, mime: String, purpose: String): String {
        val body = uploadBody(file, name, mime, purpose)
        val result = execute(Request.Builder().url(url).header("Authorization", "Bearer $key").post(body).build())
        return result["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw AppTextException(R.string.service_bad_response)
    }

    companion object {
        fun searchBody(query: String, count: Int): JsonObject {
            require(query.isNotBlank() && query.codePointCount(0, query.length) <= 128 && count in 1..50)
            return buildJsonObject { put("query", query); put("count", count); put("need_content", true) }
        }

        fun uploadBody(file: File, name: String, mime: String, purpose: String): MultipartBody =
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("purpose", purpose)
                .addFormDataPart("file", name, file.asRequestBody(mime.toMediaType())).build()

        fun query(text: String): String {
            val trimmed = text.trim()
            return trimmed.substring(0, trimmed.offsetByCodePoints(0, minOf(128, trimmed.codePointCount(0, trimmed.length))))
        }

        fun purpose(name: String, configured: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf", "doc", "docx", "ppt", "pptx", "bmp", "tif", "tiff" ->
                if (configured == "rich_layout_recognition") configured else "text_recognition"
            else -> "user_data"
        }
    }
}
