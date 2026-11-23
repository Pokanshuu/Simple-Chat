package com.simplechat.app.net.anthropic

import com.simplechat.app.net.cancelWithCoroutine
import com.simplechat.app.net.readErrorText
import com.simplechat.app.net.LargeRequestBytes
import com.simplechat.app.net.StreamingRequestBody
import com.simplechat.app.net.estimatedInputTokens
import com.simplechat.app.net.validateRequestBudget

import com.simplechat.app.net.ChatStreamLogTag
import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.StreamMaxAttempts
import com.simplechat.app.net.StreamRetryDelayMs
import com.simplechat.app.net.httpErrorText
import com.simplechat.app.net.isEmptyMessagesRejection
import com.simplechat.app.net.networkText
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

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

    fun streamMessage(
        url: String,
        apiKey: String,
        request: AnthropicRequest,
        extraHeaders: Map<String, String> = emptyMap(),
        contextWindow: Int? = null,
        retryBeforeContent: Boolean = true,
    ): Flow<StreamEvent> = flow {
        val parser = AnthropicStreamParser(json, request.tools.any { it.type == null })
        validateRequestBudget(estimatedInputTokens(request), request.maxTokens, contextWindow)
        val nativeVersion = claudeVersionFor(url)
        val wireRequest = if (nativeVersion != null) request.copy(temperature = null, topP = null) else request
        val body = StreamingAnthropicRequestBody(json, wireRequest)
        val largeRequest = body.contentLength() >= LargeRequestBytes

        val builder = Request.Builder().url(url).post(body)
        val versionHeaders = nativeVersion?.let { mapOf("anthropic-version" to it) }.orEmpty()
        anthropicHeaders(apiKey, versionHeaders + extraHeaders).forEach { (name, value) -> builder.header(name, value) }
        val httpRequest = builder.build()

        fun logEmptyMessagesRejection(message: String?) {
            if (!isEmptyMessagesRejection(message)) return
            android.util.Log.w(ChatStreamLogTag, "Messages rejected: count=${request.messages.size} " +
                "users=${request.messages.count { it.role == AnthropicMessageParam.RoleUser }} " +
                "firstRole=${request.messages.firstOrNull()?.role} systemChars=${request.system.length} " +
                "requestBytes=${body.contentLength()}")
        }

        var attempt = 0
        var emittedAny = false
        while (true) {
            attempt++
            val call = client.newCall(httpRequest)
            val handle = call.cancelWithCoroutine()
            var receivedHeaders = false
            var retryHttp = false
            try {
                call.execute().use { response ->
                    receivedHeaders = true
                    val source = response.body?.source()
                    if (!response.isSuccessful || source == null) {
                        val raw = runCatching { response.body.readErrorText() }.getOrNull()
                        val serverMessage = parser.parseErrorMessage(raw)
                        logEmptyMessagesRejection(serverMessage)
                        android.util.Log.w(ChatStreamLogTag, "HTTP ${response.code}")
                        /*
                         * 429 / 5xx 且**一个字都还没发出去** → 重发一次。
                         * 与 OpenAI 通道同一条规则：已经收到内容再重发会出现两份回答。
                         */
                        if (retryBeforeContent && !emittedAny && attempt < StreamMaxAttempts && isRetryableStatus(response.code)) {
                            android.util.Log.w(ChatStreamLogTag, "HTTP ${response.code}; retrying")
                            retryHttp = true
                            return@use
                        }
                        emit(
                            StreamEvent.Error(
                                message = httpErrorText(response.code, serverMessage, largeRequest),
                                code = response.code,
                                retryable = isRetryableStatus(response.code),
                                rawMessage = serverMessage,
                            ),
                        )
                        return@use
                    }

                    var finished = false
                    var receivedStop = false
                    while (!finished) {
                        currentCoroutineContext().ensureActive()

                        val line = source.readUtf8Line()
                        if (line == null) {
                            if (!receivedStop) throw java.io.EOFException("Stream ended before completion")
                            finished = true
                        } else {
                            when (val result = parser.parse(line)) {
                                is AnthropicStreamParser.Result.Events ->
                                    result.events.forEach {
                                        if (it is StreamEvent.Error) logEmptyMessagesRejection(it.message)
                                        emit(it)
                                        emittedAny = true
                                        if (it is StreamEvent.Error) {
                                            receivedStop = true
                                            finished = true
                                        } else if (it is StreamEvent.Stopped) {
                                            receivedStop = true
                                        }
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
                if (retryBeforeContent && !emittedAny && attempt < StreamMaxAttempts && body.canReplayOnDisconnect()) {
                    android.util.Log.w(ChatStreamLogTag, "stream reset before any event; retrying", e)
                    delay(StreamRetryDelayMs)
                    continue
                }
                android.util.Log.w(ChatStreamLogTag, "stream interrupted", e)
                emit(StreamEvent.Error(e.networkText(largeRequest && !emittedAny, receivedHeaders), retryable = true))
                return@flow
            } finally {
                handle.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

}

/** Anthropic 协议版本。原版会校验这个头；DeepSeek 的兼容端点也收。 */
internal const val AnthropicVersion = "2023-06-01"

internal class StreamingAnthropicRequestBody(
    json: Json,
    request: AnthropicRequest,
) : StreamingRequestBody<AnthropicRequest>(json, AnthropicRequest.serializer(), request)

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
internal fun claudeVersionFor(baseUrl: String): String? =
    "2026-10-21".takeIf { baseUrl.trim().toHttpUrlOrNull()?.host == "api.anthropic.com" }

fun anthropicMessagesUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return base
    return when {
        base.endsWith("/messages") -> base
        base.endsWith("/anthropic/v1") -> "$base/messages"
        base.endsWith("/anthropic") -> "$base/v1/messages"
        // DeepSeek 官方：扩展协议挂在 /anthropic 前缀下
        com.simplechat.app.net.isOfficialDeepSeekEndpoint(base) ->
            "${com.simplechat.app.net.officialChatRoot(base)?.removeSuffix("/v1") ?: base}/anthropic/v1/messages"
        // 其余 Anthropic 兼容端点（自建网关 / Claude 中转）：标准路径
        base.endsWith("/v1") -> "$base/messages"
        else -> "$base/v1/messages"
    }
}
