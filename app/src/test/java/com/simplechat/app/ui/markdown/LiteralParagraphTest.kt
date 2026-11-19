package com.simplechat.app.ui.markdown

import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteralParagraphTest {
    private fun eligible(text: String): Boolean {
        val state = parseMarkdown(text) as State.Success
        return isLiteralParagraph(text, state.node)
    }

    @Test fun `ordinary English and CJK prose can fade`() {
        for (text in listOf("Hello, world.", "Short answer: yes.", "这是一个例子，适合 Android 用户。", "👍 你好！")) {
            assertTrue(text, eligible(text))
        }
    }

    @Test fun `formatted or transformed source uses Markdown rendering`() {
        for (text in listOf("**bold**", "# Title", "- item", "> quote", "`code`", "[link](https://example.com)",
            "first\nsecond", "\\*literal\\*", "a &amp; b", "![image](img.png)", "    indented")) {
            assertFalse(text, eligible(text))
        }
    }
}
