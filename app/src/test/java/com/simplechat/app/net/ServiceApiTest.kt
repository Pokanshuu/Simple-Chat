package com.simplechat.app.net

import com.simplechat.app.R
import com.simplechat.app.data.*
import com.simplechat.app.ui.chat.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ServiceApiTest {
    @Test fun `independent search preserves incremental reasoning and answer in timeline`() {
        val research = SearchAugmentation(blocks = listOf(
            ReplyBlock.SearchResult("tool", IndependentSearchKind, listOf(WebSource("https://example.com"))),
        ))
        val blocks = research.replyBlocks("reasoning delta", "answer delta", 10, 20)
        assertEquals(20L, (blocks[1] as ReplyBlock.Thinking).endedAt)
        assertTrue(blocks[0] is ReplyBlock.SearchResult)
        assertEquals("reasoning delta", (blocks[1] as ReplyBlock.Thinking).thinking)
        assertEquals("answer delta", (blocks[2] as ReplyBlock.Text).text)
    }
    @Test fun `normalize official tool paths without duplicate v1`() {
        val config = ServiceApiConfig()
        assertEquals("https://api.deepseek.com/v1/tools/search", config.endpoint("https://api.deepseek.com/v1", config.searchPath))
        assertEquals("https://api.deepseek.com/files", config.endpoint("https://api.deepseek.com/anthropic", config.filesPath))
        assertEquals("https://proxy.example/api/files", config.copy(baseUrl = "https://proxy.example/api").endpoint("https://unused.invalid", config.filesPath))
        listOf("//evil.invalid/files", "/../files", "/%2e/files", "/files?key=secret").forEach { path ->
            assertTrue(runCatching { config.endpoint("https://api.deepseek.com", path) }.exceptionOrNull() is AppTextException)
        }
    }

    @Test fun `query preserves unicode and native search syntax`() {
        val query = ServiceApi.query("😀".repeat(140))
        assertEquals(128, query.codePointCount(0, query.length))
        val body = ServiceApi.searchBody("DeepSeek site:github.com after:2026", 5)
        assertEquals(5, body["count"]!!.jsonPrimitive.int)
        assertTrue(body["need_content"]!!.jsonPrimitive.boolean)
        assertEquals("DeepSeek site:github.com after:2026", body["query"]!!.jsonPrimitive.content)
    }

    @Test fun `purpose precedes file and document types select parsing`() {
        val file = File.createTempFile("fixture", ".pdf")
        try {
            file.writeText("pdf-fixture")
            val body = ServiceApi.uploadBody(file, "report.pdf", "application/pdf", "text_recognition")
            val buffer = Buffer(); body.writeTo(buffer)
            val raw = buffer.readUtf8()
            assertTrue(raw.indexOf("name=\"purpose\"") < raw.indexOf("name=\"file\""))
            assertEquals("text_recognition", ServiceApi.purpose("A.PDF", "user_data"))
            assertEquals("rich_layout_recognition", ServiceApi.purpose("A.docx", "rich_layout_recognition"))
            assertEquals("user_data", ServiceApi.purpose("data.xlsx", "text_recognition"))
        } finally { file.delete() }
    }

    @Test fun `independent search sends only the configured key and preserves sources`() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val payload = if (request.url.encodedPath.endsWith("fetch"))
                "{\"title\":\"Page\",\"content\":\"Fetched body\"}"
            else "{\"results\":[{\"url\":\"https://example.com/source\",\"title\":\"Source\",\"summary\":\"Evidence\"}]}"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val settings = StoredSettings(baseUrl = "https://chat.invalid", apiKey = "fixture-key",
            searchApi = ServiceApiConfig(enabled = true, baseUrl = "https://search.invalid"))
        val progress = mutableListOf<List<ReplyBlock>>()
        val session = WebResearchSession(settings, "Read https://example.com/article and explain", ServiceApi(client), onProgress = { progress += it })
        assertTrue(session.execute(IndependentSearchName, """{"query":"article background"}""").message.contains("Evidence"))
        assertTrue(session.execute(IndependentFetchName, """{"url":"https://example.com/article"}""").message.contains("Fetched body"))
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.host == "search.invalid" && it.header("Authorization") == "Bearer fixture-key" })
        val sent = Buffer(); requests[1].body!!.writeTo(sent)
        assertEquals("https://example.com/article", Json.parseToJsonElement(sent.readUtf8()).jsonObject["url"]!!.jsonPrimitive.content)
        assertTrue(progress.first().single() is ReplyBlock.ToolUse)
        assertTrue(progress.last().any { it is ReplyBlock.FetchResult && !it.failed })
    }

    @Test fun `no tool stop does not send any private text to search`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw AssertionError("Unexpected independent API request") }.build()
        val settings = StoredSettings(apiKey = "test-key", searchApi = ServiceApiConfig(enabled = true))
        val session = WebResearchSession(settings, "private draft", ServiceApi(client))
        var turns = 0
        val events = agentLoop(session, ApiStyle.OPENAI) { _, enabled, _ ->
            turns++
            assertTrue(enabled)
            flowOf(StreamEvent.Content("I can search."), StreamEvent.Stopped("stop"))
        }.toList()
        assertEquals(1, turns)
        assertTrue(StreamEvent.Content("I can search.") in events)
        assertTrue(session.execute(IndependentFetchName, """{"url":"https://example.org/unregistered"}""").message.contains("Only public"))
        assertTrue(session.execute(IndependentSearchName, "{} ").message.contains("Invalid"))
    }

    @Test fun `model can search then read a source before bounded final answer across protocols`() = runBlocking {
        for (style in listOf(ApiStyle.OPENAI, ApiStyle.ANTHROPIC, ApiStyle.OPENAI_RESPONSES)) {
            val requests = mutableListOf<JsonObject>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request(); val body = Buffer(); request.body!!.writeTo(body)
                requests += Json.parseToJsonElement(body.readUtf8()).jsonObject
                val fetch = request.url.encodedPath.endsWith("fetch")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (fetch) 503 else 200).message("Test")
                    .body((if (fetch) "{}" else """{"results":[{"url":"https://example.org/source","snippet":"Evidence"}]}""").toResponseBody()).build()
            }.build()
            val settings = StoredSettings(apiKey = "key", searchApi = ServiceApiConfig(enabled = true, baseUrl = "https://search.invalid"))
            val progress = mutableListOf<List<ReplyBlock>>()
            val session = WebResearchSession(settings, "And tomorrow?", ServiceApi(client), onProgress = { progress += it })
            var rounds = 0
            val events = agentLoop(session, style) { history, offer, output ->
                val turn = ++rounds
                assertEquals(turn < 3, offer)
                val wire = when (style) {
                    ApiStyle.OPENAI -> if (turn == 1) "" else history.chat.last().also { assertEquals("id${turn-1}", it.toolCallId) }.content.toString()
                    ApiStyle.ANTHROPIC -> if (turn == 1) "" else history.messages.last().content.jsonArray.single().jsonObject.also {
                        assertEquals("id${turn-1}", it["tool_use_id"]!!.jsonPrimitive.content)
                    }.toString()
                    else -> if (turn == 1) "" else history.responses.last().jsonObject.also {
                        assertEquals("id${turn-1}", it["call_id"]!!.jsonPrimitive.content)
                    }.toString()
                }
                if (turn == 2) assertTrue(wire.contains("Evidence"))
                if (turn == 3) {
                    assertTrue(wire.contains("failed"))
                    flowOf(StreamEvent.Content("Final"), StreamEvent.Stopped("stop"))
                } else {
                    val name = if (turn == 1) IndependentSearchName else IndependentFetchName
                    val args = if (turn == 1) """{"query":"Hong Kong forecast tomorrow"}""" else """{"url":"https://example.org/source"}"""
                    output?.invoke(0, buildJsonObject { put("type", "function_call"); put("name", name); put("call_id", "id$turn"); put("arguments", args) })
                    flowOf(StreamEvent.ToolCallDelta(0, "id$turn", name, args.take(3)), StreamEvent.ToolCallDelta(0, null, null, args.drop(3)), StreamEvent.Stopped("tool_use"))
                }
            }.toList()
            assertEquals(3, rounds)
            assertEquals(2, requests.size)
            assertEquals("Hong Kong forecast tomorrow", requests.first()["query"]!!.jsonPrimitive.content)
            assertTrue(progress.last().any { it is ReplyBlock.FetchResult && it.failed })
            assertTrue(progress.last().any { it is ReplyBlock.SearchResult && it.results.isNotEmpty() })
            assertEquals(listOf(StreamEvent.Content("Final"), StreamEvent.Stopped("stop")), events)
        }
    }

    @Test fun `file references serialize for both protocols without embedding original`() {
        val file = Attachment(Attachment.Kind.TEXT, "report.pdf", data = "", fileId = "file-api-fixture")
        val openai = userContent("Summarize", listOf(file)).jsonArray
        assertEquals("file-api-fixture", openai[1].jsonObject["file_id"]!!.jsonPrimitive.content)
        assertTrue(isSendableOutgoing(UiMessage("u", MessageRole.USER, "", listOf(file))))
        val request = buildAnthropicRequest("deepseek-flash", "system", listOf(UiMessage("u", MessageRole.USER, "Summarize", listOf(file))),
            false, true, false, null, reasoningEffort = "max")
        val block = request.messages.single().content.jsonArray.first().jsonObject
        assertEquals("document", block["type"]!!.jsonPrimitive.content)
        assertEquals("file-api-fixture", block["source"]!!.jsonObject["file_id"]!!.jsonPrimitive.content)
        assertEquals("max", request.outputConfig!!.effort)
        val image = file.copy(remoteImage = true)
        val imageRequest = buildAnthropicRequest("deepseek-flash", "system",
            listOf(UiMessage("u", MessageRole.USER, "Describe", listOf(image))), false, false, false, null)
        assertEquals("image", imageRequest.messages.single().content.jsonArray.first().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `old profiles decode and ciphertext is excluded from debug output`() {
        val legacy = decodeApiProfiles("[{\"id\":\"a\",\"name\":\"a\",\"providerId\":\"CUSTOM\",\"baseUrl\":\"https://a.invalid\",\"modelId\":\"m\"}]").single()
        assertEquals("m", legacy.modelId)
        assertFalse(ServiceApiConfig(apiKeyEncrypted = "secret-ciphertext").toString().contains("secret-ciphertext"))
    }

    @Test fun `file parsing and expired errors have actionable localized hints`() {
        assertEquals(R.string.service_file_expired, providerHintRes("File does not exist"))
        assertEquals(R.string.service_file_format, providerHintRes("password-protected document"))
        assertEquals(R.string.error_http_bad_request, httpStatusRes(422))
        assertEquals(R.string.error_network_timeout, httpStatusRes(408))
    }
}
