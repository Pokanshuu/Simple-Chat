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
    @Test fun `Chinese prose keeps both dollar styles inline and renders screenshot formulas`() {
        val source = "因为矩阵 \$\$ A \\ne O \$\$，讨论其行列式 \$|A|\$ 的取值。假设 \$\$|A|=0\$\$，则 \$A^*=-A^T\$。"
        assertEquals(listOf(ContentSegment.Text(source)), displayMathSegments(source))
        val document = inlineMathDocument(source)
        val tex = document.formulas.values.map { it.tex }
        assertEquals(listOf("A \\ne O", "|A|", "|A|=0", "A^*=-A^T"), tex)
        tex.forEach { assertNotNull(it, prepareLatexPresentation(it).nativeTex) }
        assertEquals("因为矩阵 `SCMATH0`，讨论其行列式 `SCMATH1` 的取值。假设 `SCMATH2`，则 `SCMATH3`。", document.markdown)
    }

    @Test fun `adjacent formulas and emphasis survive the Markdown parser`() {
        val document = inlineMathDocument("**设\$x\$\$y\$成立**")
        val ast = org.intellij.markdown.parser.MarkdownParser(org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor())
            .buildMarkdownTreeFromString(document.markdown)
        fun nodes(node: org.intellij.markdown.ast.ASTNode): Sequence<org.intellij.markdown.ast.ASTNode> =
            sequenceOf(node) + node.children.asSequence().flatMap(::nodes)
        val types = org.intellij.markdown.MarkdownElementTypes
        val code = nodes(ast).single { it.type == types.CODE_SPAN }
        assertEquals("`SCMATH0 SCMATH1`", document.markdown.substring(code.startOffset, code.endOffset))
        assertTrue(nodes(ast).any { it.type == types.STRONG })
        assertEquals(listOf("x", "y"), document.formulas.values.map { it.tex })
    }

    @Test fun `ordinary code token cannot collide with generated formula`() {
        val result = inlineMathDocument("`SCMATH0` and \\(x+1\\)")
        assertEquals(setOf("SCMATHX0"), result.formulas.keys)
        assertTrue(result.markdown.startsWith("`SCMATH0`"))
    }

    @Test fun normalizationPreservesTexThatResemblesHtmlOrMarkdown() {
        val source = "<b>条件</b> \\(a<b>c\\) 与 \$|a|_1\$。"
        val document = normalizedMarkdownDocument(source)
        assertEquals(listOf("a<b>c", "|a|_1"), document.formulas.values.map { it.tex })
        assertEquals("**条件** \\(a<b>c\\) 与 \$|a|_1\$。", document.restore(document.markdown))
    }
}
