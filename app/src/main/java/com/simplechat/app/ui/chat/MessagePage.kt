package com.simplechat.app.ui.chat

internal const val MESSAGE_PAGE_CHARS = 8_000
internal const val LARGE_MESSAGE_CHARS = 128_000

internal data class MessagePage(val start: Int, val end: Int, val index: Int, val count: Int)

internal fun messagePage(text: String, requested: Int, matchStart: Int = -1, matchEnd: Int = -1): MessagePage {
    val count = ((text.length.toLong() + MESSAGE_PAGE_CHARS - 1) / MESSAGE_PAGE_CHARS).toInt().coerceAtLeast(1)
    val index = requested.coerceIn(0, count - 1)
    fun boundary(offset: Int): Int {
        if (offset <= 0 || offset >= text.length) return offset
        val safeOffset = if (text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) offset - 1 else offset
        // Keep a search hit intact even when it lands across a chunk boundary.
        return if (matchStart >= offset - MESSAGE_PAGE_CHARS && matchStart < offset && matchEnd > safeOffset &&
            matchEnd < offset.toLong() + MESSAGE_PAGE_CHARS) matchEnd else safeOffset
    }
    val start = boundary(index * MESSAGE_PAGE_CHARS)
    val end = boundary(minOf((index + 1L) * MESSAGE_PAGE_CHARS, text.length.toLong()).toInt())
    return MessagePage(start, end, index, count)
}
