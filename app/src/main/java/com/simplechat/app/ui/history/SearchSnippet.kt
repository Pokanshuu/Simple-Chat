package com.simplechat.app.ui.history

/**
 * 从消息正文里取一段**围绕关键词**的窗口，给搜索结果行用。
 *
 * 为什么是「围绕关键词取」而不是「取开头 N 个字」：
 * 整条消息可能几千字，全显示会撑爆结果行；而只取开头的话，
 * 关键词在中后段时结果行里根本看不见它 —— 用户会以为搜错了。
 *
 * 抽成纯函数是为了能单测：中文按字符切、大小写不敏感、
 * 关键词恰好在开头/结尾，这几个都是很容易写错的地方。
 */
internal fun snippetAround(
    content: String,
    query: String,
    /** 关键词两侧各保留多少个字符。 */
    radius: Int = 24,
): String {
    // 换行会把结果行撑成两行，先压平
    val flat = content.replace(Regex("\\s+"), " ").trim()
    if (query.isBlank()) return flat.take(radius * 2)

    val at = flat.indexOf(query, ignoreCase = true)
    if (at < 0) return flat.take(radius * 2)

    val start = (at - radius).coerceAtLeast(0)
    val end = (at + query.length + radius).coerceAtMost(flat.length)

    return buildString {
        if (start > 0) append('…')
        append(flat, start, end)
        if (end < flat.length) append('…')
    }
}

/**
 * 结果行是否值得显示片段。
 *
 * 只有标题命中时 [com.simplechat.app.db.ConversationSearchHit.snippet] 是 null；
 * 退化成"片段就是标题开头"没有意义，不如不显示。
 */
internal fun snippetForDisplay(
    snippet: String?,
    query: String,
    radius: Int = 24,
): String? {
    if (snippet.isNullOrBlank()) return null
    return snippetAround(snippet, query, radius)
}
