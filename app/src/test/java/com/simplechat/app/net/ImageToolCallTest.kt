package com.simplechat.app.net

import com.simplechat.app.net.anthropic.AnthropicStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageToolCallTest {
    @Test fun deepSeekFragmentsWaitForToolFinish() {
        val parser = SseLineParser(ChatApi.defaultJson())
        val call = ImageToolCall()
        fun read(frame: String) {
            val events = parser.parse("data: " + frame) as SseLineParser.Result.Events
            events.events.forEach(call::consume)
        }
        read("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call1","function":{"name":"generate_image","arguments":"{\"prompt\":\""}}]}}]}""")
        read("""{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"other","arguments":"ignored"}},{"index":0,"function":{"arguments":"a fox\"}"}}]}}]}""")
        assertNull(call.prompt())
        read("""{"choices":[{"finish_reason":"tool_calls"}]}""")
        assertEquals("a fox", call.prompt())
    }

    @Test fun anthropicCompatibleToolBlocksProvideThePrompt() {
        val parser = AnthropicStreamParser(ChatApi.defaultJson(), acceptImageTool = true)
        val call = ImageToolCall()
        listOf(
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call1","name":"generate_image","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"prompt\":\"a fox\"}"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
        ).forEach { frame ->
            val events = parser.parse("data: " + frame) as AnthropicStreamParser.Result.Events
            events.events.forEach(call::consume)
        }
        assertEquals("a fox", call.prompt())
    }

    @Test fun ordinaryTextAndTruncatedToolsDoNotGenerate() {
        val call = ImageToolCall()
        call.consume(StreamEvent.Content("generate_image"))
        call.consume(StreamEvent.ToolCallDelta(0, "id", "generate_image", "{\"prompt\":\"fox\"}"))
        call.consume(StreamEvent.Stopped("length"))
        assertNull(call.prompt())
    }
}
