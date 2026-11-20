package com.simplechat.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingLinkBoundariesTest {
    @Test
    fun `no network split or animation frame exposes a partial link`() {
        val before = "正文 "
        val after = "继续"
        for (link in listOf("[12](https://example.org/a%20b)", "[文档](https://host/a_(b))",
                "[带有 [括号] 的标题](<path with spaces> \"title )\")", "[1](#cite-1)")) {
            val text = before + link + after
            val linkEnd = before.length + link.length
            for (cut in 0..text.length) {
                val plan = streamingLinkBoundaries(text.take(cut))
                val visibleEnd = if (cut in before.length until linkEnd) before.length else cut
                assertEquals("network split $cut in $link", visibleEnd, plan.visibleEnd)
                for (frame in 0..visibleEnd) {
                    val expected = if (frame in (before.length + 1) until linkEnd) linkEnd else frame
                    assertEquals("frame $frame in $link", expected, plan.completeThrough(frame))
                }
            }
        }
    }

    @Test
    fun `adjacent citations can appear on successive frames`() {
        val one = "[1](url)"
        val both = one + "[2](url2)"
        val plan = streamingLinkBoundaries(both)
        assertEquals(one.length, plan.completeThrough(1))
        assertEquals(one.length, plan.completeThrough(one.length))
        assertEquals(both.length, plan.completeThrough(one.length + 1))
    }

    @Test
    fun `literal brackets are released once disambiguated and code streams literally`() {
        for (literal in listOf("文字[附注]。", "[待补\n下一行", "\\[1](url)", "`[1](url)`",
                "~~~md\n[1](url)\n~~~", "[1](url ordinary text")) {
            val plan = streamingLinkBoundaries(literal)
            assertEquals(literal, literal.length, plan.visibleEnd)
            for (frame in 0..literal.length) assertEquals(literal, frame, plan.completeThrough(frame))
        }
    }
}
