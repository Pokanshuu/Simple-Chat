package com.simplechat.app.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索片段的取窗口逻辑。
 *
 * 这一层错了，表现是"关键词明明在消息里，结果行却什么都没高亮" ——
 * 从界面上看不出是取窗口写错了。所以把边界情况钉死。
 */
class SearchSnippetTest {

    @Test
    fun `match in the middle is centred with ellipses on both sides`() {
        val content = "前".repeat(50) + "关键词" + "后".repeat(50)

        val snippet = snippetAround(content, "关键词", radius = 5)

        assertEquals("…前前前前前关键词后后后后后…", snippet)
    }

    @Test
    fun `match at the start has no leading ellipsis`() {
        val content = "关键词" + "后".repeat(50)

        val snippet = snippetAround(content, "关键词", radius = 5)

        assertEquals("关键词后后后后后…", snippet)
        assertTrue("开头不该有省略号", !snippet.startsWith("…"))
    }

    @Test
    fun `match at the end has no trailing ellipsis`() {
        val content = "前".repeat(50) + "关键词"

        val snippet = snippetAround(content, "关键词", radius = 5)

        assertEquals("…前前前前前关键词", snippet)
        assertTrue("结尾不该有省略号", !snippet.endsWith("…"))
    }

    @Test
    fun `newlines are flattened into single spaces`() {
        val content = "第一行\n\n第二行\r\n第三行"

        val snippet = snippetAround(content, "第三行", radius = 50)

        assertEquals("第一行 第二行 第三行", snippet)
    }

    @Test
    fun `search is case insensitive`() {
        val content = "The KINGDOM of something"

        val snippet = snippetAround(content, "kingdom", radius = 10)

        assertTrue(snippet.contains("KINGDOM"))
    }

    @Test
    fun `falls back to the head when the query is not found`() {
        // 标题命中、正文没命中时会走这条路
        val content = "一二三四五六七八九十"

        val snippet = snippetAround(content, "找不到", radius = 3)

        assertEquals("一二三四五六", snippet)
    }

    @Test
    fun `blank query just takes the head`() {
        val snippet = snippetAround("一二三四五", "", radius = 2)

        assertEquals("一二三四", snippet)
    }

    @Test
    fun `short content is returned whole`() {
        assertEquals("很短", snippetAround("很短", "短", radius = 50))
    }

    @Test
    fun `null or blank snippet is not worth displaying`() {
        assertEquals(null, snippetForDisplay(null, "x"))
        assertEquals(null, snippetForDisplay("   ", "x"))
    }

    @Test
    fun `a real snippet is windowed`() {
        val content = "前".repeat(40) + "命中" + "后".repeat(40)

        val snippet = snippetForDisplay(content, "命中", radius = 3)

        assertEquals("…前前前命中后后后…", snippet)
    }
}
