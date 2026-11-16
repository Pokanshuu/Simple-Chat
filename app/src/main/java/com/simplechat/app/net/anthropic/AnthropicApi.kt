package com.simplechat.app.net.anthropic

import com.simplechat.app.net.cancelWithCoroutine
import com.simplechat.app.net.readErrorText

import com.simplechat.app.net.ChatStreamLogTag
import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.StreamMaxAttempts
import com.simplechat.app.net.StreamRetryDelayMs
import com.simplechat.app.net.httpErrorText
import com.simplechat.app.net.networkText
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
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
        val body = StreamingAnthropicRequestBody(json, request)

        val builder = Request.Builder().url(url).post(body)
        anthropicHeaders(apiKey, extraHeaders).forEach { (name, value) -> builder.header(name, value) }
        val httpRequest = builder.build()

        var attempt = 0
        var emittedAny = false
        while (true) {
            attempt++
            val call = client.newCall(httpRequest)
            val handle = call.cancelWithCoroutine()
            var retryHttp = false
            try {
                call.execute().use { response ->
                    val source = response.body?.source()
                    if (!response.isSuccessful || source == null) {
                        val raw = runCatching { response.body.readErrorText() }.getOrNull()
                        val serverMessage = parser.parseErrorMessage(raw)
                        android.util.Log.w(ChatStreamLogTag, "HTTP ${response.code}")
                        /*
                         * 429 / 5xx 且**一个字都还没发出去** → 重发一次。
                         * 与 OpenAI 通道同一条规则：已经收到内容再重发会出现两份回答。
                         */
                        if (!emittedAny && attempt < StreamMaxAttempts && isRetryableStatus(response.code)) {
                            android.util.Log.w(ChatStreamLogTag, "HTTP ${response.code}; retrying")
                            retryHttp = true
                            return@use
                        }
                        emit(
                            StreamEvent.Error(
                                message = httpErrorText(response.code, serverMessage),
                                code = response.code,
                                retryable = isRetryableStatus(response.code),
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
                                    result.events.forEach {
                                        emit(it)
                                        emittedAny = true
                                    }

                                AnthropicStreamParser.Result.Done -> finished = true
                                AnthropicStreamParser.Result.Ignore -> Unit
                            }
                        }
                    }

                    emit(StreamEvent.Done)
                    emittedAny = true
                }
                if (retryHttp) {
                    delay(StreamRetryDelayMs)
                    continue
                }
                return@flow
            } catch (e: IOException) {
                // 用户主动取消造成的 IOException 不应报告为错误
                if (!currentCoroutineContext().isActive) return@flow
                // 一个字都还没发出去 → 重发是安全的（与 SseClient 同一条规则，见那里的说明）
                if (!emittedAny && attempt < StreamMaxAttempts) {
                    android.util.Log.w(ChatStreamLogTag, "stream reset before any event; retrying", e)
                    delay(StreamRetryDelayMs)
                    continue
                }
                android.util.Log.w(ChatStreamLogTag, "stream interrupted", e)
                emit(StreamEvent.Error(e.networkText(), retryable = true))
                return@flow
            } finally {
                handle.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** Anthropic 协议版本。原版会校验这个头；DeepSeek 的兼容端点也收。 */
internal const val AnthropicVersion = "2023-06-01"

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal class StreamingAnthropicRequestBody(
    private val json: Json,
    private val request: AnthropicRequest,
) : okhttp3.RequestBody() {
    override fun contentType() = "application/json; charset=utf-8".toMediaType()
    override fun isOneShot() = true
    override fun writeTo(sink: okio.BufferedSink) {
        json.encodeToStream(AnthropicRequest.serializer(), request, sink.outputStream())
    }
}

/**
 * 请求头。
 *
 * ### 为什么两种鉴权都发
 *
 * - 原版 Anthropic（以及多数 Claude 兼容网关）只认 **`x-api-key`**；
 * - DeepSeek 的 `/anthropic` 兼容端点两种都收（官方 demo 用的是 `x-api-key`），
 *   而部分第三方中转只认 `Authorization: Bearer`。
 *
 * 两个都带上是最省事的兼容做法：两边都不会因为"少一个头"而 401。
 *
 * `anthropic-version` 是原版**强制**要求的（缺了直接 400），DeepSeek 也会忽略它。
 *
 * 纯函数，有单测（`AnthropicApiTest`）。
 */
internal fun anthropicHeaders(
    apiKey: String,
    extraHeaders: Map<String, String> = emptyMap(),
): Map<String, String> = buildMap {
    put("Content-Type", "application/json")
    put("Accept", "text/event-stream")
    put("anthropic-version", AnthropicVersion)
    if (apiKey.isNotBlank()) {
        put("Authorization", "Bearer $apiKey")
        put("x-api-key", apiKey)
    }
    putAll(extraHeaders)
}

/**
 * 哪些 HTTP 状态值得**原样重发一次**（只在"一个字都还没收到"时）。
 *
 * 429 = 限流；5xx 里 500/502/503/504 是网关/上游暂时不可用；
 * 529 是 Anthropic 特有的 `overloaded_error`。
 */
internal fun isRetryableStatus(code: Int): Boolean =
    code == 429 || code == 500 || code == 502 || code == 503 || code == 504 || code == 529

/**
 * 拼装 `/messages` 地址。
 *
 * 用户填的 Base URL 什么形态都有，而且**两种目标的路径不同**：
 *
 * | 目标 | 用户填的 | 实际请求 |
 * |---|---|---|
 * | DeepSeek 官方 | `https://api.deepseek.com` | `…/anthropic/v1/messages` |
 * | DeepSeek 官方 | `https://api.deepseek.com/anthropic` | `…/anthropic/v1/messages` |
 * | DeepSeek 官方 | `https://api.deepseek.com/anthropic/v1` | `…/anthropic/v1/messages` |
 * | 原版 / 兼容网关 | `https://gw.example.com` | `https://gw.example.com/v1/messages` |
 * | 原版 / 兼容网关 | `https://gw.example.com/v1` | `…/v1/messages` |
 * | 任意 | `https://gw.example.com/v1/messages` | 原样 |
 *
 * 判据是 **Base URL 里有没有 `api.deepseek.com`**：只有官方域把扩展协议挂在
 * `/anthropic` 前缀下；其余 Anthropic 兼容端点走标准 `/v1/messages`。
 * 纯函数，有单测：这一层错了只表现为 404，从界面上看不出是哪一环。
 */
fun anthropicMessagesUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return base
    return when {
        base.endsWith("/messages") -> base
        base.endsWith("/anthropic/v1") -> "$base/messages"
        base.endsWith("/anthropic") -> "$base/v1/messages"
        // DeepSeek 官方：扩展协议挂在 /anthropic 前缀下
        base.contains("api.deepseek.com") -> "$base/anthropic/v1/messages"
        // 其余 Anthropic 兼容端点（自建网关 / Claude 中转）：标准路径
        base.endsWith("/v1") -> "$base/messages"
        else -> "$base/v1/messages"
    }
}
