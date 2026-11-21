package com.simplechat.app.net

import com.simplechat.app.data.ImageGenConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageModelListingTest {
    @Test
    fun `list request parses the provider response without generating an image`() = runBlocking {
        val base = "https://fixture.example"
        val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            assertEquals("$base/v1beta/models", request.url.toString())
            assertEquals("fixture-key", request.header("x-goog-api-key"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"models":[{"name":"models/fixture-image"}]}""".toResponseBody()).build()
        }.build()
        val models = ImageGenerationApi(client).fetchModels(ImageGenConfig(adapter = "GEMINI"), base, "fixture-key")
        assertEquals(listOf("fixture-image"), models)
    }

    @Test
    fun `image listing uses the model endpoint and authentication of each adapter`() {
        val base = "https://fixture.example"
        val openAi = imageModelListingRequest(ImageGenConfig(baseUrl = "$base/v1/images/generations"), base, "test-key")
        assertEquals("$base/v1/models", openAi.url.toString())
        assertEquals("Bearer test-key", openAi.header("Authorization"))
        val gemini = imageModelListingRequest(ImageGenConfig(adapter = "GEMINI", baseUrl = "$base/v1beta/openai"), base, "test-key")
        assertEquals("$base/v1beta/models", gemini.url.toString())
        assertEquals("test-key", gemini.header("x-goog-api-key"))
        val dashScope = imageModelListingRequest(ImageGenConfig(adapter = "DASHSCOPE", baseUrl = "$base/api/v1"), base, "test-key")
        assertEquals("$base/compatible-mode/v1/models", dashScope.url.toString())
    }
}
