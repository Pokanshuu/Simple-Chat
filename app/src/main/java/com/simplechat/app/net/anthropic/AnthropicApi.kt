package com.simplechat.app.net.anthropic

import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.net.StreamEvent
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
 * Anthropic 兼容通道的流式客户端（`POST /anthropic/v1/messages`）。
 *
 * 与 [com.simplechat.app.net.SseClient] 刻意**不合并**成"一个带分支的客户端"：
 *
 * - 结束信号不同（`message_stop` vs `[DONE]`），合并会让两套判断缠在一起；
 * - 事件模型不同（块级 vs 增量），解析器本来就是两份；
 * - 两条通道要能**各自独立演进** —— 官方扩展先落到这条上，坏也只坏这条。
 *
 * 相同的是**取消语义**：同样在协程 Job 上挂 `call.cancel()`，
 * 点「停止」瞬间断开 socket，阻塞中的 `readUtf8Line()` 抛 IOException 退出（§5.5）。
 */
class AnthropicApi(
    private val client: OkHttpClient,
    private val json: Json,
) {

    private val parser = AnthropicStreamParser(json)

    fun streamMessage(
        url: String,
        apiKey: String,
        request: AnthropicRequest,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Flow<StreamEvent> = flow {
        val payload = json.encodeToString(AnthropicRequest.serializer(), request)
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
                            is AnthropicStreamParser.Result.Events ->
                                result.events.forEach { emit(it) }
                            AnthropicStreamParser.Result.Done -> finished = true
                            AnthropicStreamParser.Result.Ignore -> Unit
                        }
                    }
                }

                emit(StreamEvent.Done)
            }
        } catch (e: IOException) {
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

/**
 * 拼装 `/messages` 地址。
 *
 * 用户填的 Base URL 什么形态都有，这里按"最具体的先试"逐级退化：
 *
 * | 用户填的 | 实际请求 |
 * |---|---|
 * | `https://api.deepseek.com` | `https://api.deepseek.com/anthropic/v1/messages` |
 * | `https://api.deepseek.com/anthropic` | `.../anthropic/v1/messages` |
 * | `https://api.deepseek.com/anthropic/v1` | `.../anthropic/v1/messages` |
 * | `https://gw.example.com/messages` | 原样（已经是最具体的） |
 *
 * 纯函数，有单测：这一层错了只会表现为 404，从界面上看不出是哪一环。
 */
fun anthropicMessagesUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    return when {
        base.endsWith("/messages") -> base
        base.endsWith("/anthropic/v1") -> "$base/messages"
        base.endsWith("/anthropic") -> "$base/v1/messages"
        else -> "$base/anthropic/v1/messages"
    }
}
