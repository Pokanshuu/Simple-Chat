package com.simplechat.app.net

import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.MessageRole
import com.simplechat.app.ui.chat.UiMessage
import com.simplechat.app.ui.chat.buildAnthropicRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the request *shape* that prefix caches depend on:
 * per-turn instructions must sit at the tail, never at the head of the history.
 */
class RequestShapeCacheTest {

    private fun captureOpenAiBody(
        messages: List<OutMessageDto>,
        agentStateInstruction: String,
    ): JsonObject = runBlocking {
        val json = ChatApi.defaultJson()
        var body: JsonObject? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer().also { chain.request().body!!.writeTo(it) }
            body = json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toResponseBody())
                .build()
        }.build()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        api.streamChat(
            baseUrl = "https://gw.example/v1",
            apiKey = "k",
            model = ModelCatalog.find(ProviderKind.DEEPSEEK, DEEPSEEK_PRO),
            messages = messages,
            thinkingEnabled = false,
            agentStateInstruction = agentStateInstruction,
        ).toList()
        checkNotNull(body)
    }

    @Test
    fun openAiRuntimeInstructionSitsImmediatelyBeforeTheLatestUserTurn() {
        val messages = listOf(
            OutMessageDto("system", kotlinx.serialization.json.JsonPrimitive("SYS")),
            OutMessageDto("user", kotlinx.serialization.json.JsonPrimitive("first question")),
            OutMessageDto("assistant", kotlinx.serialization.json.JsonPrimitive("first answer")),
            OutMessageDto("user", kotlinx.serialization.json.JsonPrimitive("latest question")),
        )
        val body = captureOpenAiBody(messages, agentStateInstruction = "STATE-MARKER")
        val wire = body["messages"]!!.jsonArray
        val roles = wire.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        val markerIndex = wire.indexOfFirst { it.jsonObject["content"].toString().contains("STATE-MARKER") }
        assertEquals("runtime instruction must be the last system message", "system", roles[markerIndex])
        assertEquals("latest question", wire[markerIndex + 1].jsonObject["content"]!!.jsonPrimitive.content)
        // Everything before the instruction is the untouched, cacheable prefix.
        assertEquals(listOf("system", "user", "assistant"), roles.take(markerIndex))
    }

    @Test
    fun changingTheRuntimeStateDoesNotTouchTheCachedPrefix() {
        val messages = listOf(
            OutMessageDto("system", kotlinx.serialization.json.JsonPrimitive("SYS")),
            OutMessageDto("user", kotlinx.serialization.json.JsonPrimitive("q1")),
            OutMessageDto("assistant", kotlinx.serialization.json.JsonPrimitive("a1")),
            OutMessageDto("user", kotlinx.serialization.json.JsonPrimitive("q2")),
        )
        val enabled = captureOpenAiBody(messages, "web_search=enabled")
        val disabled = captureOpenAiBody(messages, "web_search=configured_but_disabled_by_user")
        val prefixOf: (JsonObject) -> List<String> = { wire ->
            val list = wire["messages"]!!.jsonArray
            val cut = list.indexOfFirst { it.jsonObject["content"].toString().contains("web_search=") }
            list.take(cut).map { it.toString() }
        }
        assertEquals(
            "toggling the switch must only change the tail, not the history prefix",
            prefixOf(enabled), prefixOf(disabled),
        )
    }

    @Test
    fun anthropicRuntimeInstructionKeepsAssistantPrefillLast() = runBlocking {
        val base = buildAnthropicRequest(
            model = "deepseek-v4-pro", systemPrompt = "SYS",
            path = listOf(
                UiMessage("u1", MessageRole.USER, "first question"),
                UiMessage("a1", MessageRole.ASSISTANT, "first answer"),
                UiMessage("u2", MessageRole.USER, "latest question"),
            ),
            prefix = UiMessage("a2", MessageRole.ASSISTANT, "unfinished answer"),
            searchEnabled = false, thinkingEnabled = false, requestTitle = false, latestReminder = null,
        )

        agentLoop(
            session = null,
            style = ApiStyle.ANTHROPIC,
            runtime = { AgentRuntimeState(webConfigured = true, webSwitchOn = false) },
        ) { history, offer, _ ->
            val request = history.anthropic(base, offer)
            assertEquals(base.system, request.system)
            assertEquals(listOf("user", "assistant", "user", "assistant"), request.messages.map { it.role })
            assertEquals(base.messages.take(2), request.messages.take(2))
            val question = request.messages[2].content.jsonPrimitive.content
            assertTrue(question.startsWith("latest question\n\n"))
            assertTrue(question.contains("web_search="))
            assertEquals(base.messages.last(), request.messages.last())
            assertEquals("unfinished answer", request.messages.last().content.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
            flowOf(StreamEvent.Content("continued"), StreamEvent.Stopped("stop"))
        }.toList()
        Unit
    }

    @Test
    fun anthropicSystemStaysStableAcrossAgentRounds() = runBlocking {
        val path = listOf(
            UiMessage("u1", MessageRole.USER, "查一下上海天气"),
        )
        val base = buildAnthropicRequest(
            model = "deepseek-v4-pro", systemPrompt = "SYS", path = path,
            searchEnabled = false, thinkingEnabled = false, requestTitle = false, latestReminder = null,
        )
        val requests = mutableListOf<com.simplechat.app.net.anthropic.AnthropicRequest>()
        agentLoop(
            session = null,
            style = ApiStyle.ANTHROPIC,
            nativeSearch = { AgentToolOutcome.success("11 月 30 日有雨") },
            runtime = { AgentRuntimeState(webConfigured = true, webSwitchOn = true, clientSearchEnabled = true) },
        ) { history, offer, _ ->
            requests += history.anthropic(base, offer)
            if (requests.size == 1) {
                flowOf(
                    StreamEvent.ToolCallDelta(0, "c1", IndependentSearchName, """{"query":"上海 11 月 30 日天气"}"""),
                    StreamEvent.Stopped("tool_calls"),
                )
            } else {
                flowOf(StreamEvent.Content("有雨"), StreamEvent.Stopped("stop"))
            }
        }.toList()
        assertEquals(2, requests.size)
        assertEquals("system must not change between rounds", requests[0].system, requests[1].system)
        // The original question is untouched; the note rides on the trailing turn.
        assertEquals("查一下上海天气", requests[1].messages.first().content.jsonPrimitive.content)
        assertTrue(requests[0].messages.last().content.toString().contains("web_search="))
        assertTrue(requests[1].messages.last().content.toString().contains("web_search="))
    }
}
