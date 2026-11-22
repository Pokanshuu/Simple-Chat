package com.simplechat.app.data.import

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.import.DeepSeekHistory.LightMessage
import com.simplechat.app.data.import.DeepSeekHistory.SessionPlan
import com.simplechat.app.data.import.DeepSeekHistory.SessionScan
import com.simplechat.app.net.ChatApi
import java.io.File
import java.io.Reader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Reads a public snapshot from the same endpoint used by the DeepSeek share page. */
internal object DeepSeekShare {
    private const val Host = "chat.deepseek.com"
    private const val MaxBytes = 1024L * 1024 * 1024
    private val shareId = Regex("[A-Za-z0-9_-]{1,128}")
    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    fun idFromLink(text: String): String? {
        val url = text.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.host != Host || url.port != 443 ||
            url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty()
        ) return null
        val parts = url.pathSegments.dropLastWhile { it.isEmpty() }
        return parts.getOrNull(1)?.takeIf { parts.size == 2 && parts[0] == "share" && shareId.matches(it) }
    }

    internal fun contentRequest(id: String): Request {
        require(shareId.matches(id))
        return Request.Builder()
            .url("https://$Host/api/v0/share/content?share_id=$id")
            .header("Accept", "application/json")
            .header("User-Agent", ChatApi.USER_AGENT)
            .build()
    }

    internal data class Preview(
        val title: String,
        val messages: Int,
        val attachments: Int,
        val excerpt: String,
        val stream: ShareStream,
    )

    suspend fun download(id: String, target: File, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        client.newCall(contentRequest(id)).execute().use { response ->
            when (response.code) {
                401, 403, 429 -> throw AppTextException(R.string.deepseek_share_blocked)
                404, 410 -> throw AppTextException(R.string.deepseek_share_unavailable)
            }
            if (!response.isSuccessful) throw AppTextException(R.string.deepseek_share_http_error, response.code)
            val body = response.body
            if (body.contentLength() > MaxBytes) {
                throw AppTextException(R.string.deepseek_share_too_large)
            }
            body.byteStream().use { input -> target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MaxBytes) throw AppTextException(R.string.deepseek_share_too_large)
                    output.write(buffer, 0, count)
                    if (body.contentLength() > 0) onProgress(total.toFloat() / body.contentLength())
                }
                output.flush()
            } }
        }
    }

    suspend fun preview(file: File, id: String, fallbackTitle: String): Preview {
        var title: String? = null
        var excerpt = ""
        var firstPrompt = ""
        var count = 0
        var attachments = 0
        val messages = mutableListOf<LightMessage>()
        try {
            walk(file.reader(Charsets.UTF_8), { title = it }) { stream ->
                val (light, files) = readLight(stream)
                if (light != null) { count++; messages += light }
                if (firstPrompt.isBlank() && light?.role == "USER") firstPrompt = light.requestHead.orEmpty().trim()
                if (excerpt.isBlank()) excerpt = light?.requestHead.orEmpty().trim().take(160)
                attachments += files
            }
        } catch (error: AppTextException) { throw error }
        catch (_: Exception) { throw AppTextException(R.string.deepseek_share_bad_format) }
        if (count == 0) throw AppTextException(R.string.deepseek_share_no_text)
        val resolvedTitle = title?.takeIf { it.isNotBlank() && !it.equals("Shared Conversation", true) }
            ?: firstPrompt.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
            ?: fallbackTitle
        val jsonTitle = JsonPrimitive(resolvedTitle).toString()
        val plan = DeepSeekHistory.planSession(
            SessionScan(mapOf("title" to jsonTitle), messages), "share-$id", ExternalBackup.ImportNaming(),
        ) ?: throw AppTextException(R.string.deepseek_share_no_text)
        return Preview(resolvedTitle, count, attachments, excerpt, ShareStream(file, plan, id, attachments))
    }

    internal class ShareStream(
        private val file: File,
        private val plan: SessionPlan,
        private val id: String,
        val attachments: Int,
    ) : ImportSessionSource {
        var onProgress: (Float) -> Unit = {}
        override val expected = 1
        override val indexVerified = true
        override suspend fun forEachSession(block: suspend (ImportSession) -> Unit) {
            block(ImportSession(plan.header, expectedMessages = plan.kept.size) { sink ->
                var processed = 0
                val total = plan.kept.size.coerceAtLeast(1)
                walk(file.reader(Charsets.UTF_8), {}, { stream ->
                    currentCoroutineContext().ensureActive()
                    DeepSeekHistory.toBackupMessage(readDeepSeekMessage(stream), plan, "share-$id", emptyList())?.let { sink(it) }
                    processed++
                    if (processed % 50 == 0 || processed == total) {
                        onProgress(0.95f * processed / total)
                    }
                })
            })
        }
    }

    private fun readLight(stream: JsonStream): Pair<LightMessage?, Int> {
        var number: Long? = null
        var parent: Long? = null
        var role: String? = null
        var request: String? = null
        var files = 0
        stream.beginObject()
        while (stream.hasNextField()) when (stream.fieldName()) {
            "message_id", "id" -> number = stream.textValue()?.toLongOrNull()
            "parent_id" -> parent = stream.textValue()?.toLongOrNull()
            "role" -> role = stream.textValue()
            "content" -> request = stream.textValue(256)
            "fragments" -> {
                if (stream.valueKind() == 'n') { stream.skipValue(); continue }
                stream.beginArray()
                while (stream.hasNextArrayElement()) {
                    var type: String? = null
                    var content: String? = null
                    stream.beginObject()
                    while (stream.hasNextField()) when (stream.fieldName()) {
                        "type" -> type = stream.textValue()
                        "content" -> content = stream.textValue(256)
                        "files" -> {
                            if (stream.valueKind() == 'n') { stream.skipValue(); continue }
                            stream.beginArray()
                            while (stream.hasNextArrayElement()) { stream.skipValue(); files++ }
                        }
                        else -> stream.skipValue()
                    }
                    if (type == "REQUEST" && request.isNullOrBlank()) request = content
                }
            }
            else -> stream.skipValue()
        }
        val id = number ?: return null to files
        return LightMessage(id, parent, role?.uppercase(), request) to files
    }

    private suspend fun walk(reader: Reader, onTitle: (String) -> Unit, onMessage: suspend (JsonStream) -> Unit) {
        reader.use {
            val stream = JsonStream(it)
            stream.beginObject()
            while (stream.hasNextField()) when (stream.fieldName()) {
                "code" -> if (stream.textValue()?.toIntOrNull() != 0) throw AppTextException(R.string.deepseek_share_unavailable)
                "data" -> {
                    stream.beginObject()
                    while (stream.hasNextField()) when (stream.fieldName()) {
                        "biz_code" -> if (stream.textValue()?.toIntOrNull() != 0) throw AppTextException(R.string.deepseek_share_unavailable)
                        "biz_data" -> {
                            stream.beginObject()
                            while (stream.hasNextField()) when (stream.fieldName()) {
                                "title" -> onTitle(stream.textValue().orEmpty())
                                "messages" -> {
                                    stream.beginArray()
                                    while (stream.hasNextArrayElement()) onMessage(stream)
                                }
                                else -> stream.skipValue()
                            }
                        }
                        else -> stream.skipValue()
                    }
                }
                else -> stream.skipValue()
            }
        }
    }

}
