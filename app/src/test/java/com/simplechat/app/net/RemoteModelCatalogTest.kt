package com.simplechat.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteModelCatalogTest {
    @Test
    fun `gateway limit takes precedence and both model picker and request budget use it`() {
        val raw = """{"data":[
            {"id":"google/gemini-3.1-pro-preview","context_length":1048576,"top_provider":{"context_length":65536}},
            {"id":"gateway-alias","context_window":"1048576"},
            {"id":"gemini-2.5-flash","context_length":{}},
            {"id":"unknown","context_length":-1}
        ]}"""
        val catalog = parseRemoteModelCatalog(ChatApi.defaultJson(), raw)
        val models = ModelCatalog.pickerModels(ProviderKind.CUSTOM, catalog.ids, catalog.contextWindows)
        assertEquals(listOf(65_536, 1_048_576, 1_048_576, 128_000), models.map { it.contextWindow })
        val active = ModelCatalog.find(ProviderKind.CUSTOM, "gateway-alias", catalog.contextWindows)
        assertEquals(models[1], active)
        validateRequestBudget(228_000, 4_096, active.contextWindow)
    }

    @Test
    fun `native Gemini catalog reads the declared input limit`() {
        val raw = """{"models":[{"name":"models/gemini-2.5-pro","inputTokenLimit":1048576}]}"""
        val catalog = parseRemoteModelCatalog(ChatApi.defaultJson(), raw)
        assertEquals(listOf("models/gemini-2.5-pro"), catalog.ids)
        assertEquals(mapOf("models/gemini-2.5-pro" to 1_048_576), catalog.contextWindows)
    }

    @Test
    fun `gateway map keys are request IDs and explicit capabilities override the baseline`() {
        val raw = """{"models":{
            "deepseek-flash":{"id":"canonical-id","name":"Gateway Flash","contextWindow":1000000,
                "limit":{"context":65536,"output":4096},"top_provider":{"max_completion_tokens":2048},
                "input_modalities":["text"],"supports_thinking":false},
            "alias":{"max_input_tokens":32768,"max_output_tokens":1024,"architecture":{"input_modalities":["text","image"]},
                "supported_parameters":["reasoning"]},
            "catalog_version":2
        }}"""
        val catalog = parseRemoteModelCatalog(ChatApi.defaultJson(), raw)
        val models = ModelCatalog.pickerModels(ProviderKind.CUSTOM, catalog.ids, catalog.contextWindows, catalog.metadata)
        assertEquals(listOf("deepseek-flash", "alias"), models.map { it.id })
        assertEquals("Gateway Flash", models.first().displayName)
        assertEquals(65_536, models.first().contextWindow)
        assertEquals(2_048, models.first().maxOutputTokens)
        assertEquals(false, models.first().supportsVision)
        assertEquals(false, models.first().supportsThinking)
        assertEquals(true, models.last().supportsVision)
        assertEquals(true, models.last().supportsThinking)
        assertEquals(32_768, models.last().contextWindow)
        assertEquals(ReasoningWire.REASONING_OBJECT, models.last().reasoningWire)
    }

    @Test
    fun `manual overrides beat advertised metadata and leave unrelated fields intact`() {
        val catalog = parseRemoteModelCatalog(ChatApi.defaultJson(), """{"data":[
            null, {"id":42}, {"id":"deepseek-flash","input_modalities":["text","image"],
            "effort":{"supported_levels":["high","max"]},"context_window":65536,"maxTokens":2048}
        ],"models":{"ignored":{}}}""")
        val models = ModelCatalog.pickerModels(ProviderKind.CUSTOM, catalog.ids, catalog.contextWindows, catalog.metadata,
            mapOf("deepseek-flash" to ModelCapabilityOverride(false, false, 32_768)))
        val model = models.single()
        assertEquals(false, model.supportsVision)
        assertEquals(false, model.supportsThinking)
        assertEquals(32_768, model.contextWindow)
        assertEquals(2_048, model.maxOutputTokens)
        assertEquals(listOf("high", "max"), model.effortLevels)
        assertEquals("high", model.resolveReasoningEffort("low"))
    }

    @Test
    fun `new Gemini model IDs use their own effort levels and honor gateway limits`() {
        val kind = ProviderKind.CUSTOM
        val flashLite = ModelCatalog.find(kind, "google/gemini-3.5-flash-lite")
        assertEquals(1_048_576, flashLite.contextWindow)
        assertEquals(65_536, flashLite.maxOutputTokens)
        assertEquals(listOf("minimal", "low", "medium", "high"), flashLite.effortLevels)
        assertEquals(true, flashLite.supportsVision)
        assertEquals(true, flashLite.supportsThinking)
        val high = ModelCatalog.find(kind, "gemini-3.8-flash-high")
        assertEquals(1_048_576, high.contextWindow)
        assertEquals("high", high.resolveReasoningEffort("medium"))
        val argon = ModelCatalog.find(kind, "gemini-4-argon")
        assertEquals(1_048_576, argon.contextWindow)
        assertEquals(listOf("low", "medium", "high"), argon.effortLevels)
        val flash = ModelCatalog.find(kind, "gemini-3.8-flash")
        assertEquals("low", flash.resolveReasoningEffort("minimal"))
        val hydrogen = ModelCatalog.find(kind, "gemini-4-hydrogen")
        assertEquals("xhigh", hydrogen.resolveReasoningEffort("max"))
        val carbon = ModelCatalog.find(kind, "gemini-4-carbon", mapOf("gemini-4-carbon" to 98_000))
        assertEquals(98_000, carbon.contextWindow)
        assertEquals("high", carbon.resolveReasoningEffort("max"))
    }

    @Test
    fun `Gemini minimum stays active and supported Flash models can explicitly disable thinking`() {
        fun settings(id: String) = com.simplechat.app.ui.chat.ConversationSettings(
            model = ModelCatalog.find(ProviderKind.CUSTOM, id),
        )
        val mandatory = settings("google/gemini-3.8-flash")
        assertEquals(true, mandatory.thinkingEnabled)
        assertEquals("low", mandatory.reasoningEffort)
        assertEquals("low", buildChatRequest(mandatory.model, emptyList(), false).reasoningEffort)
        val lite = settings("gemini-3.5-flash-lite")
        assertEquals(true, lite.thinkingEnabled)
        assertEquals("minimal", lite.reasoningEffort)
        val flash25 = settings("gemini-2.5-flash")
        assertEquals(false, flash25.thinkingEnabled)
        assertEquals("none", buildChatRequest(flash25.model, emptyList(), false).reasoningEffort)
    }

    @Test
    fun `Gemini fallback matches chat IDs without assuming every Gemini model has one million tokens`() {
        val kind = ProviderKind.CUSTOM
        assertEquals(1_048_576, ModelCatalog.find(kind, "google/gemini-2.5-flash-preview-09-2025").contextWindow)
        assertEquals(1_048_576, ModelCatalog.find(kind, "models/gemini-3.1-pro-preview").contextWindow)
        assertEquals(128_000, ModelCatalog.find(kind, "gemini-2.5-flash-image").contextWindow)
        assertEquals(128_000, ModelCatalog.find(kind, "gemini-2.5-flash-live").contextWindow)
    }
}
