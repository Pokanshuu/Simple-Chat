package com.simplechat.app.net

import com.simplechat.app.R
import com.simplechat.app.data.*
import com.simplechat.app.ui.chat.*
import java.io.File
import kotlinx.coroutines.runBlocking
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
        val result = searchAugmentation(settings, "Read https://example.com/article and explain", ServiceApi(client),
            plan = { input ->
                if (Json.parseToJsonElement(input).jsonObject["round"]?.jsonPrimitive?.content == "1")
                    """{"search":["example article background"],"fetch":[]}"""
                else """{"search":["EXAMPLE ARTICLE BACKGROUND"],"fetch":[1,999]}"""
            }) {
            progress += it
        }
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.url.host == "search.invalid" && it.header("Authorization") == "Bearer fixture-key" })
        val sent = Buffer(); requests[1].body!!.writeTo(sent)
        assertEquals("https://example.com/article", Json.parseToJsonElement(sent.readUtf8()).jsonObject["url"]!!.jsonPrimitive.content)
        assertTrue(result.context.contains("Evidence") && result.context.contains("Fetched body"))
        assertEquals(2, result.sources.size)
        assertTrue(progress.first().single() is ReplyBlock.ToolUse)
        assertTrue(progress.last().any { it is ReplyBlock.FetchResult && !it.failed })
    }

    @Test fun `planner resolves follow ups and reads only registered result urls`() = runBlocking {
        val outbound = mutableListOf<Pair<String, JsonObject>>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = Buffer(); request.body!!.writeTo(body)
            outbound += path to Json.parseToJsonElement(body.readUtf8()).jsonObject
            val payload = if (path.endsWith("fetch")) """{"title":"Android release","content":"Release notes"}"""
                else """{"results":[{"url":"http://localhost/internal"},{"url":"https://example.org/android#notes","title":"Android release","snippet":"See release notes"}]}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val settings = StoredSettings(baseUrl = "https://chat.invalid", apiKey = "test-key",
            searchApi = ServiceApiConfig(enabled = true, baseUrl = "https://search.invalid"))
        var rounds = 0
        val result = searchAugmentation(settings, "And on Android?", ServiceApi(client),
            recentTurns = listOf("user" to "Check the SimpleChat Windows release"), plan = { input ->
                rounds++
                assertTrue(input.contains("SimpleChat Windows release"))
                if (rounds == 1) """{"search":["SimpleChat Android official release"],"fetch":[]}"""
                else {
                    assertTrue(input.contains("See release notes"))
                    assertFalse(input.contains("localhost"))
                    """{"search":[],"fetch":[1,1]}"""
                }
            })
        assertEquals(2, rounds)
        assertEquals(2, outbound.size)
        assertEquals("SimpleChat Android official release", outbound[0].second["query"]!!.jsonPrimitive.content)
        assertEquals("https://example.org/android", outbound[1].second["url"]!!.jsonPrimitive.content)
        assertTrue(result.context.contains("Release notes"))
        assertFalse(result.incomplete)
    }

    @Test fun `skip or malformed plan does not send raw message to a search service`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { throw AssertionError("Unexpected independent API request") }.build()
        val settings = StoredSettings(baseUrl = "https://chat.invalid", apiKey = "test-key",
            searchApi = ServiceApiConfig(enabled = true, baseUrl = "https://search.invalid"))
        val skipped = searchAugmentation(settings, "Just say hello", ServiceApi(client), plan = { """{"search":[],"fetch":[]}""" })
        assertTrue(skipped.blocks.isEmpty())
        assertFalse(skipped.incomplete)
        val malformed = searchAugmentation(settings, "A private draft", ServiceApi(client), plan = { "search the whole message" })
        assertTrue(malformed.blocks.isEmpty())
        assertTrue(malformed.incomplete)
    }

    @Test fun `failed fetch preserves successful search evidence`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val fetch = request.url.encodedPath.endsWith("fetch")
            val payload = if (fetch) "{}" else
                """{"results":[{"url":"https://example.org/page","snippet":"Useful search evidence"}]}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (fetch) 503 else 200).message("Test")
                .body(payload.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val settings = StoredSettings(baseUrl = "https://chat.invalid", apiKey = "test-key",
            searchApi = ServiceApiConfig(enabled = true, baseUrl = "https://search.invalid"))
        var round = 0
        val result = searchAugmentation(settings, "Check the release details", ServiceApi(client), plan = {
            if (++round == 1) """{"search":["release details"],"fetch":[]}""" else """{"search":[],"fetch":[1]}"""
        })
        assertTrue(result.incomplete)
        assertTrue(result.context.contains("Useful search evidence"))
        assertTrue(result.blocks.any { it is ReplyBlock.FetchResult && it.failed })
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
