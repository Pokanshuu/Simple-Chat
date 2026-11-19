package com.simplechat.app.net

import com.simplechat.app.net.anthropic.AnthropicApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesApiTest {
    private val json = ChatApi.defaultJson()

    @Test fun `Responses sends a summary request with protocol-specific image input`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("/gateway/v1/responses", request.url.encodedPath)
            assertEquals("Bearer fixture-key", request.header("Authorization"))
            val encoded = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val body = json.parseToJsonElement(encoded).jsonObject
            assertEquals("true", body["stream"].toString())
            assertEquals("false", body["store"].toString())
            assertEquals("512", body["max_output_tokens"].toString())
            assertEquals("\"auto\"", body["reasoning"]!!.jsonObject["summary"].toString())
            assertEquals("\"high\"", body["reasoning"]!!.jsonObject["effort"].toString())
            assertFalse("messages" in body)
            assertFalse("reasoning_effort" in body)
            val image = body["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.last().jsonObject
            assertEquals("\"input_image\"", image["type"].toString())
            assertEquals("\"data:image/png;base64,AAA\"", image["image_url"].toString())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""
                    event: response.reasoning_summary_text.delta
                    data: {"type":"response.reasoning_summary_text.delta","output_index":0,"summary_index":0,"delta":"Summary"}

                    data: {"type":"response.output_text.delta","output_index":1,"delta":"Answer"}

                    data: {"type":"response.completed","response":{"usage":{"input_tokens":17,"output_tokens":12,"input_tokens_details":{"cached_tokens":4}}}}
                """.trimIndent().toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        val events = api.streamChat(
            baseUrl = "https://fixture.invalid/gateway/v1/chat/completions", apiKey = "fixture-key",
            model = ModelInfo("gateway-alias", supportsThinking = true, maxOutputTokens = 512),
            messages = listOf(OutMessageDto("user", MessageContent.withImages("Explain", listOf(ImageInput("data:image/png;base64,AAA"))))),
            thinkingEnabled = true, reasoningEffort = "high", maxTokens = 1024, apiStyle = ApiStyle.OPENAI_RESPONSES,
        ).toList()
        assertEquals(listOf(StreamEvent.Reasoning("Summary"), StreamEvent.Content("Answer"),
            StreamEvent.Usage(17, 12, 4), StreamEvent.Stopped("stop"), StreamEvent.Done), events)
    }

    @Test fun `completed items supply missing summary without repeating streamed text`() {
        val parser = ResponsesSseLineParser(json)
        fun parse(payload: String) = (parser.parse("data: $payload") as? SseLineParser.Result.Events)?.events.orEmpty()
        assertEquals(listOf(StreamEvent.Reasoning("First")), parse(
            """{"type":"response.reasoning_summary_text.delta","output_index":0,"summary_index":0,"delta":"First"}""",
        ))
        assertEquals(listOf(StreamEvent.Reasoning("\n\nSecond")), parse(
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","summary":[{"type":"summary_text","text":"First"},{"type":"summary_text","text":"Second"}],"encrypted_content":"hidden"}}""",
        ))
        assertEquals(listOf(StreamEvent.Stopped("length")), parse(
            """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"},"output":[{"type":"reasoning","summary":[{"type":"summary_text","text":"First"},{"type":"summary_text","text":"Second"}]}]}}""",
        ))
        assertTrue(parse("""{"type":"response.output_item.done","output_index":2,"item":{"type":"reasoning","summary":[],"encrypted_content":"hidden"}}""").isEmpty())
    }

    @Test fun `Responses is explicit or selected by the endpoint path`() {
        val root = "https://fixture.invalid/gateway/v1"
        assertEquals(ApiStyle.OPENAI, ApiStyle.resolve(ProviderKind.CUSTOM, "AUTO", root))
        assertEquals(ApiStyle.OPENAI_RESPONSES, ApiStyle.resolve(ProviderKind.CUSTOM, "AUTO", "$root/responses/"))
        assertEquals(ApiStyle.OPENAI_RESPONSES, ApiStyle.resolve(ProviderKind.CUSTOM, "OPENAI_RESPONSES", root))
        assertEquals("$root/models", modelsUrl("$root/responses/"))
        assertEquals("$root/responses", responsesUrl("$root/responses/"))
    }
}
