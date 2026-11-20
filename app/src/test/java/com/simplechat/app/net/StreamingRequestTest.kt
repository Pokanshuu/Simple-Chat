package com.simplechat.app.net

import com.simplechat.app.db.CompactionEntity
import com.simplechat.app.net.anthropic.StreamingAnthropicRequestBody
import com.simplechat.app.ui.chat.*
import okio.Buffer
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class StreamingRequestTest {
    @Test
    fun `OpenAI streaming JSON request is identical to the original serializer`() {
        val json = ChatApi.defaultJson()
        val request = ChatRequestDto("model", listOf(OutMessageDto("user", MessageContent.text("中文 😀 \"\n\u0000"))), stream = true)
        val buffer = Buffer()
        val body = StreamingJsonRequestBody(json, request)
        body.writeTo(buffer)
        assertEquals(buffer.size, body.contentLength())
        assertEquals(json.encodeToString(ChatRequestDto.serializer(), request), buffer.readUtf8())
    }

    @Test
    fun `Anthropic streaming JSON contains the post compaction prompt`() {
        val json = ChatApi.defaultJson()
        val compacted = CompactionEntity("c", "conv", "a1", "保留的前情摘要", 0, 0)
        val history = listOf(
            UiMessage("u1", MessageRole.USER, "已压缩的旧问题"),
            UiMessage("a1", MessageRole.ASSISTANT, "已压缩的旧回复"),
            UiMessage("u2", MessageRole.USER, "您好"),
        )
        val failedReply = UiMessage("a2", MessageRole.ASSISTANT, "", status = MessageStatus.ERROR)
        val nextQuestion = UiMessage("u3", MessageRole.USER, "继续")
        for ((path, expected) in listOf(history to "您好", (history + failedReply + nextQuestion) to "您好\n\n继续")) {
            val request = buildAnthropicRequest("model", compacted.summary, orderedOutgoingMessages(path, compacted),
                searchEnabled = false, thinkingEnabled = false, requestTitle = false,
                latestReminder = "2026-11-19,星期四,杭州,App,中文")
            val buffer = Buffer()
            val body = StreamingAnthropicRequestBody(json, request)
            body.writeTo(buffer)
            assertEquals(buffer.size, body.contentLength())
            val wire = json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val messages = wire.getValue("messages").jsonArray
            assertEquals(1, messages.size)
            assertEquals("user", messages.single().jsonObject.getValue("role").jsonPrimitive.content)
            assertEquals(expected, messages.single().jsonObject.getValue("content").jsonPrimitive.content)
            val system = wire.getValue("system").jsonPrimitive.content
            assertTrue(system.contains(compacted.summary))
            assertTrue(system.contains("2026-11-19,星期四,杭州,App,中文"))
        }
    }

    @Test
    fun `request can replay only before upload starts`() {
        val json = ChatApi.defaultJson()
        for (size in listOf(16, LargeRequestBytes.toInt())) {
            val text = "x".repeat(size)
            val request = ChatRequestDto(DEEPSEEK_PRO, listOf(OutMessageDto("user", MessageContent.text(text))), true)
            val body = StreamingJsonRequestBody(json, request)
            body.contentLength() // Measuring bytes is not an upload.
            assertTrue(body.canReplayOnDisconnect())
            body.writeTo(Buffer())
            assertFalse(body.canReplayOnDisconnect())
        }
    }

    @Test
    fun `large requests on both protocols send complete fixed length UTF8 and use longer upload timeout`() = runBlocking {
        val json = ChatApi.defaultJson()
        for (anthropic in listOf(false, true)) {
            for (large in listOf(false, true)) {
                val content = if (large) "文".repeat(300_000) + "\n".repeat(96_000) else "中文 😀 \"\n\u0000"
                if (large) assertEquals(224_000, com.simplechat.app.data.TokenEstimate.of(content))
                val history = listOf(
                    UiMessage("u1", MessageRole.USER, "开篇"),
                    UiMessage("a1", MessageRole.ASSISTANT, content),
                    UiMessage("u2", MessageRole.USER, "继续"),
                )
                val model = ModelCatalog.find(ProviderKind.DEEPSEEK, DEEPSEEK_PRO)
                val request = buildChatRequest(model, history.map {
                    OutMessageDto(if (it.role == MessageRole.USER) "user" else "assistant", MessageContent.text(it.content))
                }, thinkingEnabled = false, officialDeepSeek = true)
                val extendedRequest = buildAnthropicRequest(model.id, "summary", history, true, false, true, null)
                val expected = if (anthropic) json.encodeToString(com.simplechat.app.net.anthropic.AnthropicRequest.serializer(), extendedRequest)
                    else json.encodeToString(ChatRequestDto.serializer(), request)
                val timeout = AtomicInteger()
                val executor = Executors.newSingleThreadExecutor()
                ServerSocket(0).use { server ->
                    server.soTimeout = 5000
                    val received = executor.submit<Pair<Map<String, String>, String>> {
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val input = socket.getInputStream().buffered()
                            fun line(): String = buildString {
                                while (true) {
                                    val ch = input.read()
                                    check(ch >= 0) { "Unexpected EOF in headers" }
                                    if (ch == 10) break
                                    if (ch != 13) append(ch.toChar())
                                }
                            }
                            line() // Request line
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val header = line()
                                if (header.isEmpty()) break
                                headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                            }
                            val length = checkNotNull(headers["content-length"]).toInt()
                            val body = input.readNBytes(length).toString(Charsets.UTF_8)
                            val response = if (anthropic) "data: {\"type\":\"message_stop\"}\n\n" else "data: [DONE]\n\n"
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n$response".toByteArray())
                                flush()
                            }
                            headers to body
                        }
                    }
                    try {
                        val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
                            timeout.set(chain.writeTimeoutMillis())
                            chain.proceed(chain.request())
                        }.build()
                        val url = "http://127.0.0.1:${server.localPort}/chat"
                        val stream = if (anthropic) com.simplechat.app.net.anthropic.AnthropicApi(client, json)
                            .streamMessage(url, "", extendedRequest, contextWindow = model.contextWindow)
                        else SseClient(client, json).stream(url, "", request, contextWindow = model.contextWindow)
                        assertEquals(listOf(StreamEvent.Done), stream.toList())
                        val (headers, body) = received.get(5, TimeUnit.SECONDS)
                        assertEquals(expected, body)
                        assertEquals(expected.toByteArray(Charsets.UTF_8).size.toString(), headers["content-length"])
                        assertNull(headers["transfer-encoding"])
                        assertEquals(if (large) 120_000 else 30_000, timeout.get())
                    } finally {
                        executor.shutdownNow()
                    }
                }
            }
        }
    }
}
