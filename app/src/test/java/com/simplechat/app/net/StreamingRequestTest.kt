package com.simplechat.app.net

import com.simplechat.app.net.anthropic.StreamingAnthropicRequestBody
import com.simplechat.app.ui.chat.*
import okio.Buffer
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
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
        val request = buildAnthropicRequest("model", "summary", listOf(UiMessage("u", MessageRole.USER, "1")),
            searchEnabled = false, thinkingEnabled = false, requestTitle = false, latestReminder = null)
        val buffer = Buffer()
        val body = StreamingAnthropicRequestBody(json, request)
        body.writeTo(buffer)
        assertEquals(buffer.size, body.contentLength())
        assertEquals(json.encodeToString(com.simplechat.app.net.anthropic.AnthropicRequest.serializer(), request), buffer.readUtf8())
    }

    @Test
    fun `large requests on both protocols send complete fixed length UTF8 and use longer upload timeout`() = runBlocking {
        val json = ChatApi.defaultJson()
        for (anthropic in listOf(false, true)) {
            for (large in listOf(false, true)) {
                val content = "中文 😀 \"\n\u0000".repeat(if (large) 70_000 else 1)
                val request = ChatRequestDto("model", listOf(OutMessageDto("user", MessageContent.text(content))), true)
                val extendedRequest = buildAnthropicRequest("model", "summary",
                    listOf(UiMessage("u", MessageRole.USER, content)), false, false, false, null)
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
                            .streamMessage(url, "", extendedRequest)
                        else SseClient(client, json).stream(url, "", request)
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
