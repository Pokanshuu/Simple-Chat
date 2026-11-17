package com.simplechat.app.net

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.net.anthropic.AnthropicMessageParam
import com.simplechat.app.net.anthropic.AnthropicRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class RequestBudgetTest {
    @Test fun `oversized requests are rejected before network IO on both protocols`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("Must not open a connection") }.build()
        val json = ChatApi.defaultJson()
        val content = MessageContent.text("中".repeat(300_000))
        val streams = listOf(
            SseClient(client, json).stream("https://test.invalid", "",
                ChatRequestDto("model", listOf(OutMessageDto("user", content)), true), contextWindow = 128_000),
            AnthropicApi(client, json).streamMessage("https://test.invalid", "",
                AnthropicRequest("model", "", listOf(AnthropicMessageParam("user", content))), contextWindow = 128_000),
        )
        for (stream in streams) {
            try {
                stream.collect()
                fail("Expected a local context error")
            } catch (error: AppTextException) {
                assertEquals(R.string.error_context_budget, error.text.resId)
            }
        }
    }

    @Test fun `budget includes system summary attachments and output allowance`() {
        val request = ChatRequestDto("model", listOf(
            OutMessageDto("system", MessageContent.text("中".repeat(1500))),
            OutMessageDto("system", MessageContent.text("a".repeat(4000))),
            OutMessageDto("user", MessageContent.withImages("b".repeat(4000),
                listOf(ImageInput("data:image/jpeg;base64," + "A".repeat(100_000))))),
        ), true)
        assertEquals(4048L, estimatedInputTokens(request))
        validateRequestBudget(4048, 1000, 5048)
        assertThrows(AppTextException::class.java) { validateRequestBudget(4048, 1001, 5048) }
        validateRequestBudget(100, null, 128_000)
        validateRequestBudget(1_000_000, null, null)
    }

    @Test fun `Anthropic continuation thinking and tool text count without treating image bytes as tokens`() {
        val content = buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", "a".repeat(400)) })
            add(buildJsonObject { put("type", "thinking"); put("thinking", "中".repeat(150)); put("signature", "s".repeat(10000)) })
            add(buildJsonObject { put("type", "image"); put("source", buildJsonObject { put("data", "A".repeat(10000)) }) })
        }
        val request = AnthropicRequest("model", "a".repeat(400), listOf(AnthropicMessageParam("assistant", content)))
        assertEquals(1332L, estimatedInputTokens(request))
    }

    @Test fun `large sums cannot overflow past context validation`() {
        assertThrows(AppTextException::class.java) { validateRequestBudget(Int.MAX_VALUE.toLong(), Int.MAX_VALUE, 128_000) }
    }
}
