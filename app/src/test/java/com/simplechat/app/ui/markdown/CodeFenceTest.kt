package com.simplechat.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码块那两处优化各钉一条：
 *
 * - **增量高亮**：敢复用前缀的前提是"结果与整段重算**逐条一致**" —— 不一致就是错色
 * - **收起态截断**：只能切在行边界上，切出半行会露出半截代码
 */
class CodeFenceTest {

    private val light = false

    @Test
    fun `纯追加的增量结果与整段重算一致`() {
        val first = "fun main() {\n    println(\"hi\")\n"
        val second = first + "    val x = 1\n"

        val incremental = IncrementalHighlight()
        incremental.spansFor(first, "kotlin", light)
        val viaIncremental = incremental.spansFor(second, "kotlin", light)

        val fromScratch = IncrementalHighlight().spansFor(second, "kotlin", light)

        assertEquals(fromScratch, viaIncremental)
        assertTrue(viaIncremental.isNotEmpty())
    }

    @Test
    fun `最后一行的 token 延伸时不会用错色`() {
        // 第一版停在半个字符串里，第二版把它补全 —— 前缀里那半行必须重算
        val first = "val s = \"abc"
        val second = "val s = \"abcdef\""

        val incremental = IncrementalHighlight()
        incremental.spansFor(first, "kotlin", light)
        val viaIncremental = incremental.spansFor(second, "kotlin", light)

        assertEquals(
            IncrementalHighlight().spansFor(second, "kotlin", light),
            viaIncremental,
        )
    }

    @Test
    fun `换语言时整段重算`() {
        val code = "print(1)\nprint(2)\n"
        val incremental = IncrementalHighlight()
        incremental.spansFor(code, "kotlin", light)

        assertEquals(
            IncrementalHighlight().spansFor(code, "python", light),
            incremental.spansFor(code, "python", light),
        )
    }

    @Test
    fun `换主题时整段重算`() {
        val code = "val a = 1\n"
        val incremental = IncrementalHighlight()
        incremental.spansFor(code, "kotlin", light)

        assertEquals(
            IncrementalHighlight().spansFor(code, "kotlin", true),
            incremental.spansFor(code, "kotlin", true),
        )
    }

    @Test
    fun `同一份内容重复调用不重算`() {
        val code = "val a = 1\nval b = 2\n"
        val incremental = IncrementalHighlight()
        val first = incremental.spansFor(code, "kotlin", light)
        val second = incremental.spansFor(code, "kotlin", light)
        assertTrue("应当直接返回上一次的结果", first === second)
    }

    @Test
    fun `截断只切在行边界上`() {
        val code = "a\nb\nc\nd\n"
        assertEquals("a\nb\n", code.truncatedToLines(2))
        assertEquals("line1\nline2\n", "line1\nline2\nline3".truncatedToLines(2))
        // 行数不够就原样返回
        assertEquals(code, code.truncatedToLines(99))
        assertEquals("abc", "abc".truncatedToLines(3))
    }

    @Test
    fun `高亮颜色必须不透明`() {
        // 库给的 rgb 高位是 0；漏了 copy(alpha = 1f) 的话高亮字符会整段消失
        val spans = IncrementalHighlight().spansFor("val s = \"abc\" // c\n", "kotlin", light)
        assertTrue("应当有高亮片段", spans.isNotEmpty())
        spans.mapNotNull { it.style.color }.forEach {
            assertEquals(1f, it.alpha, 0.0001f)
        }
    }
}
