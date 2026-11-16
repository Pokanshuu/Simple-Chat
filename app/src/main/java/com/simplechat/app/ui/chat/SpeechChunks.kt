package com.simplechat.app.ui.chat

/** System TTS imposes an input limit per utterance. Keep every character and surrogate pair. */
internal fun speechChunks(text: String, maxChars: Int): List<String> {
    require(maxChars >= 2)
    val result = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + maxChars, text.length)
        if (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
        result += text.substring(start, end)
        start = end
    }
    return result
}
