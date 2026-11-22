package com.simplechat.app.net

import com.simplechat.app.net.anthropic.AnthropicStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageToolCallTest {
    @Test fun deepSeekFragmentsWaitForToolFinish() {
        val parser = SseLineParser(ChatApi.defaultJson())
        val call = ImageToolCall()
        fun read(frame: String) {
            val events = parser.parse("data: " + frame) as SseLineParser.Result.Events
            events.events.forEach(call::consume)
        }
        read("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call1","function":{"name":"generate_image","arguments":"{\"operation\":\"create\",\"prompt\":\""}}]}}]}""")
        read("""{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"name":"other","arguments":"ignored"}},{"index":0,"function":{"arguments":"a fox\"}"}}]}}]}""")
        assertNull(call.invocation())
        read("""{"choices":[{"finish_reason":"tool_calls"}]}""")
        assertEquals(ImageToolInvocation("a fox", ImageToolOperation.CREATE), call.invocation())
    }

    @Test fun anthropicCompatibleToolBlocksProvideThePrompt() {
        val parser = AnthropicStreamParser(ChatApi.defaultJson(), acceptImageTool = true)
        val call = ImageToolCall()
        listOf(
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call1","name":"generate_image","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"operation\":\"edit\",\"prompt\":\"a fox\"}"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
        ).forEach { frame ->
            val events = parser.parse("data: " + frame) as AnthropicStreamParser.Result.Events
            events.events.forEach(call::consume)
        }
        assertEquals(ImageToolInvocation("a fox", ImageToolOperation.EDIT), call.invocation())
    }

    @Test fun ordinaryTextAndTruncatedToolsDoNotGenerate() {
        val call = ImageToolCall()
        call.consume(StreamEvent.Content("generate_image"))
        call.consume(StreamEvent.ToolCallDelta(0, "id", "generate_image", "{\"prompt\":\"fox\"}"))
        call.consume(StreamEvent.Stopped("length"))
        assertNull(call.invocation())
        assertFalse(call.endedWithoutCall)
    }

    @Test fun onlyNormalTextCompletionCanOfferDirectGeneration() {
        val call = ImageToolCall()
        call.consume(StreamEvent.Content("Image generated."))
        call.consume(StreamEvent.Stopped("refusal"))
        assertFalse(call.endedWithoutCall)
        call.consume(StreamEvent.Stopped("end_turn"))
        assertTrue(call.endedWithoutCall)
        assertNull(call.invocation())
    }

    @Test fun unknownOrMissingOperationNeverExecutes() {
        listOf("""{"prompt":"fox"}""", """{"prompt":"fox","operation":"redraw"}""").forEach { args ->
            val call = ImageToolCall()
            call.consume(StreamEvent.ToolCallDelta(0, "id", "generate_image", args))
            call.consume(StreamEvent.Stopped("tool_use"))
            assertThrows(IllegalArgumentException::class.java) { call.invocation() }
            assertFalse(call.endedWithoutCall)
        }
    }
}
