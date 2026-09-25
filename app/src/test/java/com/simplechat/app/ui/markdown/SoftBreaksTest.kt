package com.simplechat.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 软换行 → 段落边界的规范化。
 *
 * 这一层错了，表现是"某一类内容渲染错乱"：段落糊成一坨、代码被拆散、
 * 表格散架、或者模型故意写的段内换行被硬拆成两段。
 * 种类多、又都只在长文里出现，所以把每条规则都钉死。
 */
class SoftBreaksTest {

    private fun normalize(input: String) = normalizeSoftBreaks(input)

    // ── 主用例：模型用单换行分段 ─────────────────────────────

    @Test
    fun `bare single newlines become paragraph breaks`() {
        // 实测：让模型"用单换行分段"时它就是这么写的。
        // markdown 会把这些软换行合并成一个空格，几个自然段糊成一整块。
        val input = "第一段。\n第二段。\n第三段。"

        assertEquals("第一段。\n\n第二段。\n\n第三段。", normalize(input))
    }

    @Test
    fun `blank lines are left as they are`() {
        val input = "第一段。\n\n第二段。"

        assertEquals(input, normalize(input))
    }

    // ── 不能动的第 4 条：硬换行是"段内换行" ─────────────────

    @Test
    fun `two trailing spaces mark a deliberate line break inside one paragraph`() {
        // 实测 DeepSeek 就是这么写段内换行的；动了就把一段拆成两段。
        val input = "句子 A。  \n句子 B。"

        assertEquals(input, normalize(input))
    }

    @Test
    fun `trailing backslash is also a deliberate line break`() {
        val input = "句子 A。\\\n句子 B。"

        assertEquals(input, normalize(input))
    }

    // ── 不能动的第 1 条：围栏代码块 ────────────────────────

    @Test
    fun `code fences are never touched`() {
        val input = "看代码：\n```python\na = 1\nb = 2\n```\n就这样。"

        assertEquals("看代码：\n\n```python\na = 1\nb = 2\n```\n\n就这样。", normalize(input))
    }

    @Test
    fun `code fence content is preserved even if it looks like prose`() {
        val input = "```\n第一行\n第二行\n```"

        assertEquals(input, normalize(input))
    }

    @Test
    fun `tilde fences are recognised too`() {
        val input = "```\n~~~\n不是围栏\n~~~\n```"

        // 外层 ``` 开 → 里面的 ~~~ 不该把状态打乱
        assertEquals(input, normalize(input))
    }

    // ── 不能动的第 2 条：表格 ──────────────────────────────

    @Test
    fun `table rows are kept tight`() {
        val input = "| 名字 | 年龄 |\n|---|---|\n| 甲 | 20 |"

        assertEquals(input, normalize(input))
    }

    @Test
    fun `a table right after a paragraph still gets its separator`() {
        val input = "表格如下：\n| a | b |\n|---|---|"

        assertEquals("表格如下：\n\n| a | b |\n|---|---|", normalize(input))
    }

    // ── 不能动的第 5 条：缩进续行 ──────────────────────────

    @Test
    fun `indented continuation lines are not broken apart`() {
        val input = "1. 第一项\n   续行\n2. 第二项"

        assertEquals(input, normalize(input))
    }

    // ── 边界 ──────────────────────────────────────────────

    @Test
    fun `empty input stays empty`() {
        assertEquals("", normalize(""))
    }

    @Test
    fun `a single line is unchanged`() {
        assertEquals("只有一行", normalize("只有一行"))
    }

    @Test
    fun `heading followed by text gets a break`() {
        val input = "# 标题\n正文"

        assertEquals("# 标题\n\n正文", normalize(input))
    }

    @Test
    fun `already normalised text is idempotent`() {
        val once = normalize("A\nB\nC")

        assertEquals(once, normalize(once))
    }

    // ── 第 6 条：句中标点收尾是同一段的续行 ─────────────────

    @Test
    fun `a line ending with a comma continues the same paragraph`() {
        // 中文长句被换行截断时行尾就是逗号 —— 不加这条会被拆成两段。
        val input = "他转过身，\n看着窗外。"

        assertEquals(input, normalize(input))
    }

    @Test
    fun `an opening bracket on the line end also continues`() {
        val input = "他说：\n「好的。」"

        assertEquals(input, normalize(input))
    }

    @Test
    fun `a line ending with a period still starts a new paragraph`() {
        // 句号收尾是"一短句一行"的正常分段，不能被上一条吞掉。
        val input = "第一段。\n第二段。"

        assertEquals("第一段。\n\n第二段。", normalize(input))
    }

    @Test
    fun `continuation rule applies per line not per paragraph`() {
        // 逗号行续接、句号行分段，两种情况在同一篇里共存。
        val input = "他转过身，\n看着窗外。\n然后离开了。"

        assertEquals("他转过身，\n看着窗外。\n\n然后离开了。", normalize(input))
    }

    @Test
    fun `a continuation line before a new block still breaks`() {
        // `要点如下：` 末尾是句中标点，但下一行开了列表 —— 必须分块。
        val input = "要点如下：\n- 一"

        assertEquals("要点如下：\n\n- 一", normalize(input))
    }

    // ── 不能动的第 7 条：相邻引用行 ────────────────────────

    @Test
    fun `consecutive quote lines stay in one block`() {
        // 拆开后每段各带一条竖线，看着像两个引用。
        val input = "> 引用第一行。\n> 引用第二行。"

        assertEquals(input, normalize(input))
    }

    @Test
    fun `an alert keeps its title and body together`() {
        // GFM 提示块拆开后标题与正文各成一块。
        val input = "> [!NOTE]\n> 提示正文。"

        assertEquals(input, normalize(input))
    }

    @Test
    fun `separate quotes with a blank line are still separate`() {
        val input = "> 引用一。\n\n> 引用二。"

        assertEquals(input, normalize(input))
    }
}
