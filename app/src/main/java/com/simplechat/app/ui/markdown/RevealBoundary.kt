package com.simplechat.app.ui.markdown

/** Returns the next reveal boundary, or [start] if only an unfinished surrogate remains. */
internal fun nextRevealEnd(text: String, start: Int, advance: Int): Int {
    val end = start + advance.coerceIn(0, text.length - start)
    if (end == start || !text[end - 1].isHighSurrogate()) return end
    if (end == text.length) return end - 1
    if (!text[end].isLowSurrogate()) return end
    // Use the requested budget when possible, but an emoji first in line must make progress.
    return if (end - 1 > start) end - 1 else end + 1
}
