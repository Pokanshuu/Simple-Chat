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
    fun `Gemini fallback matches chat IDs without assuming every Gemini model has one million tokens`() {
        val kind = ProviderKind.CUSTOM
        assertEquals(1_048_576, ModelCatalog.find(kind, "google/gemini-2.5-flash-preview-09-2025").contextWindow)
        assertEquals(1_048_576, ModelCatalog.find(kind, "models/gemini-3.1-pro-preview").contextWindow)
        assertEquals(128_000, ModelCatalog.find(kind, "gemini-2.5-flash-image").contextWindow)
        assertEquals(128_000, ModelCatalog.find(kind, "gemini-2.5-flash-live").contextWindow)
    }
}
