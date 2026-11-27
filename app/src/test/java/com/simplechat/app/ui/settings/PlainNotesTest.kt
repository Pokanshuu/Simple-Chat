package com.simplechat.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * release notes 的"去 Markdown"。
 *
 * 这个函数只负责让说明能读，不追求渲染 —— 所以测试钉的是"别把内容吃掉"：
 * 链接要留下文字、标题井号要去掉、正文里的 `[1]` 这类引用不能被误伤。
 */
class PlainNotesTest {

    @Test
    fun `链接只留文字`() {
        val input = "- [@hongkai233](https://github.com/hongkai233) 贡献了语音，见 [PR #10](https://x/y)。"
        val output = plainNotes(input)
        assertTrue(output.contains("@hongkai233 贡献了语音"))
        assertTrue(output.contains("PR #10"))
        assertFalse(output.contains("https://"))
    }

    @Test
    fun `标题井号去掉但保留文字`() {
        assertEquals("下载与校验\n正文", plainNotes("## 下载与校验\n正文"))
    }

    @Test
    fun `加粗星号去掉`() {
        assertEquals("重点在这", plainNotes("**重点在这**"))
    }

    @Test
    fun `普通方括号不被当成链接`() {
        // 没有 "(url)" 的方括号是正文，不能删
        assertEquals("见 [1] 和 [2]", plainNotes("见 [1] 和 [2]"))
    }

    @Test
    fun `空内容不炸`() {
        assertEquals("", plainNotes(""))
        assertEquals("", plainNotes("   \n  "))
    }
}
