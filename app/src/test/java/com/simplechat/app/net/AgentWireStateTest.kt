package com.simplechat.app.net

import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.MessageRole
import com.simplechat.app.ui.chat.UiMessage
import com.simplechat.app.ui.chat.buildAnthropicRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class AgentWireStateTest {
    @Test fun independentlyRoutedSearchIsSerializedOnlyWhenEnabledAcrossProtocols() = runBlocking {
        val json = ChatApi.defaultJson()
        val host = ProviderKind.DEEPSEEK.defaultBaseUrl
        val model = ModelCatalog.find(ProviderKind.DEEPSEEK, DEEPSEEK_PRO)
        val branch = listOf(
            UiMessage("u1", MessageRole.USER, "上海天气，查 2026 年 11 月 30 日"),
            UiMessage("a1", MessageRole.ASSISTANT, "智能搜索是关闭的。"),
        )
        for (style in ApiStyle.entries) {
            var captured: JsonObject? = null
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer().also { chain.request().body!!.writeTo(it) }
                captured = json.parseToJsonElement(buffer.readUtf8()).jsonObject
                val frames = when (style) {
                    ApiStyle.ANTHROPIC -> listOf(
                        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"fixture"}}""",
                        """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                        """{"type":"message_stop"}""",
                    )
                    ApiStyle.OPENAI -> listOf(
                        """{"choices":[{"delta":{"content":"fixture"},"finish_reason":"stop"}]}""", "[DONE]",
                    )
                    ApiStyle.OPENAI_RESPONSES -> listOf(
                        """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"fixture"}""",
                        """{"type":"response.completed","response":{"output":[]}}""",
                    )
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(frames.joinToString("\n\n", postfix = "\n\n") { "data: $it" }.toResponseBody()).build()
            }.build()
            val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
            val cases = listOf(false to "现在呢", true to "现在呢", true to "改查今天")
            for ((enabled, followUp) in cases) {
                val messages = branch + UiMessage("u2", MessageRole.USER, followUp)
                val runtime = AgentRuntimeState(
                    webConfigured = true, clientSearchEnabled = enabled, webSwitchOn = enabled, webFetchEnabled = enabled, imageExposed = true,
                )
                agentLoop(null, style, runtime = { runtime }) { history, offer, output ->
                    if (style == ApiStyle.ANTHROPIC) {
                        val base = buildAnthropicRequest(model.id, "", messages, false, false, false, null,
                            generateImageTool = true)
                        api.streamMessage(host, "fixture", history.anthropic(base, offer))
                    } else {
                        api.streamChat(host, "fixture", model, messages.map {
                            OutMessageDto(it.role.name.lowercase(), JsonPrimitive(it.content))
                        }, thinkingEnabled = false, apiStyle = style, independentWeb = offer,
                            generateImageTool = history.imageOffered, agentStateInstruction = history.runtimeInstruction,
                            onResponseOutput = output)
                    }
                }.toList()
                val body = checkNotNull(captured)
                val names = body["tools"]!!.jsonArray.map { tool ->
                    val fields = tool.jsonObject
                    (fields["name"] ?: fields["function"]!!.jsonObject["name"])!!.jsonPrimitive.content
                }.toSet()
                assertEquals(if (enabled) clientToolNames else setOf(imageToolDefinition.name), names)
                assertTrue(body.toString().contains("2026 年 11 月 30 日"))
                assertTrue(body.toString().contains(followUp))
                if (enabled) assertTrue(body.toString().contains("An explicit change"))
                else assertTrue(body.toString().contains("configured_but_disabled_by_user"))
            }
        }
    }

    @Test fun routesRespectTheActualHostModelAndProtocol() {
        val deepSeek = ProviderKind.DEEPSEEK.defaultBaseUrl
        val openAi = "https://api.openai.com/v1"
        for (style in ApiStyle.entries) {
            assertEquals(AgentSearchRoute.FLASH_NATIVE, AgentSearchRoute.resolve(deepSeek, DEEPSEEK_FLASH, style, false, true))
            assertEquals(AgentSearchRoute.FLASH_BRIDGE, AgentSearchRoute.resolve(deepSeek, DEEPSEEK_FLASH, style, true, false))
            assertEquals(AgentSearchRoute.INDEPENDENT, AgentSearchRoute.resolve(deepSeek, DEEPSEEK_FLASH, style, true, true))
            assertEquals(AgentSearchRoute.INDEPENDENT, AgentSearchRoute.resolve(deepSeek, DEEPSEEK_PRO, style, false, true))
        }
        assertEquals(AgentSearchRoute.RESPONSES_NATIVE, AgentSearchRoute.resolve(openAi, "gpt-5.4", ApiStyle.OPENAI_RESPONSES, true, true))
        assertEquals(AgentSearchRoute.UNAVAILABLE, AgentSearchRoute.resolve("https://example.org", "gpt-5-search-api", ApiStyle.OPENAI, false, false))
    }

    @Test fun repeatedStaleSearchDenialHasOneRetryAndExplicitFailure() = runBlocking {
        var requests = 0
        var retries = 0
        var unresolved = 0
        agentLoop(null, ApiStyle.OPENAI, onSearchAnswerRetry = { retries++ }, onSearchAnswerUnresolved = { unresolved++ },
            runtime = { AgentRuntimeState(webConfigured = true, clientSearchEnabled = true, webSwitchOn = true) }) { history, offer, _ ->
            assertTrue(offer)
            assertEquals(if (requests++ == 0) AgentRoundPhase.ACT else AgentRoundPhase.RECOVER_SEARCH, history.phase)
            flowOf(StreamEvent.Content("Web search is still disabled."), StreamEvent.Stopped("stop"))
        }.toList()
        assertEquals(2, requests)
        assertEquals(1, retries)
        assertEquals(1, unresolved)
    }

    @Test fun turningOffSearchBetweenCallsPreventsTheSecondNetworkRequest() = runBlocking {
        var on = true
        var executed = 0
        var requests = 0
        agentLoop(null, ApiStyle.OPENAI, runtime = { AgentRuntimeState(webConfigured = true, clientSearchEnabled = on, webSwitchOn = on) }, nativeSearch = {
            executed++
            on = false
            AgentToolOutcome(AgentToolStatus.EMPTY, "No matches")
        }) { history, offer, _ ->
            if (requests++ == 0) {
                assertTrue(offer)
                flowOf(
                    StreamEvent.ToolCallDelta(0, "one", IndependentSearchName, """{"query":"first query"}"""),
                    StreamEvent.ToolCallDelta(1, "two", IndependentSearchName, """{"query":"second query"}"""),
                    StreamEvent.Stopped("tool_calls"),
                )
            } else {
                assertFalse(offer)
                assertFalse(history.webUsed)
                assertEquals(listOf("empty", "disabled"), history.transcript.events
                    .filter { it.kind == AgentTranscriptKind.TOOL_RESULT }.map { it.status })
                flowOf(StreamEvent.Content("No matches were found."), StreamEvent.Stopped("stop"))
            }
        }.toList()
        assertEquals(1, executed)
        assertEquals(2, requests)
    }

    @Test fun liveOfficialFlashSearchAfterToggle() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("SIMPLECHAT_AGENT_LIVE") == "1")
        val key = System.getenv("DEEPSEEK_API_KEY")?.trim().orEmpty()
        org.junit.Assume.assumeTrue(key.isNotEmpty())
        val client = ChatApi.defaultClient()
        val json = ChatApi.defaultJson()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        val host = ProviderKind.DEEPSEEK.defaultBaseUrl
        val user = UiMessage("u1", MessageRole.USER, "帮我查询上海当地 2026 年 11 月 30 日的天气预报。")
        val conversation = listOf(user, UiMessage("a1", MessageRole.ASSISTANT, "智能搜索关闭，无法核实该日期。"),
            UiMessage("u2", MessageRole.USER, "现在呢"))
        val base = buildAnthropicRequest(DEEPSEEK_FLASH,
            "今天是 2026 年 11 月 23 日。以本轮工具为准。", conversation,
            searchEnabled = true, thinkingEnabled = false, requestTitle = false, latestReminder = null, maxTokens = 768)
        val events = agentLoop(null, ApiStyle.ANTHROPIC,
            runtime = { AgentRuntimeState(webConfigured = true, webSwitchOn = true, nativeSearchEnabled = true) },
            onSearchAnswerRetry = {}, observe = { round, event -> println("official Flash round=$round $event") },
        ) { history, offer, _ ->
            val prepared = history.anthropic(base, offer)
            assertEquals(listOf("deepseek_web_search"), prepared.tools.map { it.name })
            api.streamMessage(host, key, prepared, retryBeforeContent = false)
        }.toList()
        val errors = events.filterIsInstance<StreamEvent.Error>()
        assertTrue("Official Flash error HTTP status: "+errors.map { it.code }, errors.isEmpty())
        val blocks = events.filterIsInstance<StreamEvent.BlockStart>().map { it.block }
        val searchCalls = blocks.filterIsInstance<ReplyBlock.ToolUse>().filter { it.action == "search" }
        assertTrue("No native search call returned", searchCalls.isNotEmpty())
        println("official Flash queries: "+searchCalls.flatMap { it.queries })
        val text = blocks.filterIsInstance<ReplyBlock.Text>().joinToString(" ") { it.text } +
            events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
        println("official Flash synthetic answer: "+text.take(1000))
        assertTrue("Answer did not preserve requested day", text.contains("30"))
        assertFalse("Answer incorrectly claimed switch was off", claimsSearchIsOff(text))
    }


    @Test fun liveOfficialProUsesIndependentToolAfterToggle() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("SIMPLECHAT_AGENT_LIVE") == "1")
        val key = System.getenv("DEEPSEEK_API_KEY")?.trim().orEmpty()
        org.junit.Assume.assumeTrue(key.isNotEmpty())
        val json = ChatApi.defaultJson()
        val host = ProviderKind.DEEPSEEK.defaultBaseUrl
        val queries = mutableListOf<String>()
        val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != "search.invalid") return@addInterceptor chain.proceed(request)
            val buffer = Buffer().also { request.body!!.writeTo(it) }
            val body = json.parseToJsonElement(buffer.readUtf8()).jsonObject
            queries += body["query"]?.jsonPrimitive?.content.orEmpty()
            // A controlled response with no target-date evidence exercises the honest empty-result path.
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"results":[]}""".toResponseBody()).build()
        }.build()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        val config = com.simplechat.app.data.StoredSettings(modelId = DEEPSEEK_PRO, apiKey = key,
            searchApi = com.simplechat.app.data.ServiceApiConfig(enabled = true, baseUrl = "https://search.invalid"))
        val user = UiMessage("u1", MessageRole.USER, "帮我查询上海当地 2026 年 11 月 30 日的天气预报。")
        val conversation = listOf(user, UiMessage("a1", MessageRole.ASSISTANT, "智能搜索关闭，无法核实该日期。"),
            UiMessage("u2", MessageRole.USER, "现在呢"))
        val base = buildAnthropicRequest(DEEPSEEK_PRO, "今天是 2026 年 11 月 23 日。", conversation,
            searchEnabled = false, thinkingEnabled = false, requestTitle = false, latestReminder = null, maxTokens = 768)
        val events = agentLoop(WebResearchSession(config, "现在呢", ServiceApi(client)), ApiStyle.ANTHROPIC,
            onSearchAnswerRetry = {}, observe = { round, event -> println("official Pro round=$round $event") },
        ) { history, offer, _ ->
            api.streamMessage(host, key, history.anthropic(base, offer), retryBeforeContent = false)
        }.toList()
        val errors = events.filterIsInstance<StreamEvent.Error>()
        assertTrue("Official Pro error HTTP status: "+errors.map { it.code }, errors.isEmpty())
        println("official Pro independent queries: $queries")
        assertTrue("No independent query preserved the original place and day", queries.any { it.contains("上海") && it.contains("30") })
        val text = events.filterIsInstance<StreamEvent.BlockStart>().map { it.block }
            .filterIsInstance<ReplyBlock.Text>().joinToString(" ") { it.text } +
            events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text }
        println("official Pro synthetic answer: "+text.take(1000))
        assertTrue("Answer did not preserve requested day", text.contains("30"))
        assertFalse("Answer incorrectly claimed switch was off", claimsSearchIsOff(text))
    }

}
