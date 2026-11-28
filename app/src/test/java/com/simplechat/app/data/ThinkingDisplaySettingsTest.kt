package com.simplechat.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考展示策略（docs/42 §52.4–§52.7）。
 *
 * 这里钉死三类纯规则：默认值/迁移、两阶段展开判定、手动覆盖优先级。
 * 行为一旦算错，用户看到的就是"设置没生效"或"手点开的又被自动收回去"。
 */
class ThinkingDisplaySettingsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── 默认值与迁移 ─────────────────────────────────────

    @Test
    fun `默认关闭且与旧版行为一致`() {
        val settings = ThinkingDisplaySettings()
        assertFalse(settings.expand)
        assertEquals(ThinkingDuring.PARTIAL, settings.effectiveDuring())
        assertEquals(ThinkingAfter.COLLAPSE, settings.effectiveAfter())
    }

    @Test
    fun `打开后的默认是全部展开加保持展开`() {
        val settings = ThinkingDisplaySettings(expand = true)
        assertEquals(ThinkingDuring.FULL, settings.effectiveDuring())
        assertEquals(ThinkingAfter.EXPAND, settings.effectiveAfter())
    }

    @Test
    fun `总开关关闭时不看子项`() {
        val settings = ThinkingDisplaySettings(
            expand = false,
            during = ThinkingDuring.FULL,
            after = ThinkingAfter.EXPAND,
        )
        assertEquals(ThinkingDuring.PARTIAL, settings.effectiveDuring())
        assertEquals(ThinkingAfter.COLLAPSE, settings.effectiveAfter())
    }

    @Test
    fun `旧键缺省或开启时迁移到新默认`() {
        assertEquals(ThinkingDisplaySettings(), ThinkingDisplaySettings.fromLegacyAutoCollapse(null))
        assertEquals(ThinkingDisplaySettings(), ThinkingDisplaySettings.fromLegacyAutoCollapse(true))
    }

    @Test
    fun `旧键关闭自动折叠时迁移为部分加保持展开`() {
        val migrated = ThinkingDisplaySettings.fromLegacyAutoCollapse(false)
        assertTrue(migrated.expand)
        assertEquals(ThinkingDuring.PARTIAL, migrated.during)
        assertEquals(ThinkingAfter.EXPAND, migrated.after)
        // 与旧行为一致：生成中仍是尾巴，结束后保持全文。
        assertEquals(ThinkingDuring.PARTIAL, migrated.effectiveDuring())
        assertEquals(ThinkingAfter.EXPAND, migrated.effectiveAfter())
    }

    // ── 展开判定 ─────────────────────────────────────────

    @Test
    fun `关闭时生成中与收口后都不展开`() {
        val policy = ThinkingDisplaySettings()
        assertFalse(thinkingExpandedByDefault(reasoningStreaming = true, policy = policy))
        assertFalse(thinkingExpandedByDefault(reasoningStreaming = false, policy = policy))
    }

    @Test
    fun `生成中只看 during 阶段策略`() {
        val full = ThinkingDisplaySettings(expand = true, during = ThinkingDuring.FULL)
        val partial = ThinkingDisplaySettings(expand = true, during = ThinkingDuring.PARTIAL)
        assertTrue(thinkingExpandedByDefault(reasoningStreaming = true, policy = full))
        assertFalse(thinkingExpandedByDefault(reasoningStreaming = true, policy = partial))
    }

    @Test
    fun `收口后只看 after 阶段策略`() {
        val keep = ThinkingDisplaySettings(expand = true, after = ThinkingAfter.EXPAND)
        val collapse = ThinkingDisplaySettings(expand = true, after = ThinkingAfter.COLLAPSE)
        assertTrue(thinkingExpandedByDefault(reasoningStreaming = false, policy = keep))
        assertFalse(thinkingExpandedByDefault(reasoningStreaming = false, policy = collapse))
    }

    @Test
    fun `手动覆盖优先于任何策略`() {
        val collapseAll = ThinkingDisplaySettings()
        val expandAll = ThinkingDisplaySettings(expand = true)
        assertTrue(resolveThinkingExpanded(override = true, reasoningStreaming = false, policy = collapseAll))
        assertFalse(resolveThinkingExpanded(override = false, reasoningStreaming = true, policy = expandAll))
    }

    @Test
    fun `没有覆盖时跟随策略`() {
        val expandAll = ThinkingDisplaySettings(expand = true)
        assertTrue(resolveThinkingExpanded(override = null, reasoningStreaming = true, policy = expandAll))
        assertFalse(resolveThinkingExpanded(override = null, reasoningStreaming = true, policy = ThinkingDisplaySettings()))
    }

    // ── 展开动画阈值 ─────────────────────────────────────

    @Test
    fun `短内容展开走动画`() {
        assertTrue(shouldAnimateThinkingExpand(charCount = 120, contentHeightPx = 600, viewportHeightPx = 2000))
    }

    @Test
    fun `长文本或超过一屏的内容瞬时展开`() {
        assertFalse(shouldAnimateThinkingExpand(charCount = 4000, contentHeightPx = 600, viewportHeightPx = 2000))
        assertFalse(shouldAnimateThinkingExpand(charCount = 120, contentHeightPx = 2000, viewportHeightPx = 2000))
    }

    // ── 序列化 ───────────────────────────────────────────

    @Test
    fun `JSON 往返保留两阶段选项`() {
        val settings = ThinkingDisplaySettings(
            expand = true,
            during = ThinkingDuring.PARTIAL,
            after = ThinkingAfter.COLLAPSE,
        )
        val raw = json.encodeToString(ThinkingDisplaySettings.serializer(), settings)
        assertEquals(settings, json.decodeFromString(ThinkingDisplaySettings.serializer(), raw))
    }

    @Test
    fun `缺失字段回落到默认值`() {
        assertEquals(ThinkingDisplaySettings(), json.decodeFromString(ThinkingDisplaySettings.serializer(), "{}"))
    }

    @Test
    fun `未来字段不影响解码`() {
        val decoded = json.decodeFromString(
            ThinkingDisplaySettings.serializer(),
            """{"expand":true,"during":"PARTIAL","futureOption":3}""",
        )
        assertTrue(decoded.expand)
        assertEquals(ThinkingDuring.PARTIAL, decoded.during)
    }
}
