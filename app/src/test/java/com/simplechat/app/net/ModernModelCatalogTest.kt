package com.simplechat.app.net

import com.simplechat.app.ui.chat.MessageRole
import com.simplechat.app.ui.chat.UiMessage
import com.simplechat.app.ui.chat.buildAnthropicRequest
import org.junit.Assert.*
import org.junit.Test

class ModernModelCatalogTest {
    @Test
    fun `GPT snapshot IDs use documented context and effort on both request protocols`() {
        val model = ModelCatalog.find(ProviderKind.CUSTOM, "openai/gpt-6.2-astra-2026-11-17")
        assertEquals(1_050_000, model.contextWindow)
        assertTrue(model.supportsVision)
        assertTrue(model.supportsThinking)
        val chat = buildChatRequest(model, emptyList(), true, "max", 200_000)
        assertEquals("max", chat.reasoningEffort)
        assertEquals(128_000, chat.maxCompletionTokens)
        assertNull(chat.maxTokens)
        val responses = buildResponsesRequest(model, emptyList(), true, "xhigh", 200_000, null)
        assertEquals("xhigh", responses.reasoning?.effort)
        assertEquals(128_000, responses.maxOutputTokens)
        val terra = ModelCatalog.find(ProviderKind.CUSTOM, "gpt-6.1-terra")
        assertEquals("none", buildChatRequest(terra, emptyList(), false).reasoningEffort)
        assertEquals("https://api.openai.com/v1/models", modelsUrl("https://api.openai.com/"))
        assertEquals("https://api.openai.com/v1/responses", responsesUrl("https://api.openai.com"))
    }

    @Test
    fun `GPT off selection sends an explicit effort on both protocols`() {
        for ((id, expected) in listOf("gpt-5.2" to "none", "gpt-6.2-sol" to "low", "openai/gpt-5-mini" to "minimal")) {
            val model = ModelCatalog.find(ProviderKind.CUSTOM, id)
            assertEquals(expected, buildChatRequest(model, emptyList(), false).reasoningEffort)
            val request = buildResponsesRequest(model, emptyList(), false, null, null, null)
            assertEquals(expected, request.reasoning?.effort)
            assertNull(request.reasoning?.summary)
        }
    }

    @Test
    fun `only dedicated search models lock the search switch`() {
        val official = "https://api.openai.com/v1"
        assertTrue(usesAutomaticOpenAiSearch("openai/gpt-5-search-api-2025-10-02", ApiStyle.OPENAI))
        assertFalse(usesAutomaticOpenAiSearch("gpt-5.2", ApiStyle.OPENAI))
        assertTrue(supportsOpenAiSearchTool(official, "gpt-5.2", ApiStyle.OPENAI_RESPONSES))
        assertFalse(supportsOpenAiSearchTool(official, "gpt-5.2", ApiStyle.OPENAI))
        assertFalse(supportsOpenAiSearchTool("https://proxy.example/v1", "gpt-5.2", ApiStyle.OPENAI_RESPONSES))
    }

    @Test
    fun `native Claude sends adaptive thinking and omits unsupported temperature`() {
        val model = ModelCatalog.find(ProviderKind.CUSTOM, "anthropic/claude-opus-5.5")
        assertEquals(1_000_000, model.contextWindow)
        val path = listOf(UiMessage("user", MessageRole.USER, "hello"))
        fun request(thinking: Boolean, id: String = model.id) = buildAnthropicRequest(
            model = id, systemPrompt = "", path = path, searchEnabled = false, thinkingEnabled = thinking,
            requestTitle = false, latestReminder = null, deepSeekExtensions = false,
            reasoningEffort = "xhigh", temperature = 0.8,
        )
        val adaptive = request(true)
        assertEquals("adaptive", adaptive.thinking?.type)
        assertEquals("summarized", adaptive.thinking?.display)
        assertNull(adaptive.thinking?.budgetTokens)
        assertNull(adaptive.temperature)
        assertEquals("xhigh", adaptive.outputConfig?.effort)
        assertEquals("adaptive", request(false).thinking?.type)
        assertNull(request(false).outputConfig)
        assertEquals("disabled", request(false, "claude-opus-4-8").thinking?.type)
        assertEquals(ApiStyle.ANTHROPIC, ApiStyle.resolve(ProviderKind.CUSTOM, "AUTO", "https://api.anthropic.com"))
    }

    @Test
    fun `Claude metadata overrides fallback model capabilities`() {
        val catalog = parseRemoteModelCatalog(ChatApi.defaultJson(), """{"data":[{
          "id":"claude-haiku-5-5","max_input_tokens":99000,"max_tokens":4096,
          "capabilities":{"image_input":{"supported":false},"thinking":{"supported":true},
            "effort":{"supported":true,"low":{"supported":true},"xhigh":{"supported":false}}}
        }]}""")
        val model = ModelCatalog.pickerModels(ProviderKind.CUSTOM, catalog.ids, catalog.contextWindows, catalog.metadata).single()
        assertEquals(99_000, model.contextWindow)
        assertEquals(4096, model.maxOutputTokens)
        assertFalse(model.supportsVision)
        assertTrue(model.supportsThinking)
        assertEquals(listOf("low"), model.effortLevels)
    }
}
