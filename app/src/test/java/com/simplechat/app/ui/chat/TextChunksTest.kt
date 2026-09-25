package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长文本分块。
 *
 * 最要紧的一条不变量是 **`chunks.joinToString("") == 原文`** ——
 * 块之间是分开渲染的，丢一个字符、多一个换行，界面上都会看出来
 * （多空行、句子被吃掉一截）。其余是切点选得合不合理。
 */
class TextChunksTest {

    @Test
    fun `short text stays in one piece`() {
        val text = "只有一行"
        assertEquals(listOf(text), textChunks(text))
    }

    @Test
    fun `empty text yields no chunks`() {
        assertEquals(emptyList<String>(), textChunks(""))
    }

    @Test
    fun `concatenation is always identical to the original`() {
        // 这是唯一不能破的不变量
        val cases = listOf(
            "短",
            "a\nb\nc",
            "一".repeat(5000),
            ("段落一\n\n段落二\n\n段落三\n").repeat(400),
            "没有换行的超长单行".repeat(900),
            "混合\ntext with\n中英文 mixed\n".repeat(300),
        )
        for (text in cases) {
            val joined = textChunks(text).joinToString("")
            assertEquals("分块后内容变了（长度 ${text.length}）", text, joined)
        }
    }

    @Test
    fun `no chunk exceeds the limit`() {
        val text = "没有换行的超长单行".repeat(900)
        for (chunk in textChunks(text, maxChars = 2000)) {
            assertTrue("有块超过上限：${chunk.length}", chunk.length <= 2000)
        }
    }

    @Test
    fun `splits at a newline when there is one nearby`() {
        // 每行 10 字，上限 25 → 应该在换行处断，而不是硬切到 25
        val text = (1..10).joinToString("\n") { "第${it}行内容占十个字" }
        val chunks = textChunks(text, maxChars = 25)
        assertTrue("块数应该大于 1", chunks.size > 1)
        // 除了最后一块，其余都应以换行结尾（说明是在换行处断的）
        chunks.dropLast(1).forEach {
            assertTrue("没有在换行处断开：'${it.takeLast(5)}'", it.endsWith("\n"))
        }
    }

    @Test
    fun `hard splits a single line with no newline`() {
        val text = "x".repeat(100)
        val chunks = textChunks(text, maxChars = 30)
        assertEquals(4, chunks.size)
        assertEquals(listOf(30, 30, 30, 10), chunks.map { it.length })
    }

    @Test
    fun `realistic long message is split into a sane number of pieces`() {
        // 21 万字（用户实测能复现 bug 的量级）
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(4500)
        val chunks = textChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue("块数不合理：${chunks.size}", chunks.size in 50..200)
    }
}
