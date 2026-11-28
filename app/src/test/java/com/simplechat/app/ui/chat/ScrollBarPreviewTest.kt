package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动条拖动胶囊的文本提取（docs/41 §51.5.5 / §51.5.9）。
 *
 * 纯函数：不碰 Android Resources —— labels 由测试注入固定文案。
 */
class ScrollBarPreviewTest {

    private val labels = ScrollBarPreviewLabels(
        thinking = { "思考中…" },
        streaming = { "生成中…" },
        failed = { "发送失败" },
        stopped = { "已停止" },
        imageGenerating = { "正在生成图片…" },
        deletedBranch = { "（分支已删除）" },
        empty = { "（无内容）" },
    )

    private fun message(
        content: String = "",
        reasoning: String? = null,
        streaming: Boolean = false,
        status: MessageStatus = if (streaming) MessageStatus.STREAMING else MessageStatus.DONE,
    ) = UiMessage(
        id = "m1",
        role = MessageRole.ASSISTANT,
        content = content,
        reasoning = reasoning,
        status = status,
    )

    @Test
    fun `takes the first non-empty line`() {
        assertEquals("第一行", firstLinePreview("\n\n第一行\n第二行"))
    }

    @Test
    fun `strips markdown markers from the first line`() {
        assertEquals("标题", firstLinePreview("## 标题\n正文"))
        assertEquals("引用", firstLinePreview("> 引用"))
        assertEquals("列表项", firstLinePreview("- 列表项"))
        assertEquals("代码", firstLinePreview("```代码```"))
    }

    @Test
    fun `truncates by code points not chars`() {
        // 每个 emoji 是 2 个 UTF-16 char，截 13 个码点不能切出半个代理对
        val emoji = "😀".repeat(20)
        val cut = truncateCodePoints(emoji, 13)
        assertEquals(13, cut.codePointCount(0, cut.length))
        assertEquals(26, cut.length)
    }

    @Test
    fun `first line preview defaults to 13 code points`() {
        val text = "一二三四五六七八九十甲乙丙丁戊"
        val preview = firstLinePreview(text)
        assertEquals("一二三四五六七八九十甲乙丙", preview)
    }

    @Test
    fun `empty content falls back to the empty label`() {
        assertEquals("（无内容）", scrollBarPreview(message(content = "   "), labels))
    }

    @Test
    fun `status labels win over content`() {
        assertEquals("发送失败", scrollBarPreview(message(content = "正文", status = MessageStatus.ERROR), labels))
        assertEquals("已停止", scrollBarPreview(message(content = "正文", status = MessageStatus.STOPPED), labels))
    }

    @Test
    fun `streaming states use their own wording`() {
        assertEquals(
            "思考中…",
            scrollBarPreview(message(content = "", reasoning = "思考", streaming = true), labels),
        )
        assertEquals(
            "生成中…",
            scrollBarPreview(message(content = "", streaming = true), labels),
        )
        // 已经有正文的流式消息优先显示正文，而不是"生成中…"
        assertEquals(
            "已经写的内容",
            scrollBarPreview(message(content = "已经写的内容", streaming = true), labels),
        )
        assertTrue(scrollBarPreview(message(content = "正文", streaming = true), labels).isNotEmpty())
    }

    @Test
    fun `deleted branch placeholder`() {
        val deleted = UiMessage(
            id = "m2",
            role = MessageRole.ASSISTANT,
            content = "",
            deletedNavigation = true,
        )
        assertEquals("（分支已删除）", scrollBarPreview(deleted, labels))
    }
}
