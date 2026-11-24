package com.simplechat.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公式切分。
 *
 * 切错的两种表现都很难定位：**代码块里的 `$PATH` 把后面整篇正文吞进公式**，
 * 或者金额 `$5 和 $10` 被当成公式渲染成一片乱码。所以边界逐条钉。
 */
class MathSplitTest {

    private fun segments(content: String) = splitMath(content)

    @Test
    fun `纯文本原样返回单段`() {
        val result = segments("雨是从傍晚开始下的。")
        assertEquals(1, result.size)
        assertEquals(ContentSegment.Text("雨是从傍晚开始下的。"), result.single())
    }

    @Test
    fun `行间公式切成公式段`() {
        val result = segments("前\n$$\na^2 + b^2 = c^2\n$$\n后")
        assertEquals(3, result.size)
        val formula = result[1] as ContentSegment.Formula
        assertEquals("a^2 + b^2 = c^2", formula.tex)
        assertTrue(formula.display)
        assertEquals(ContentSegment.Text("前\n"), result[0])
        assertEquals(ContentSegment.Text("\n后"), result[2])
    }

    @Test
    fun `反斜杠方括号也是行间公式`() {
        val result = segments("\\[\\frac{1}{2}\\]")
        assertEquals(1, result.size)
        val formula = result.single() as ContentSegment.Formula
        assertEquals("\\frac{1}{2}", formula.tex)
        assertTrue(formula.display)
    }

    @Test
    fun `行内公式`() {
        val result = segments("面积是 $\\pi r^2$ 左右")
        assertEquals(3, result.size)
        val formula = result[1] as ContentSegment.Formula
        assertEquals("\\pi r^2", formula.tex)
        assertTrue(!formula.display)
    }

    @Test
    fun `反斜杠圆括号也是行内公式`() {
        val result = segments("设 \\(x=1\\) 即可")
        val formula = result[1] as ContentSegment.Formula
        assertEquals("x=1", formula.tex)
        assertTrue(!formula.display)
    }

    @Test
    fun `金额不被当成公式`() {
        // 闭引号前面是空格 → 不成对
        val result = segments("苹果 $5 和香蕉 $10")
        assertEquals(1, result.size)
        assertTrue(result.single() is ContentSegment.Text)
    }

    @Test
    fun `开引号后紧跟空白不算公式`() {
        val result = segments("价格 $ 100 $ 元")
        assertEquals(1, result.size)
    }

    @Test
    fun `没闭合的公式按纯文本`() {
        val result = segments("算一下 $$\na+b")
        assertEquals(1, result.size)
        assertTrue(result.single() is ContentSegment.Text)
    }

    @Test
    fun `围栏代码块里的美元号不切`() {
        val content = """
            看看这段：

            ```
            echo ${'$'}PATH 和 ${'$'}HOME
            ```

            完毕。
        """.trimIndent()
        val result = segments(content)
        assertEquals(1, result.size)
        assertTrue(result.single() is ContentSegment.Text)
    }

    @Test
    fun `行内代码里的美元号不切`() {
        val result = segments("用 `echo \$HOME` 就行")
        assertEquals(1, result.size)
        assertTrue(result.single() is ContentSegment.Text)
    }

    @Test
    fun `多个公式交替切分`() {
        val result = segments("\$a\$\$b\$")
        // `$a$` 与 `$b$` 相邻
        assertEquals(2, result.size)
        assertEquals("a", (result[0] as ContentSegment.Formula).tex)
        assertEquals("b", (result[1] as ContentSegment.Formula).tex)
    }

    @Test
    fun `空正文返回空列表`() {
        assertTrue(segments("").isEmpty())
    }

    @Test
    fun `没有公式标记时不做多余工作`() {
        val result = segments("一段没有公式的很长的正文。".repeat(50))
        assertEquals(1, result.size)
    }

    @Test
    fun `公式两侧斜杠转义的美元号不被当成闭合`() {
        val result = segments("""$\$5$""")
        val formula = result.single() as ContentSegment.Formula
        assertEquals("""\$5""", formula.tex)
    }

    @Test fun codeAndLinksDoNotContainMath() {
        val source = "````sh\necho \$x\$\n```\n\$y\$\n````\n\n[label](https://example.invalid/\$price\$) and \$z\$"
        val segments = splitMath(source)
        assertEquals(listOf("z"), segments.filterIsInstance<ContentSegment.Formula>().map { it.tex })
    }

    @Test fun incompleteDisplayDoesNotSwallowLaterParagraphs() {
        val source = "\$\$unfinished\n\nprose\n\n\$\$x^2\$\$"
        assertEquals(listOf("x^2"), splitMath(source).filterIsInstance<ContentSegment.Formula>().map { it.tex })
    }

    @Test fun pricesAndCrossLineInlineMathStayLiteral() {
        for (source in listOf("From \$5-\$10", "\$first\r\nlast\$", "\$first\\\nlast\$")) {
            assertEquals(listOf(ContentSegment.Text(source)), splitMath(source))
        }
    }

    @Test fun standaloneAmsEnvironmentsAreNativeDisplayMath() {
        val align = "\\begin{align*}\na&=b \\\\\nc&=d\n\\end{align*}"
        val formula = displayMathSegments("前\n$align\n后").filterIsInstance<ContentSegment.Formula>().single()
        assertTrue(formula.display)
        assertEquals(align, formula.source)
        assertTrue(prepareLatexPresentation(formula.tex).canRender)
        assertTrue(displayMathSegments("```tex\n$align\n```").none { it is ContentSegment.Formula })
    }

    @Test fun explicitInlineMathCanWrapInTheSourceWithoutSwallowingLaterParagraphs() {
        val formula = "\\(\\Delta = 144m^2 - 40(3m^2+6) > 0\n\\Rightarrow m^2 > 10\\)"
        val source = "先计算 $formula。\n\n未闭合 \\(a+b\n\n下一段 \\(x^2\\)。"
        val document = inlineMathDocument(source)
        assertEquals(listOf(formula, "\\(x^2\\)"), document.formulas.values.map { it.source })
        assertEquals(source, document.restore(document.markdown))
        assertTrue(document.formulas.values.all { prepareLatexPresentation(it.tex).canRender })
        assertEquals(listOf("x"), splitMath("\\(unclosed\n\\(x\\)").filterIsInstance<ContentSegment.Formula>().map { it.tex })
    }

    @Test fun standaloneMatricesAndAmsAlignmentsUseTheMathRenderer() {
        for (body in listOf(
            """\begin{pmatrix} a&b \\ c&d \end{pmatrix}""",
            """\begin{alignedat}{2} a&=b & c&=d \end{alignedat}""",
            """\begin{split} a&=b \\ &=c \end{split}""",
            """\begin{dcases} a & x>0 \\ b & x\leq0 \end{dcases}""",
        )) {
            val formula = displayMathSegments("前\n$body\n后").filterIsInstance<ContentSegment.Formula>().single()
            assertTrue(formula.display)
            assertEquals(body, formula.source)
            assertTrue(body, prepareLatexPresentation(formula.tex).canRender)
        }
    }

    @Test fun sameLineBracketMathDoesNotBreakATableRow() {
        val source = "| 前 \\[|x|\\] 后 | y |"
        val formula = splitMath(source).filterIsInstance<ContentSegment.Formula>().single()
        assertTrue(!formula.display)
        assertEquals("|x|", formula.tex)
    }
}
