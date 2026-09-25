package com.simplechat.app.net

import com.simplechat.app.R
import com.simplechat.app.data.Res
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI 兼容的 SSE 流式客户端。
 *
 * 设计要点：
 * - **不用 Retrofit**：直接 OkHttp + 手写行解析，少一层抽象、少一份反射。
 * - **取消瞬时生效**：在协程 Job 上挂 `invokeOnCompletion { call.cancel() }`，
 *   取消时立刻断开 socket，阻塞中的 `readUtf8Line()` 会抛 IOException 而退出。
 *   这是「点停止立刻停」的关键（见设计文档 §5.5）。
 * - **解析逻辑外置**：行解析在 [SseLineParser] 里，纯函数、可单测。
 */
class SseClient(
    private val client: OkHttpClient,
    private val json: Json,
) {

    private val parser = SseLineParser(json)

    fun stream(
        url: String,
        apiKey: String,
        request: ChatRequestDto,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Flow<StreamEvent> = flow {
        val payload = json.encodeToString(ChatRequestDto.serializer(), request)
        val body = payload.toRequestBody(JSON_MEDIA_TYPE)

        val builder = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }

        val call = client.newCall(builder.build())
        val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                val source = response.body?.source()
                if (!response.isSuccessful || source == null) {
                    val raw = runCatching { response.body?.string() }.getOrNull()
                    emit(
                        StreamEvent.Error(
                            message = parser.parseErrorMessage(raw) ?: "HTTP ${response.code}",
                            code = response.code,
                            retryable = response.code == 429 || response.code >= 500,
                        ),
                    )
                    return@use
                }

                var finished = false
                while (!finished) {
                    currentCoroutineContext().ensureActive()

                    val line = source.readUtf8Line()
                    if (line == null) {
                        finished = true
                    } else {
                        when (val result = parser.parse(line)) {
                            is SseLineParser.Result.Events -> result.events.forEach { emit(it) }
                            SseLineParser.Result.Done -> finished = true
                            SseLineParser.Result.Ignore -> Unit
                        }
                    }
                }

                emit(StreamEvent.Done)
            }
        } catch (e: IOException) {
            // 用户主动取消造成的 IOException 不应报告为错误
            if (currentCoroutineContext().isActive) {
                emit(StreamEvent.Error(e.message ?: Res.get(R.string.error_network), retryable = true))
            }
        } finally {
            handle?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
