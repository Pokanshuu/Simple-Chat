package com.simplechat.app.net

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
import okhttp3.Request

/**
 * OpenAI 兼容的 SSE 流式客户端。
 *
 * 设计要点：
 * - **不用 Retrofit**：直接 OkHttp + 手写行解析，少一层抽象、少一份反射。
 * - **取消瞬时生效**：独立的子协程在取消开始时关闭 socket，
 *   阻塞中的 `execute()` / `readUtf8Line()` 因而能退出。
 *   这是「点停止立刻停」的关键（见设计文档 §5.5）。
 * - **解析逻辑外置**：行解析在 [SseLineParser] 里，纯函数、可单测。
 *
 * ### 断流自动重试（只在"一个字都没收到"时）
 *
 * 服务端偶尔会把流掐断（HTTP/2 `stream was reset: INTERNAL_ERROR`）。这种失败
 * 发生在**任何内容下发之前**时，重发一次是安全的：用户看到的是回答正常出现，
 * 而不是一条英文报错。
 *
 * 反过来，只要已经收到过任何事件就**绝不重发** —— 重发会让同一段回答出现两遍，
 * 那比让用户点一次「重试」更糟。
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
        contextWindow: Int? = null,
        retryBeforeContent: Boolean = true,
        maxOutputTokens: Int? = null,
    ): Flow<StreamEvent> = streamRequest(url, apiKey, extraHeaders, retryBeforeContent) {
        validateRequestBudget(estimatedInputTokens(request), request.maxTokens ?: request.maxCompletionTokens, contextWindow, maxOutputTokens)
        StreamingJsonRequestBody(json, request)
    }

    internal fun streamResponses(
        url: String,
        apiKey: String,
        request: ResponsesRequestDto,
        messages: List<OutMessageDto>,
        model: ModelInfo,
        extraHeaders: Map<String, String>,
        retryBeforeContent: Boolean,
        onOutput: ((Int, kotlinx.serialization.json.JsonObject) -> Unit)? = null,
    ): Flow<StreamEvent> = streamRequest(url, apiKey, extraHeaders, retryBeforeContent, responses = true, onOutput = onOutput) {
        val toolHistoryTokens = request.input.asSequence().drop(messages.size).sumOf(::contentTokens)
        validateRequestBudget(estimatedInputTokens(messages) + toolHistoryTokens, request.maxOutputTokens, model.contextWindow, model.maxOutputTokens)
        StreamingRequestBody(json, ResponsesRequestDto.serializer(), request)
    }

    private fun streamRequest(
        url: String,
        apiKey: String,
        extraHeaders: Map<String, String>,
        retryBeforeContent: Boolean,
        responses: Boolean = false,
        onOutput: ((Int, kotlinx.serialization.json.JsonObject) -> Unit)? = null,
        prepareBody: () -> StreamingRequestBody<*>,
    ): Flow<StreamEvent> = flow {
        val body = prepareBody()
        val largeRequest = body.contentLength() >= LargeRequestBytes

        val builder = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        val httpRequest = builder.build()

        var attempt = 0
        var emittedAny = false
        while (true) {
            attempt++
            val call = client.newCall(httpRequest)
            val handle = call.cancelWithCoroutine()
            var receivedHeaders = false
            try {
                call.execute().use { response ->
                    receivedHeaders = true
                    if (!response.isSuccessful) {
                        val raw = runCatching { response.body.readErrorText() }.getOrNull()
                        // 已知状态码 → 一句人话；否则用服务端自己给的文案；再否则带状态码兜底
                        val serverMessage = parser.parseErrorMessage(raw)
                        android.util.Log.w(ChatStreamLogTag, "HTTP ${response.code}")
                        emit(
                            StreamEvent.Error(
                                message = httpErrorText(response.code, serverMessage, largeRequest),
                                code = response.code,
                                retryable = response.code == 429 || response.code >= 500,
                                rawMessage = serverMessage,
                            ),
                        )
                        return@use
                    }

                    val source = response.body.source()
                    val responsesParser = if (responses) ResponsesSseLineParser(json, onOutput) else null
                    var finished = false
                    var receivedStop = false
                    while (!finished) {
                        currentCoroutineContext().ensureActive()

                        val line = source.readUtf8Line()
                        if (line == null) {
                            if (!receivedStop) throw java.io.EOFException("Stream ended before completion")
                            finished = true
                        } else {
                            when (val result = responsesParser?.parse(line) ?: parser.parse(line)) {
                                is SseLineParser.Result.Events -> result.events.forEach {
                                    emit(it)
                                    emittedAny = true
                                    if (it is StreamEvent.Error || (responses && it is StreamEvent.Stopped)) {
                                        receivedStop = true
                                        finished = true
                                    } else if (it is StreamEvent.Stopped) {
                                        receivedStop = true
                                    }
                                }

                                SseLineParser.Result.Done -> finished = true
                                SseLineParser.Result.Ignore -> Unit
                            }
                        }
                    }

                    emit(StreamEvent.Done)
                    emittedAny = true
                }
                return@flow
            } catch (e: IOException) {
                // 用户主动取消造成的 IOException 不应报告为错误
                if (!currentCoroutineContext().isActive) return@flow
                if (retryBeforeContent && !emittedAny && attempt < StreamMaxAttempts && body.canReplayOnDisconnect()) {
                    // 一个字都还没发出去 → 重发是安全的（不会出现两份内容）
                    android.util.Log.w(ChatStreamLogTag, "stream reset before any event; retrying", e)
                    delay(StreamRetryDelayMs)
                    continue
                }
                // 原始异常进日志（排查要用），界面拿到的是"连接被中断、请重试"这类人话
                android.util.Log.w(ChatStreamLogTag, "stream interrupted", e)
                emit(StreamEvent.Error(e.networkText(largeRequest && !emittedAny, receivedHeaders), retryable = true))
                return@flow
            } finally {
                handle.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

}

/** 流式客户端的日志 tag：原始异常与服务端报文都记在这里，界面上只给人话。 */
internal const val ChatStreamLogTag = "SimpleChat/Stream"

/** 断流重试次数上限（含首次请求）。 */
internal const val StreamMaxAttempts = 2

/** 断流重试前的等待：给上游一点恢复时间，又不至于让用户觉得卡住。 */
internal const val StreamRetryDelayMs = 600L

/** Serializes directly to Okio instead of duplicating the full request in a UTF-16 String. */
internal class StreamingJsonRequestBody(
    json: Json,
    request: ChatRequestDto,
) : StreamingRequestBody<ChatRequestDto>(json, ChatRequestDto.serializer(), request)
