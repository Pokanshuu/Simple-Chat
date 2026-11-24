package com.simplechat.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class MessagePageTest {
    @Test fun `page boundaries preserve all unicode exactly once`() {
        val text = "a".repeat(MESSAGE_PAGE_CHARS - 1) + "😀" + "中".repeat(MESSAGE_PAGE_CHARS - 2) + "😀" + "end"
        val windows = (0 until messagePage(text, 0).count).map { messagePage(text, it) }
        assertEquals(text, windows.joinToString("") { text.substring(it.start, it.end) })
        windows.zipWithNext().forEach { (left, right) -> assertEquals(left.end, right.start) }
        windows.forEach { assertFalse(text[it.start].isLowSurrogate()); assertFalse(text[it.end - 1].isHighSurrogate()) }
    }

    @Test fun `search spanning two chunks is visible as one match`() {
        val query = "跨边界😀命中"
        val hit = MESSAGE_PAGE_CHARS - 2
        val text = "a".repeat(hit) + query + "b".repeat(16)
        val first = messagePage(text, 0, hit, hit + query.length)
        val second = messagePage(text, 1, hit, hit + query.length)
        assertEquals(query, text.substring(hit, first.end))
        assertEquals(first.end, second.start)
        assertEquals(text, text.substring(first.start, first.end) + text.substring(second.start, second.end))
    }

    @Test fun `empty text and stale indices stay inside bounds`() {
        assertEquals(MessagePage(0, 0, 0, 1), messagePage("", Int.MAX_VALUE))
        val text = "x".repeat(MESSAGE_PAGE_CHARS)
        assertEquals(MessagePage(0, text.length, 0, 1), messagePage(text, Int.MAX_VALUE))
        assertEquals(0, messagePage(text, -10).index)
    }
}
