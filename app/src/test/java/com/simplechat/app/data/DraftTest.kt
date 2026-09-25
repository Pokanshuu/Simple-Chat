package com.simplechat.app.data

import com.simplechat.app.db.DraftEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 草稿落库前的归一规则。
 *
 * 两条都是"不能悄悄出错"的那类：空草稿要**删行**（否则会攒一堆空壳），
 * 超长草稿要**截尾**（否则一次异常粘贴会把落库拖慢）。
 */
class DraftTest {

    @Test
    fun `empty draft means delete the row`() {
        assertNull(draftForStorage(""))
    }

    @Test
    fun `short draft is stored as-is`() {
        assertEquals("写一半的想法", draftForStorage("写一半的想法"))
    }

    @Test
    fun `whitespace is content, not empty`() {
        assertEquals("  ", draftForStorage("  "))
    }

    @Test
    fun `oversized draft is truncated to the cap`() {
        val huge = "a".repeat(DraftEntity.MaxChars + 5_000)
        val stored = draftForStorage(huge)
        assertEquals(DraftEntity.MaxChars, stored?.length)
        // 截的是**尾巴**，开头那段用户最可能还要用
        assertEquals("a", stored?.first().toString())
    }

    @Test
    fun `draft exactly at the cap is not touched`() {
        val exact = "b".repeat(DraftEntity.MaxChars)
        assertEquals(exact, draftForStorage(exact))
    }

    // ── 待用标题 ─────────────────────────────────────────

    @Test
    fun `blank title means never named it`() {
        assertNull(titleForStorage(null))
        assertNull(titleForStorage(""))
        assertNull(titleForStorage("   "))
        assertNull(titleForStorage("\n\t"))
    }

    @Test
    fun `title is trimmed`() {
        assertEquals("秋夜", titleForStorage("  秋夜  "))
    }
}
