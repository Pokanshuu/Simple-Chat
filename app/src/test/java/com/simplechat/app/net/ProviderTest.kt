package com.simplechat.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider 层回归测试。
 *
 * 重点是 [ProviderKind.OPENCODE_GO] 的 `x-opencode-session` 头 ——
 * 缺了它 OpenCode Go 会直接返回 400，且错误信息只在响应体里，
 * 属于「一漏就整个服务商不可用」的致命问题（已实测确认）。
 */
class ProviderTest {

    @Test
    fun `OpenCode Go 必须携带 x-opencode-session`() {
        val headers = ProviderKind.OPENCODE_GO.headersFor("session-abc")
        assertEquals("session-abc", headers["x-opencode-session"])
    }

    @Test
    fun `DeepSeek 不携带额外请求头`() {
        assertTrue(ProviderKind.DEEPSEEK.headersFor("session-abc").isEmpty())
    }

    @Test
    fun `自定义服务商不携带额外请求头`() {
        assertTrue(ProviderKind.CUSTOM.headersFor("session-abc").isEmpty())
    }

    // ── 地址拼接 ──────────────────────────────────────────

    @Test
    fun `DeepSeek 地址拼接`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            chatCompletionsUrl("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            chatCompletionsUrl("https://api.deepseek.com/"),
        )
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            chatCompletionsUrl("  https://api.deepseek.com  "),
        )
    }

    @Test
    fun `OpenCode Go 地址拼接保留 v1 前缀`() {
        assertEquals(
            "https://opencode.ai/zen/go/v1/chat/completions",
            chatCompletionsUrl("https://opencode.ai/zen/go/v1"),
        )
    }

    @Test
    fun `已是完整路径时不重复拼接`() {
        val full = "https://example.com/v1/chat/completions"
        assertEquals(full, chatCompletionsUrl(full))
    }

    @Test
    fun `models 地址拼接`() {
        assertEquals(
            "https://opencode.ai/zen/go/v1/models",
            modelsUrl("https://opencode.ai/zen/go/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/models",
            modelsUrl("https://api.deepseek.com/"),
        )
    }

    @Test
    fun `discovery follows configured listing protocol and uses the official DeepSeek catalog`() {
        val openAi = modelListingRequest("https://gw.example/route/v1/chat/completions", "test-key", ApiStyle.OPENAI)
        assertEquals("https://gw.example/route/v1/models", openAi.url.toString())
        assertEquals("Bearer test-key", openAi.header("Authorization"))
        assertEquals(null, openAi.header("x-api-key"))
        val anthropic = modelListingRequest("https://gw.example/route/v1/messages", "test-key", ApiStyle.ANTHROPIC)
        assertEquals("https://gw.example/route/v1/models", anthropic.url.toString())
        assertEquals("test-key", anthropic.header("x-api-key"))
        assertEquals("2023-06-01", anthropic.header("anthropic-version"))
        val official = modelListingRequest("https://api.deepseek.com/anthropic/v1", "test-key", ApiStyle.ANTHROPIC)
        assertEquals("https://api.deepseek.com/models", official.url.toString())
        assertEquals("Bearer test-key", official.header("Authorization"))
    }

    // ── 模型能力表 ────────────────────────────────────────

    @Test
    fun `DeepSeek 官方仅两个模型且能力正确`() {
        val models = ModelCatalog.knownModels(ProviderKind.DEEPSEEK)
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), models.map { it.id })
        // 实测：/models 只返回这两个；且只有 flash 支持识图
        assertTrue(models.first { it.id == "deepseek-flash" }.supportsVision)
        assertTrue(!models.first { it.id == "deepseek-v4-pro" }.supportsVision)
        assertTrue(models.all { it.supportsThinking })
    }

    @Test
    fun `未知模型回落到保守默认能力`() {
        val info = ModelCatalog.find(ProviderKind.CUSTOM, "some-unknown-model")
        assertEquals("some-unknown-model", info.id)
        assertTrue(!info.supportsVision)
        assertTrue(!info.supportsThinking)
    }

    @Test
    fun `远程模型列表与能力表合并时保留已知能力`() {
        val merged = ModelCatalog.merge(
            ProviderKind.OPENCODE_GO,
            listOf("deepseek-v4-flash", "brand-new-model"),
        )
        assertEquals(2, merged.size)
        // 已知模型保留能力
        assertTrue(merged.first { it.id == "deepseek-v4-flash" }.supportsThinking)
        assertEquals(1_000_000, merged.first { it.id == "deepseek-v4-flash" }.contextWindow)
        // 未知模型使用保守默认
        assertEquals("brand-new-model", merged[1].id)
        assertTrue(!merged[1].supportsVision)
    }

    @Test
    fun `远程列表为空时回落到已知目录`() {
        val merged = ModelCatalog.merge(ProviderKind.DEEPSEEK, emptyList())
        assertEquals(2, merged.size)
    }

    // ── 设置页「模型」选择器 ──────────────────────────────

    @Test
    fun `自定义端点拉到的模型按服务端顺序全部进选择器`() {
        val models = ModelCatalog.pickerModels(
            ProviderKind.CUSTOM,
            listOf("gpt-5-mini", "gpt-5", "gpt-5-mini"),
        )
        assertEquals(listOf("gpt-5-mini", "gpt-5"), models.map { it.id })
    }

    @Test
    fun `远端列表后面补上内置目录且不重复`() {
        val models = ModelCatalog.pickerModels(
            ProviderKind.DEEPSEEK,
            listOf("deepseek-v4-pro"),
        )
        // 远端在前，内置表里没被覆盖的补在后面
        assertEquals(listOf("deepseek-v4-pro", "deepseek-flash"), models.map { it.id })
    }

    @Test
    fun `没有远端列表时选择器就是内置目录`() {
        assertEquals(2, ModelCatalog.pickerModels(ProviderKind.DEEPSEEK, emptyList()).size)
        assertTrue(ModelCatalog.pickerModels(ProviderKind.CUSTOM, emptyList()).isEmpty())
    }

    // ── 第三方端点上的能力表 ──────────────────────────────

    @Test
    fun `第三方端点上的已知模型仍然带上能力`() {
        // 中转站原样代理官方模型（截图里的 totokens.cc 就是），id 一模一样
        val info = ModelCatalog.find(ProviderKind.CUSTOM, "deepseek-flash")
        assertEquals(1_000_000, info.contextWindow)
        assertTrue(info.supportsVision)
        assertTrue(info.supportsThinking)
    }

    @Test
    fun `当前服务商自己的能力记录优先于别的表`() {
        // OpenCode Go 那张表实测 deepseek-flash 不支持识图，不能被 DeepSeek 官方那条盖掉
        assertTrue(!ModelCatalog.find(ProviderKind.OPENCODE_GO, "deepseek-flash").supportsVision)
        assertTrue(ModelCatalog.find(ProviderKind.CUSTOM, "deepseek-flash").supportsVision)
    }

    @Test
    fun `完全未知的模型仍用保守默认值`() {
        val info = ModelCatalog.find(ProviderKind.CUSTOM, "totally-unknown-model")
        assertEquals(ModelInfo.DEFAULT_CONTEXT_WINDOW, info.contextWindow)
        assertTrue(!info.supportsVision)
        assertTrue(!info.supportsThinking)
    }

    @Test
    fun `选择器里的第三方模型也带上全局能力`() {
        val model = ModelCatalog.pickerModels(ProviderKind.CUSTOM, listOf("glm-5.2")).single()
        assertEquals(1_000_000, model.contextWindow)
    }
}
