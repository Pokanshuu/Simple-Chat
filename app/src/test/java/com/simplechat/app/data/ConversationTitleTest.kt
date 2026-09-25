package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话标题的两条纯逻辑。
 *
 * 它们必须**完全一致地**成对工作：`derivedTitle` 既用来兜底写库，
 * 又用来判断"标题有没有被人改过"—— 规则一旦漂移，AI 生成的标题就永远覆盖不上去，
 * 而现象只是"标题一直是那句话"，从界面上根本看不出哪一环断了。
 */
class ConversationTitleTest {

    // ── 兜底标题 ──────────────────────────────────────────

    @Test
    fun `takes the first line and trims it`() {
        assertEquals("帮我写一段开头", derivedTitle("  帮我写一段开头  \n第二行不该出现", "新对话"))
    }

    @Test
    fun `truncates long input`() {
        val long = "一".repeat(50)

        assertEquals(TITLE_MAX_CHARS, derivedTitle(long, "新对话").length)
    }

    @Test
    fun `blank input falls back to the placeholder`() {
        assertEquals("新对话", derivedTitle("", "新对话"))
        assertEquals("新对话", derivedTitle("   \n  ", "新对话"))
    }

    @Test
    fun `is stable for the same input`() {
        // 幂等性：判断"有没有被改过"靠的就是它每次算出来一样
        val text = "写一个关于灯塔的故事\n分五段"

        assertEquals(derivedTitle(text, "新对话"), derivedTitle(text, "新对话"))
    }

    @Test
    fun `auto titles are recognised in every language`() {
        // 中文界面建的会话切到英文界面（或反过来）之后，
        // 兜底标题照样要认得出来 —— 否则 AI 生成的标题永远盖不上去
        assertTrue(isAutoTitle("新对话"))
        assertTrue(isAutoTitle("New chat"))
        assertTrue(!isAutoTitle("灯塔与守夜人"))
    }

    // ── 模型输出清洗 ──────────────────────────────────────

    @Test
    fun `strips the prefix the model loves to add`() {
        assertEquals("灯塔与守夜人", cleanGeneratedTitle("标题：灯塔与守夜人"))
        assertEquals("灯塔与守夜人", cleanGeneratedTitle("标题: 灯塔与守夜人"))
        assertEquals("灯塔与守夜人", cleanGeneratedTitle("Title: 灯塔与守夜人"))
    }

    @Test
    fun `strips quotes and book-title marks`() {
        assertEquals("灯塔与守夜人", cleanGeneratedTitle("\"灯塔与守夜人\""))
        assertEquals("灯塔与守夜人", cleanGeneratedTitle("《灯塔与守夜人》"))
        assertEquals("灯塔与守夜人", cleanGeneratedTitle("“灯塔与守夜人”"))
        assertEquals("灯塔与守夜人", cleanGeneratedTitle("「灯塔与守夜人」"))
    }

    @Test
    fun `strips trailing punctuation`() {
        assertEquals("灯塔与守夜人", cleanGeneratedTitle("灯塔与守夜人。"))
        assertEquals("灯塔与守夜人", cleanGeneratedTitle("灯塔与守夜人！"))
        assertEquals("灯塔与守夜人", cleanGeneratedTitle("灯塔与守夜人?"))
    }

    @Test
    fun `keeps only the first line`() {
        assertEquals("灯塔与守夜人", cleanGeneratedTitle("灯塔与守夜人\n\n我为你起的标题"))
    }

    @Test
    fun `returns null when there is nothing usable`() {
        assertNull(cleanGeneratedTitle(""))
        assertNull(cleanGeneratedTitle("   "))
        assertNull(cleanGeneratedTitle("《》"))
        assertNull(cleanGeneratedTitle("。"))
    }

    @Test
    fun `truncates when the model ignores the length limit`() {
        val tooLong = "标".repeat(60)

        assertEquals(TITLE_MAX_CHARS, cleanGeneratedTitle(tooLong)!!.length)
    }

    @Test
    fun `result is safe to write straight into the database`() {
        val cleaned = cleanGeneratedTitle("  《 灯塔与守夜人 》 。 ")!!

        assertTrue(cleaned.isNotBlank())
        assertEquals(cleaned, cleaned.trim())
        assertTrue(cleaned.length <= TITLE_MAX_CHARS)
    }
}

