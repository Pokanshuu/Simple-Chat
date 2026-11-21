package com.simplechat.app.net

import com.simplechat.app.data.ImageGenConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ImageGenerationApiTest {
    private val base = "https://fixture.example"
    private fun api(response: String, verify: (String, okhttp3.Request) -> Unit) = ImageGenerationApi(
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            verify(body, request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(response.toResponseBody()).build()
        }.build(),
    )

    @Test fun geminiUsesNativeEndpointAndReturnsInlineImage() = runBlocking {
        val api = api("""{"candidates":[{"content":{"parts":[{"text":"caption"},{"inlineData":{"mimeType":"image/png","data":"AQID"}}]}}]}""") { body, request ->
            assertEquals("/v1beta/models/fixture:generateContent", request.url.encodedPath)
            assertEquals("fixture-key", request.header("x-goog-api-key"))
            assertTrue(body.contains("\"responseModalities\":[\"TEXT\",\"IMAGE\"]"))
        }
        val image = api.generateIndependent(ImageGenConfig(enabled = true, adapter = "GEMINI", modelId = "fixture"), base, "fixture-key", "a fox")
        assertArrayEquals(byteArrayOf(1, 2, 3), image.bytes)
    }

    @Test fun nanoBanana21UsesTheOfficialInteractionsContract() = runBlocking {
        val google = "https://generativelanguage.googleapis.com/v1beta/openai/"
        val api = api("""{"steps":[{"type":"model_output","content":[{"type":"text","text":"caption"},{"type":"image","mime_type":"image/png","data":"AQID"}]}]}""") { body, request ->
            assertEquals("/v1beta/interactions", request.url.encodedPath)
            assertEquals("2026-05-20", request.header("Api-Revision"))
            assertEquals("fixture-key", request.header("x-goog-api-key"))
            assertTrue(body.contains("\"model\":\"gemini-nano-banana-2.1\""))
            assertTrue(body.contains("\"response_format\":{\"type\":\"image\",\"aspect_ratio\":\"16:9\",\"image_size\":\"2K\"}"))
        }
        val config = ImageGenConfig(enabled = true, adapter = "GEMINI", modelId = "gemini-nano-banana-2.1", aspectRatio = "16:9", geminiImageSize = "2K")
        assertArrayEquals(byteArrayOf(1, 2, 3), api.generateIndependent(config, google, "fixture-key", "a fox").bytes)
    }

    @Test fun dashScopeUsesMultimodalEndpointAndReadsContentImage() = runBlocking {
        val api = api("""{"output":{"choices":[{"message":{"content":[{"image":"data:image/png;base64,AQID"}]}}]}}""") { body, request ->
            assertTrue(request.url.encodedPath.endsWith("/multimodal-generation/generation"))
            assertTrue(body.contains("1024*1024"))
        }
        val image = api.generateIndependent(ImageGenConfig(enabled = true, adapter = "DASHSCOPE", modelId = "fixture"), base, "fixture-key", "a fox")
        assertArrayEquals(byteArrayOf(1, 2, 3), image.bytes)
    }

    @Test fun responsesUsesToolAndReturnsItsFinalImage() = runBlocking {
        val api = api("""{"output":[{"type":"image_generation_call","result":"AQID"}]}""") { body, request ->
            assertEquals("/v1/responses", request.url.encodedPath)
            assertTrue(body.contains("\"model\":\"gpt-image-2.6-sunburst\""))
            assertTrue(body.contains("\"tool_choice\":{\"type\":\"image_generation\"}"))
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), api.generateWithResponses("https://api.openai.com/v1", "fixture-key", "gpt-6.2-astra", "a fox").bytes)
    }
}
