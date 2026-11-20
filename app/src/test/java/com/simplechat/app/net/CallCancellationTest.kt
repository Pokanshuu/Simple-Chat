package com.simplechat.app.net

import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.net.anthropic.AnthropicRequest
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class CallCancellationTest {
    @Test fun `both streaming protocols cancel while waiting for response headers`() = runBlocking {
        for (anthropic in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val closed = AtomicBoolean(false)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                entered.countDown()
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (!chain.call().isCanceled() && System.nanoTime() < until) Thread.sleep(5)
                closed.set(chain.call().isCanceled())
                throw IOException("test cancellation")
            }.build()
            val json = ChatApi.defaultJson()
            val stream = if (anthropic) AnthropicApi(client, json).streamMessage("https://test.invalid", "",
                AnthropicRequest(model = "m", system = "", messages = emptyList()))
            else SseClient(client, json).stream("https://test.invalid", "", ChatRequestDto("m", emptyList(), stream = true))
            val job = launch(Dispatchers.Default) { stream.collect() }
            check(entered.await(2, TimeUnit.SECONDS))
            job.cancel()
            withTimeout(2_000) { job.join() }
            assertTrue(closed.get())
        }
    }

    @Test fun `terminal SSE errors close both streams even when the server keeps the socket open`() = runBlocking {
        for (anthropic in listOf(false, true)) {
            val peerObservedClose = CompletableDeferred<Boolean>()
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            ServerSocket(0).use { server ->
                val served = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val input = socket.getInputStream().buffered()
                        fun line(): String = buildString {
                            while (true) {
                                val ch = input.read()
                                if (ch < 0 || ch == 10) break
                                if (ch != 13) append(ch.toChar())
                            }
                        }
                        line()
                        var contentLength = 0
                        while (true) {
                            val header = line()
                            if (header.isEmpty()) break
                            if (header.startsWith("content-length:", ignoreCase = true)) {
                                contentLength = header.substringAfter(':').trim().toInt()
                            }
                        }
                        input.readNBytes(contentLength)
                        val payload = if (anthropic) {
                            "data: {\"type\":\"error\",\"error\":{\"message\":\"terminal test\"}}\n\n"
                        } else {
                            "data: {\"error\":{\"message\":\"terminal test\"}}\n\n"
                        }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: keep-alive\r\n\r\n$payload".toByteArray())
                            flush()
                        }
                        peerObservedClose.complete(input.read() == -1)
                    }
                }
                try {
                    val json = ChatApi.defaultJson()
                    val stream = if (anthropic) AnthropicApi(ChatApi.defaultClient(), json).streamMessage(
                        "http://127.0.0.1:${server.localPort}/messages", "",
                        AnthropicRequest(model = "m", system = "", messages = emptyList()),
                    ) else SseClient(ChatApi.defaultClient(), json).stream(
                        "http://127.0.0.1:${server.localPort}/chat", "", ChatRequestDto("m", emptyList(), stream = true),
                    )
                    val events = withTimeout(2_000) { stream.toList() }
                    assertTrue(events.any { it is StreamEvent.Error })
                    assertEquals(StreamEvent.Done, events.last())
                    assertTrue(withTimeout(2_000) { peerObservedClose.await() })
                    served.get(3, TimeUnit.SECONDS)
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test fun `cancellation closes a call before blocked coroutine completes`() = runBlocking {
        val call = OkHttpClient().newCall(okhttp3.Request.Builder().url("https://test.invalid").build())
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val job = launch(Dispatchers.IO) {
            val handle = call.cancelWithCoroutine()
            entered.complete(Unit)
            try { released.await(3, TimeUnit.SECONDS) } finally { handle.cancel() }
        }
        try {
            entered.await()
            job.cancel()
            assertTrue("socket must close while its reader is still blocked", call.isCanceled())
        } finally { released.countDown(); job.join() }
    }
}
