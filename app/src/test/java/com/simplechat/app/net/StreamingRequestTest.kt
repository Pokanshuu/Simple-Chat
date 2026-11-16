package com.simplechat.app.net

import com.simplechat.app.net.anthropic.StreamingAnthropicRequestBody
import com.simplechat.app.ui.chat.*
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingRequestTest {
    @Test
    fun `OpenAI streaming JSON request is identical to the original serializer`() {
        val json = ChatApi.defaultJson()
        val request = ChatRequestDto("model", listOf(OutMessageDto("user", MessageContent.text("中文 😀 \"\n\u0000"))), stream = true)
        val buffer = Buffer()
        StreamingJsonRequestBody(json, request).writeTo(buffer)
        assertEquals(json.encodeToString(ChatRequestDto.serializer(), request), buffer.readUtf8())
    }

    @Test
    fun `Anthropic streaming JSON contains the post compaction prompt`() {
        val json = ChatApi.defaultJson()
        val request = buildAnthropicRequest("model", "summary", listOf(UiMessage("u", MessageRole.USER, "1")),
            searchEnabled = false, thinkingEnabled = false, requestTitle = false, latestReminder = null)
        val buffer = Buffer()
        StreamingAnthropicRequestBody(json, request).writeTo(buffer)
        assertEquals(json.encodeToString(com.simplechat.app.net.anthropic.AnthropicRequest.serializer(), request), buffer.readUtf8())
    }
}
