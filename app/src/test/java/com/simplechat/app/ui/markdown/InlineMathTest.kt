package com.simplechat.app.ui.markdown

import org.junit.Assert.*
import org.junit.Test

class InlineMathTest {
    @Test fun `inline math keeps quote markers paragraphs and punctuation together`() {
        val source = "> 解：对于任意给定的 \\(\\varepsilon > 0\\)，\n> 要使 \\(|a_n|<\\varepsilon\\)，即 \\(\\frac{2}{n}<\\varepsilon\\)。"
        val result = inlineMathDocument(source)
        assertEquals(3, result.formulas.size)
        assertEquals(2, result.markdown.count { it == '>' })
        assertFalse(result.markdown.contains("\n\n"))
        assertTrue(result.markdown.contains("，即 `"))
        assertTrue(result.markdown.endsWith("`。"))
    }
    @Test fun `code escaped currency and incomplete formula remain source`() {
        for (source in listOf("`\$x\$`", "```sh\necho \$PATH\n```", "price \\\$5 and \\\$10", "half \\(x")) {
            val result = inlineMathDocument(source)
            assertEquals(source, result.markdown)
            assertTrue(result.formulas.isEmpty())
        }
    }
    @Test fun `ordinary code token cannot collide with generated formula`() {
        val result = inlineMathDocument("`SCMATH0` and \\(x+1\\)")
        assertEquals(setOf("SCMATHX0"), result.formulas.keys)
        assertTrue(result.markdown.startsWith("`SCMATH0`"))
    }
}
