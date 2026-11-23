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

    @Test fun registryKeepsWireNamesSeparateFromLogicalToolIds() {
        val registry = AgentToolRegistry.forCapabilities(AgentCapabilities(webSearch = true, imageGeneration = true))
        assertEquals(AgentToolId.WEB_SEARCH, registry.registration(IndependentSearchName)?.id)
        assertEquals(AgentToolId.IMAGE_GENERATION, registry.registration(imageToolDefinition.name)?.id)
        assertTrue(registry.enabled().any { it.id == AgentToolId.WEB_SEARCH })
        assertTrue(registry.enabled().any { it.id == AgentToolId.IMAGE_GENERATION })
    }

    @Test fun genericAgentLoopRecordsStateCallsAndResults() = runBlocking {
        val events = agentLoop(
            session = null,
            style = ApiStyle.OPENAI,
            generateImage = { "generated" },
        ) { history, _, _ ->
            if (history.transcript.events.any { it.kind == AgentTranscriptKind.TOOL_RESULT }) {
                flowOf(StreamEvent.Content("done"), StreamEvent.Stopped("stop"))
            } else {
                flowOf(
                    StreamEvent.ToolCallDelta(0, "image-1", imageToolDefinition.name,
                        """{"operation":"create","prompt":"A blue square"}"""),
                    StreamEvent.Stopped("tool_calls"),
                )
            }
        }.toList()
        assertTrue(events.any { it is StreamEvent.Content && it.text == "done" })
    }

    @Test fun executorReturnsStructuredDisabledAndInvalidResults() = runBlocking {
        val search = AgentToolExecutor(
            harness = AgentHarness(AgentCapabilities(webSearch = false)),
            webAllowed = false,
            imageAllowed = false,
            session = null,
            nativeSearch = null,
            nativeFetch = null,
            generateImage = null,
        )
        val disabled = search.execute(IndependentSearchName, """{"query":"weather"}""")
        assertEquals(AgentToolStatus.DISABLED, disabled.status)
        val unknown = search.execute("future_tool", "{}")
        assertEquals(AgentToolStatus.BLOCKED, unknown.status)

        val image = AgentToolExecutor(
            harness = AgentHarness(AgentCapabilities(imageGeneration = true)),
            webAllowed = false,
            imageAllowed = true,
            session = null,
            nativeSearch = null,
            nativeFetch = null,
            generateImage = { "ok" },
        )
        assertEquals(AgentToolStatus.INVALID_ARGUMENTS,
            image.execute(imageToolDefinition.name, "{}") .status)

        assertFalse(disabled.isUsableEvidence())
        assertTrue(AgentToolExecutionResult(AgentToolId.WEB_SEARCH, AgentToolStatus.SUCCESS, "result").isUsableEvidence())
        assertFalse(AgentToolExecutionResult(AgentToolId.WEB_SEARCH, AgentToolStatus.FAILED, "failed").isUsableEvidence())
    }

    @Test fun unknownToolNeverReachesAnExecutor() = runBlocking {
        var executed = false
        val events = agentLoop(null, ApiStyle.OPENAI, generateImage = {
            executed = true
            "bad"
        }) { _, _, _ ->
            flowOf(
                StreamEvent.ToolCallDelta(0, "unknown-1", "future_tool", "{}"),
                StreamEvent.Stopped("tool_calls"),
            )
        }.toList()
        assertFalse(executed)
        assertTrue(events.any { it is StreamEvent.Stopped && it.reason == "tool_calls" })
    }

    @Test fun capabilityInstructionReflectsTheActualSwitches() {
        val disabled = agentCapabilityInstruction(
            webConfigured = true, webEnabled = false,
            imageConfigured = true, imageExposed = false,
        )
        assertTrue(disabled.contains("web_search=configured_but_disabled_by_user"))
        assertTrue(disabled.contains("web_fetch=configured_but_disabled_or_unavailable"))
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
        val events = agentLoop(
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
            val events = agentLoop(null, style, generateImage = { invocation ->
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
        agentLoop(null, ApiStyle.OPENAI, generateImage = {
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
