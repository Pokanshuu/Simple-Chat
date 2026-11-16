package com.simplechat.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 列表项正文的缩进归一化（`normalizeListIndent`）。
 *
 * 真机反馈："缩进的文本和上面的间距也有问题" —— 模型把列表项正文缩进得比内容列更多，
 * 多出来的空格会被 CommonMark 当**字面空格**排进正文，看起来就是"莫名右移一大截"。
 * 这里把多余缩进夹回内容缩进（结构不变、字面空格消失），并保证嵌套列表不被破坏。
 */
class ListIndentNormalizeTest {

    @Test
    fun `多余缩进被夹到内容缩进`() {
        val src = "1. 标题\n\n      这是正文\n"
        val out = normalizeListIndent(src)
        // `1. ` 的内容缩进是 3 格：多出来的 3 格被去掉
        assertEquals("1. 标题\n\n   这是正文\n", out)
    }

    @Test
    fun `原本就对齐的缩进保持不变`() {
        val src = "1. 标题\n\n   这是正文\n"
        assertEquals(src, normalizeListIndent(src))
    }

    @Test
    fun `嵌套列表的标记行保持原缩进`() {
        val src = "1. 标题\n\n   - 子项\n   - 子项二\n"
        assertEquals(src, normalizeListIndent(src))
    }

    @Test
    fun `嵌套列表项的续行也被夹到子项内容缩进`() {
        val src = "1. 标题\n\n   - 子项\n\n        子项续行\n"
        val out = normalizeListIndent(src)
        // 子项 `- ` 内容缩进 = 3(父) + 2 = 5 格
        assertEquals("1. 标题\n\n   - 子项\n\n     子项续行\n", out)
    }

    @Test
    fun `列表结束后的普通段落原样保留`() {
        val src = "1. 标题\n\n   正文\n\n普通段落\n"
        assertEquals(src, normalizeListIndent(src))
    }

    @Test
    fun `代码围栏内的缩进不动`() {
        val src = "1. 标题\n\n   ```\n      代码缩进\n   ```\n"
        // 围栏内部原样；围栏标记行因为是列表项内的缩进块，缩进也可能被裁剪 —— 
        // 这里只要求内容行不被改动
        val out = normalizeListIndent(src)
        assertEquals(true, out.contains("      代码缩进"))
    }

    @Test
    fun `任务列表标记按内容列计算`() {
        val src = "- [ ] 任务\n\n      续行\n"
        val out = normalizeListIndent(src)
        assertEquals("- [ ] 任务\n\n      续行\n", out)
    }

    @Test
    fun `没有列表时原样返回`() {
        val src = "第一段\n\n第二段\n"
        assertEquals(src, normalizeListIndent(src))
    }
}
