package com.simplechat.app.net

import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.net.anthropic.AnthropicRequest
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
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
