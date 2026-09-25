package com.simplechat.app.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docx 正文抽取。
 *
 * 这里的两个坑都不会报错，只会"读起来不对"：
 * 段落标记被当成标签剥掉（整篇挤成一坨）、实体还原顺序颠倒（凭空多出尖括号）。
 */
class DocxTextTest {

    private fun docx(vararg paragraphs: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        paragraphs.forEach { append(it) }
        append("</w:body></w:document>")
    }

    private fun para(vararg runs: String) = "<w:p>${runs.joinToString("")}</w:p>"

    private fun run(text: String) = "<w:r><w:t>$text</w:t></w:r>"

    @Test
    fun `turns paragraphs into lines`() {
        val xml = docx(para(run("第一段")), para(run("第二段")))

        assertEquals("第一段\n第二段", docxXmlToText(xml))
    }

    @Test
    fun `joins runs inside one paragraph without a newline`() {
        // 同一段里被拆成多个 run 是很常见的（拼写检查、格式变化都会拆）
        val xml = docx(para(run("前半"), run("后半")))

        assertEquals("前半后半", docxXmlToText(xml))
    }

    @Test
    fun `keeps word-level structure across page-level junk`() {
        val xml = docx(
            para(run("标题")),
            para(run("正文一")),
            para(run("正文二")),
        )

        assertEquals("标题\n正文一\n正文二", docxXmlToText(xml))
    }

    @Test
    fun `collapses runs of blank paragraphs`() {
        val xml = docx(para(run("上")), para(""), para(""), para(""), para(run("下")))

        // 四个空段不该在正文里产出一大片空白
        assertEquals("上\n\n下", docxXmlToText(xml))
    }

    @Test
    fun `restores xml entities with the correct precedence`() {
        // 关键用例：&amp;lt; 表示的是**字面量** &lt;，不是 "<"
        val xml = docx(para(run("a &amp;lt; b &amp;amp; c &amp;gt; d")))

        assertEquals("a &lt; b &amp; c &gt; d", docxXmlToText(xml))
    }

    @Test
    fun `handles line breaks and tabs inside a paragraph`() {
        val xml = docx("<w:p><w:r><w:t>上</w:t><w:br/><w:t>下</w:t><w:tab/><w:t>右</w:t></w:r></w:p>")

        assertEquals("上\n下\t右", docxXmlToText(xml))
    }

    @Test
    fun `ignores formatting-only tags`() {
        val xml = docx(
            """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>加粗</w:t></w:r></w:p>""",
        )

        assertEquals("加粗", docxXmlToText(xml))
    }

    @Test
    fun `trims trailing whitespace that xml indentation leaves behind`() {
        val xml = docx(para(run("正文   ")), para(run(" 下一段")))

        assertEquals("正文\n 下一段", docxXmlToText(xml))
    }

    @Test
    fun `empty document yields empty text`() {
        assertTrue(docxXmlToText(docx()).isEmpty())
    }
}
