package com.simplechat.app.net

import com.simplechat.app.R
import com.simplechat.app.data.ServiceApiConfig
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.net.anthropic.anthropicMessagesUrl
import com.simplechat.app.ui.chat.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class DeepSeekCompatibilityTest {
    private val official = ProviderKind.DEEPSEEK.defaultBaseUrl
    private val json = ChatApi.defaultJson()

    @Test fun oldOfficialProfileAndFullUrls() {
        val messages = "$official/anthropic/v1/messages"
        val chat = "$official/v1/chat/completions"
        assertEquals(ApiStyle.OPENAI, ApiStyle.resolve(ProviderKind.DEEPSEEK, "AUTO", chat))
        assertEquals(messages, anthropicMessagesUrl(chat))
        assertEquals("$official/chat/completions", chatCompletionsUrl(messages))
        assertEquals("$official/files", ServiceApiConfig().endpoint(messages, "/files"))
        assertEquals(DEEPSEEK_FLASH, effectiveModelId(chat, "deepseek-v4-flash"))
        assertEquals("deepseek-v4-flash", effectiveModelId("https://gw.example/v1", "deepseek-v4-flash"))
        val pro = StoredSettings(modelId = DEEPSEEK_PRO, webSearch = true, filesApi = ServiceApiConfig(enabled = true))
        assertFalse(pro.supportsWebSearch)
        assertFalse(pro.useRemoteFiles)
        assertFalse(pro.copy(searchApi = ServiceApiConfig(enabled = true)).supportsWebSearch)
        assertTrue(pro.copy(apiKey = "chat-key", searchApi = ServiceApiConfig(enabled = true)).supportsWebSearch)
        assertTrue(pro.copy(searchApi = ServiceApiConfig(enabled = true, reuseChatKey = false,
            apiKeyEncrypted = "encrypted-key")).supportsWebSearch)
        assertTrue(pro.copy(searchApi = ServiceApiConfig(enabled = true, baseUrl = "not a URL")).supportsWebSearch.not())
        assertTrue(StoredSettings(providerId = "CUSTOM", baseUrl = "https://api.openai.com/v1/responses",
            modelId = "gpt-5.4", apiStyleId = "OPENAI_RESPONSES").supportsWebSearch)
        assertTrue(StoredSettings(providerId = "CUSTOM", baseUrl = "https://api.openai.com/v1/chat/completions",
            modelId = "gpt-5-search-api", apiStyleId = "OPENAI").supportsWebSearch)
        assertFalse(StoredSettings(providerId = "CUSTOM", baseUrl = "https://api.openai.com/v1/responses",
            modelId = "unknown-model", apiStyleId = "OPENAI_RESPONSES").supportsWebSearch)
        assertFalse(pro.copy(baseUrl = "https://gw.example/api.deepseek.com", modelId = DEEPSEEK_FLASH, apiStyleId = "ANTHROPIC").supportsWebSearch)
    }

    @Test fun proCannotInheritFlashToolsOrSignedHistory() {
        val history = listOf(UiMessage("u", MessageRole.USER, "hi"),
            UiMessage("a", MessageRole.ASSISTANT, "previous answer", model = DEEPSEEK_FLASH,
                timeline = listOf(ReplyBlock.ToolUse("tool", "deepseek_read_url", "read_link", "read_link"),
                    ReplyBlock.FetchResult("tool", "read_link", "https://example.org", signature = "old-signature"))),
            UiMessage("u2", MessageRole.USER, "read https://example.org"))
        val request = buildAnthropicRequest(DEEPSEEK_PRO, "", history, true, true, true, "today", reasoningEffort = "high")
        val wire = json.encodeToJsonElement(request).jsonObject
        assertFalse("title" in wire || "tools" in wire)
        assertFalse(wire.toString().contains("old-signature"))
        assertTrue(wire.toString().contains("previous answer"))
        assertEquals("high", request.outputConfig?.effort)
    }

    @Test fun switchingBackToFlashUsesProTextAndEnablesFlashToolsAgain() {
        val history = listOf(UiMessage("u", MessageRole.USER, "first"),
            UiMessage("a", MessageRole.ASSISTANT, "Pro answer", model = DEEPSEEK_PRO,
                timeline = listOf(ReplyBlock.Thinking("private reasoning", signature = "pro-signature"))),
            UiMessage("u2", MessageRole.USER, "read https://example.org"))
        val request = buildAnthropicRequest(DEEPSEEK_FLASH, "", history, true, true, false, null)
        val wire = json.encodeToJsonElement(request).toString()
        assertTrue("Pro answer" in wire)
        assertFalse("pro-signature" in wire)
        assertEquals(2, request.tools.size)
    }

    @Test fun flashAndProOnBothOpenAiProtocols() = runBlocking {
        for (style in listOf(ApiStyle.OPENAI, ApiStyle.OPENAI_RESPONSES)) for (id in listOf(DEEPSEEK_FLASH, DEEPSEEK_PRO)) {
            val captured = Buffer()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.request().body!!.writeTo(captured)
                val data = if (style == ApiStyle.OPENAI) """{"choices":[{"delta":{"title":"Example"},"finish_reason":"stop"}]}"""
                    else """{"type":"response.completed","response":{"title":"Example"}}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("data: $data\n\ndata: [DONE]\n\n".toResponseBody()).build()
            }.build()
            val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
            val events = api.streamChat(official, "test", ModelCatalog.find(ProviderKind.DEEPSEEK, id),
                listOf(OutMessageDto("user", JsonPrimitive("hi"))), false, apiStyle = style,
                search = true, readLink = true, requestTitle = true).toList()
            val wire = json.parseToJsonElement(captured.readUtf8()).jsonObject
            assertEquals(id, wire["model"]?.jsonPrimitive?.content)
            assertEquals(id == DEEPSEEK_FLASH, "title" in wire)
            assertEquals(if (id == DEEPSEEK_FLASH) 2 else null, wire["tools"]?.jsonArray?.size)
            if (id == DEEPSEEK_FLASH) {
                assertTrue(wire.toString().contains("Distinguish failed, empty or unattempted search"))
            }
            assertTrue(StreamEvent.Title("Example") in events)
        }
    }

    @Test fun providerErrorExplainsHowToRecover() {
        assertEquals(R.string.error_deepseek_flash_feature, providerHintRes("The server-side tool calls (deepseek_web_search, deepseek_read_url) and the title generation feature are unavailable for model deepseek-v4-pro. Please use deepseek-flash instead."))
    }

    @Test fun proCanSearchWithoutImageToolAndReceiveMatchedResult() = runBlocking {
        val bodies = mutableListOf<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val payload = if (request.url.host == "search.invalid") {
                """{"results":[{"url":"https://example.org/forecast","summary":"Rain tomorrow"}]}"""
            } else {
                bodies += json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
                val frames = if (bodies.size == 1) listOf(
                    """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"c1","name":"$IndependentSearchName","input":{}}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"Hong Kong forecast tomorrow\"}"}}""",
                    """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
                ) else listOf(
                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Rain tomorrow"}}""",
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                )
                (frames + """{"type":"message_stop"}""").joinToString("\n\n", postfix = "\n\n") { "data: $it" }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(payload.toResponseBody()).build()
        }.build()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        val config = StoredSettings(modelId = DEEPSEEK_PRO, apiKey = "key", searchApi = ServiceApiConfig(enabled = true, baseUrl = "https://search.invalid"))
        val path = listOf(UiMessage("u", MessageRole.USER, "Hong Kong"), UiMessage("a", MessageRole.ASSISTANT, "Today or tomorrow?"), UiMessage("u2", MessageRole.USER, "Tomorrow"))
        val base = buildAnthropicRequest(DEEPSEEK_PRO, "", path, false, true, false, null)
        val events = agentLoop(WebResearchSession(config, "Tomorrow", ServiceApi(client)), ApiStyle.ANTHROPIC) { h, offer, _ ->
            api.streamMessage(official, "key", h.anthropic(base, offer))
        }.toList()
        assertEquals(2, bodies.size)
        assertEquals(setOf(IndependentSearchName, IndependentFetchName), bodies.first()["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet())
        val last = bodies.last()["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("c1", last["tool_use_id"]!!.jsonPrimitive.content)
        assertTrue(last["content"]!!.jsonPrimitive.content.contains("Rain tomorrow"))
        assertTrue(events.any { it is StreamEvent.BlockStart && it.block is ReplyBlock.Text })
    }

    @Test fun onlyOfficialNonThinkingImageRequestsCanRequireTheImageTool() = runBlocking {
        val captured = mutableListOf<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body!!.writeTo(it) }
            captured += json.parseToJsonElement(body.readUtf8()).jsonObject
            val data = """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: $data\n\ndata: [DONE]\n\n".toResponseBody()).build()
        }.build()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        for ((base, thinking, offerTool) in listOf(
            Triple(official, false, true), Triple(official, true, true),
            Triple("https://gw.example/v1", false, true), Triple(official, false, false),
        )) {
            api.streamChat(base, "test", ModelCatalog.find(ProviderKind.DEEPSEEK, DEEPSEEK_FLASH),
                listOf(OutMessageDto("user", JsonPrimitive("Draw a picture"))), thinking,
                generateImageTool = offerTool, requireImageTool = true).toList()
        }
        assertEquals(listOf("required", "auto", "auto", null), captured.map { it["tool_choice"]?.jsonPrimitive?.content })
        assertEquals("generate_image", captured.first()["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertFalse("tools" in captured.last())
    }
}
