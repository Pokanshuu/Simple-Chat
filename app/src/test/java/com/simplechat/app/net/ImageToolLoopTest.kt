package com.simplechat.app.net

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ImageToolLoopTest {
    @Test fun harnessOnlyExposesConfiguredTools() {
        val searchOnly = AgentHarness(AgentCapabilities(webSearch = true))
        assertTrue(searchOnly.canOfferWeb(0))
        assertTrue(searchOnly.knownTool(IndependentSearchName))
        assertFalse(searchOnly.knownTool(IndependentFetchName))
        assertFalse(searchOnly.knownTool(imageToolDefinition.name))

        val imageOnly = AgentHarness(AgentCapabilities(imageGeneration = true))
        assertFalse(imageOnly.canOfferWeb(0))
        assertTrue(imageOnly.knownTool(imageToolDefinition.name))
        assertFalse(imageOnly.knownTool(IndependentSearchName))
    }

    @Test fun capabilityInstructionReflectsTheActualSwitches() {
        val disabled = agentCapabilityInstruction(
            webConfigured = true, webEnabled = false,
            imageConfigured = true, imageExposed = false,
        )
        assertTrue(disabled.contains("web_search=configured_but_disabled_by_user"))
        assertTrue(disabled.contains("image_generation=configured_but_not_exposed_for_this_request"))
        assertTrue(disabled.contains("do not search"))
        assertTrue(disabled.contains("do not call it"))

        val enabled = agentCapabilityInstruction(
            webConfigured = true, webEnabled = true,
            imageConfigured = true, imageExposed = true,
        )
        assertTrue(enabled.contains("web_search=enabled"))
        assertTrue(enabled.contains("image_generation=enabled"))
        assertFalse(enabled.contains("configured_but_disabled"))
    }

    @Test fun disablingSearchDuringModelResponsePreventsExecution() = runBlocking {
        var enabled = true
        var executed = false
        var requests = 0
        val events = webToolLoop(
            session = null,
            style = ApiStyle.OPENAI,
            nativeSearch = { executed = true; "must not run" },
            runtime = {
                AgentRuntimeState(webConfigured = true, webEnabled = enabled)
            },
        ) { history, offer, _ ->
            requests++
            assertEquals(requests == 1, offer)
            if (requests == 1) {
                enabled = false
                flowOf(
                    StreamEvent.ToolCallDelta(0, "search1", IndependentSearchName, """{"query":"Shanghai weather"}"""),
                    StreamEvent.Stopped("tool_calls"),
                )
            } else {
                assertTrue(history.chat.last().content.toString().contains("disabled"))
                flowOf(StreamEvent.Content("Search is disabled."), StreamEvent.Stopped("stop"))
            }
        }.toList()
        assertFalse(executed)
        assertTrue(events.any { it is StreamEvent.Content && it.text.contains("Search") })
    }

    @Test fun contextualImageCallReturnsResultAndCannotGenerateTwice() = runBlocking {
        for (style in listOf(ApiStyle.ANTHROPIC, ApiStyle.OPENAI, ApiStyle.OPENAI_RESPONSES)) {
            var generated = 0
            var requests = 0
            val args = """{"operation":"create","prompt":"Rainy afternoon on the Shanghai Bund"}"""
            val events = webToolLoop(null, style, generateImage = { invocation ->
                assertEquals("Rainy afternoon on the Shanghai Bund", invocation.prompt)
                generated++
                "Attached successfully"
            }) { history, offerWeb, snapshot ->
                assertFalse(offerWeb)
                if (++requests == 1) {
                    assertTrue(history.imageOffered)
                    val function = buildJsonObject {
                        put("type", "function_call"); put("name", "generate_image")
                        put("call_id", "image1"); put("arguments", args)
                    }
                    snapshot?.invoke(0, function)
                    snapshot?.invoke(1, JsonObject(function + ("call_id" to JsonPrimitive("image2"))))
                    flowOf(StreamEvent.ToolCallDelta(0, "image1", "generate_image", args),
                        StreamEvent.ToolCallDelta(1, "image2", "generate_image", args), StreamEvent.Stopped("tool_use"))
                } else {
                    assertFalse(history.imageOffered)
                    assertTrue(history.imageHandled)
                    val result = when (style) {
                        ApiStyle.ANTHROPIC -> history.messages.last().content.toString()
                        ApiStyle.OPENAI -> history.chat.filter { it.role == "tool" }.joinToString { "${it.toolCallId} ${it.content}" }
                        else -> history.responses.takeLast(2).toString()
                    }
                    assertTrue(result.contains("image1") && result.contains("Attached successfully"))
                    assertTrue(result.contains("image2") && result.contains("Image tool is disabled"))
                    flowOf(StreamEvent.Content("Done"), StreamEvent.Stopped("stop"))
                }
            }.toList()
            assertEquals(1, generated)
            assertEquals(2, requests)
            assertTrue(StreamEvent.Content("Done") in events)
        }
    }

    @Test fun searchAndImageInSameBatchBothExecute() = runBlocking {
        val executed = mutableListOf<String>()
        webToolLoop(null, ApiStyle.OPENAI, generateImage = {
            executed += "image"
            "Image provider failed"
        }, nativeSearch = { query ->
            executed += query
            "Rain in Shanghai"
        }) { history, _, _ ->
            if (history.chat.isEmpty()) flowOf(
                StreamEvent.ToolCallDelta(0, "i", "generate_image", """{"operation":"create","prompt":"Rainy Shanghai"}"""),
                StreamEvent.ToolCallDelta(1, "w", IndependentSearchName, """{"query":"Shanghai weather"}"""),
                StreamEvent.Stopped("tool_calls"),
            ) else {
                assertEquals(listOf("i", "w"), history.chat.drop(1).map { it.toolCallId })
                assertTrue(history.chat[1].content.toString().contains("Image provider failed"))
                flowOf(StreamEvent.Stopped("stop"))
            }
        }.toList()
        assertEquals(listOf("Shanghai weather", "image"), executed)
    }
}
