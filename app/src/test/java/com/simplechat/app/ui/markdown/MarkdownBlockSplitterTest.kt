package com.simplechat.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式渲染的分块器。
 *
 * 这一层错了有两种表现，都不好排查：
 * - **切错**：同一段内容被拆成几块 → 列表重新从 1 开始、表格散架、代码被当正文；
 * - **切不出稳定 key**：前面的块跟着尾块一起变 → 分块白做，性能一点没省。
 *
 * 所以除了"切在哪"，还要钉死"**追加内容时前面的块一字不变**"这条性质 ——
 * 那正是省下解析量的全部依据。
 */
class MarkdownBlockSplitterTest {

    private fun split(source: String) = splitMarkdownBlocks(source)

    /** 不变式：每块都必须是原文的**连续子串**（块文本不增不删）。 */
    private fun assertSubstrings(source: String, blocks: List<MarkdownBlock>) {
        for (b in blocks) {
            val end = b.start + b.text.length
            assertTrue("块越界: start=${b.start} len=${b.text.length}", end <= source.length)
            assertEquals("块文本不是原文子串", b.text, source.substring(b.start, end))
        }
    }

    // ── 主用例：空行是边界 ─────────────────────────────────

    @Test
    fun `blank lines separate paragraphs`() {
        val src = "第一段。\n\n第二段。\n\n第三段。"
        val blocks = split(src)

        assertEquals(3, blocks.size)
        assertEquals(listOf("第一段。", "第二段。", "第三段。"), blocks.map { it.text })
        assertEquals(listOf(0, 6, 12), blocks.map { it.start })
        assertSubstrings(src, blocks)
    }

    @Test
    fun `single block when there is no blank line`() {
        val src = "只有一段，\n中间没有空行。"
        val blocks = split(src)

        assertEquals(1, blocks.size)
        assertEquals(src, blocks[0].text)
        assertEquals(0, blocks[0].start)
    }

    // ── 围栏：内部绝不切 ──────────────────────────────────

    @Test
    fun `code fence content is never split even with blank lines`() {
        val src = "看代码：\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\n就这样。"
        val blocks = split(src)

        assertEquals(3, blocks.size)
        assertEquals("看代码：", blocks[0].text)
        assertEquals("```kotlin\nval a = 1\n\nval b = 2\n```", blocks[1].text)
        assertEquals("就这样。", blocks[2].text)
        assertSubstrings(src, blocks)
    }

    @Test
    fun `unclosed fence swallows the rest into one block`() {
        // 流式最常见的形态：代码块还没写完。半截代码必须整块留着，
        // 不能被拆成"正文 + 代码 + 正文"。
        val src = "前言。\n\n```python\ndef f():\n    return 1\n\n还没写完"
        val blocks = split(src)

        assertEquals(2, blocks.size)
        assertEquals("前言。", blocks[0].text)
        assertTrue(blocks[1].text.startsWith("```python"))
        assertTrue(blocks[1].text.endsWith("还没写完"))
    }

    @Test
    fun `mismatched fence marker does not close the fence`() {
        val src = "```\n~~~\n不是围栏\n\n还在代码里\n```"
        val blocks = split(src)

        assertEquals(1, blocks.size)
        assertEquals(src, blocks[0].text)
    }

    // ── 同族合并：列表 / 表格 ─────────────────────────────

    @Test
    fun `loose list stays one block`() {
        // normalizeSoftBreaks 会在列表项之间补空行（松列表）。
        // 若这里不合并，会被拆成两个各自从 1 开始的列表。
        val src = "1. 第一项\n\n2. 第二项\n\n3. 第三项"
        val blocks = split(src)

        assertEquals(1, blocks.size)
        assertEquals(src, blocks[0].text)
    }

    @Test
    fun `table rows stay one block`() {
        val src = "| 名字 | 年龄 |\n\n|---|---|\n\n| 甲 | 20 |"
        val blocks = split(src)

        assertEquals(1, blocks.size)
        assertSubstrings(src, blocks)
    }

    @Test
    fun `list then paragraph are not merged`() {
        val src = "- 甲\n\n- 乙\n\n普通段落。"
        val blocks = split(src)

        assertEquals(2, blocks.size)
        assertEquals("- 甲\n\n- 乙", blocks[0].text)
        assertEquals("普通段落。", blocks[1].text)
    }

    @Test
    fun `paragraph then table are not merged`() {
        // GFM 的表格不能打断段落，紧贴正文的表格根本不会被解析成表格 ——
        // 所以正文与表格之间**要**分开，让 normalize 插的空行生效。
        val src = "表格如下：\n\n| a | b |\n\n|---|---|"
        val blocks = split(src)

        assertEquals(2, blocks.size)
        assertEquals("表格如下：", blocks[0].text)
        assertEquals("| a | b |\n\n|---|---|", blocks[1].text)
    }

    // ── 核心性质：追加内容时前面的块一字不变 ──────────────

    @Test
    fun `appending text keeps earlier blocks identical`() {
        val before = "第一段。\n\n第二段。\n\n第三段"
        val after = "第一段。\n\n第二段。\n\n第三段写完了。\n\n第四段开始"

        val b1 = split(before)
        val b2 = split(after)

        // 前面已完成的块：文本与 key 都必须原样（Compose 据此跳过重组）
        assertEquals(b1[0].text, b2[0].text)
        assertEquals(b1[1].text, b2[1].text)
        assertEquals(b1[0].key, b2[0].key)
        assertEquals(b1[1].key, b2[1].key)

        // 只有尾块在长
        assertEquals("第三段", b1[2].text)
        assertTrue(b2[2].text.startsWith("第三段"))
    }

    @Test
    fun `appending to an open fence keeps earlier blocks identical`() {
        val before = "前言。\n\n```kotlin\nval a ="
        val after = "前言。\n\n```kotlin\nval a = 1\nval b = 2"

        val b1 = split(before)
        val b2 = split(after)

        assertEquals(2, b1.size)
        assertEquals(2, b2.size)
        assertEquals(b1[0].key, b2[0].key)
        assertEquals(b1[1].key, b2[1].key)
        assertTrue(b2[1].text.endsWith("val b = 2"))
    }

    // ── 边界 ──────────────────────────────────────────────

    @Test
    fun `empty input yields no blocks`() {
        assertEquals(0, split("").size)
    }

    @Test
    fun `blank only input yields no blocks`() {
        assertEquals(0, split("\n\n\n").size)
    }

    @Test
    fun `keys are unique`() {
        val blocks = split("甲\n\n乙\n\n丙\n\n丁")
        assertEquals(blocks.size, blocks.map { it.key }.toSet().size)
    }
}
