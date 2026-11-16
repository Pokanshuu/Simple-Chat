package com.simplechat.app.ui.chat

internal const val MESSAGE_PAGE_CHARS = 8_000
internal const val LARGE_MESSAGE_CHARS = 32_000

internal data class MessagePage(val start: Int, val end: Int, val index: Int, val count: Int)

internal fun messagePage(text: String, requested: Int): MessagePage {
    val count = ((text.length.toLong() + MESSAGE_PAGE_CHARS - 1) / MESSAGE_PAGE_CHARS).toInt().coerceAtLeast(1)
    val index = requested.coerceIn(0, count - 1)
    var start = index * MESSAGE_PAGE_CHARS
    var end = minOf(start + MESSAGE_PAGE_CHARS, text.length)
    if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
    if (end < text.length && end > 0 && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
    return MessagePage(start, end, index, count)
}
