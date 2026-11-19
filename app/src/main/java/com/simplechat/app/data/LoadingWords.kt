package com.simplechat.app.data

/** Store one line of text without the ellipsis that the waiting animation supplies. */
internal fun normalizeLoadingWord(raw: String): String =
    raw.replace(Regex("[\\r\\n]+"), " ").trim().trimEnd { it.isWhitespace() || it == '.' || it == '…' }
