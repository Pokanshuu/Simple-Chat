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

    @Test fun `empty text and stale indices stay inside bounds`() {
        assertEquals(MessagePage(0, 0, 0, 1), messagePage("", Int.MAX_VALUE))
        val text = "x".repeat(MESSAGE_PAGE_CHARS)
        assertEquals(MessagePage(0, text.length, 0, 1), messagePage(text, Int.MAX_VALUE))
        assertEquals(0, messagePage(text, -10).index)
    }
}
