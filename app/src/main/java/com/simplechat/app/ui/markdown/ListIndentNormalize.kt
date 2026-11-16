package com.simplechat.app.ui.markdown

/**
 * 列表项内部**多余缩进**的归一化。
 *
 * ### 为什么需要它
 *
 * 模型很喜欢这样写列表：
 *
 * ```text
 * 1. **小标题**
 *
 *      这是正文……
 * ```
 *
 * 「这是正文」那一行前面有 6 个空格，而 `1. ` 的内容缩进只有 3 格
 * （CommonMark 只把**刚好等于内容缩进**的前缀当结构剥掉）。多出来的 3 格是
 * **字面空格**，会被原样排进段落开头 —— 真机上看到的就是「正文莫名向右缩进一大截」，
 * 而且紧接着上一段的间距也被撑开，读起来像排版坏了。
 *
 * 这里把列表项内正文行的缩进**夹到内容缩进**：结构不变（仍是同一个列表项），
 * 但多出来的空格不再进正文。嵌套列表的标记行原样保留（层级靠它）。
 *
 * 纯函数，有单测（`ListIndentNormalizeTest`）；只影响渲染，不改消息本身。
 */
internal fun normalizeListIndent(markdown: String): String {
    if (markdown.isEmpty()) return markdown
    val lines = markdown.split('\n')
    val out = ArrayList<String>(lines.size)
    // 每一层列表项"内容缩进"（`- ` → 2，`1. ` → 3，`10. ` → 4）
    val contentIndents = ArrayDeque<Int>()
    var fence: String? = null

    for (line in lines) {
        val trimmed = line.trimStart()
        val marker = fenceMarkerOf(trimmed)
        if (marker != null) {
            when {
                fence == null -> fence = marker
                marker == fence -> fence = null
            }
            out += line
            continue
        }
        if (fence != null) {
            out += line
            continue
        }
        if (trimmed.isEmpty()) {
            out += line
            continue
        }
        val indent = line.length - trimmed.length
        val markerWidth = listMarkerWidth(trimmed)
        if (markerWidth != null) {
            // 列表标记行：确定它属于哪一层，并记下这一层的内容缩进
            while (contentIndents.isNotEmpty() && indent < contentIndents.last()) {
                contentIndents.removeLast()
            }
            val contentIndent = indent + markerWidth
            if (contentIndents.isEmpty()) {
                contentIndents.addLast(contentIndent)
            } else {
                // 同层兄弟标记：覆盖为当前项；嵌套标记：压入新的一层
                if (indent >= contentIndents.last()) contentIndents.addLast(contentIndent)
                else contentIndents[contentIndents.size - 1] = contentIndent
            }
            out += line
            continue
        }
        // 正文行：在列表项内时，把多余缩进夹到内容缩进
        if (contentIndents.isNotEmpty()) {
            val contentIndent = contentIndents.last()
            if (indent > contentIndent) {
                out += " ".repeat(contentIndent) + trimmed
                continue
            }
        }
        out += line
    }
    return out.joinToString("\n")
}

/**
 * 列表标记的"内容缩进"宽度（标记本身 + 其后至少一个空格）；不是列表行返回 null。
 *
 * 支持无序 `- ` / `* ` / `+ `、有序 `1. ` / `1) `、任务列表 `- [ ] `。
 * 只认行首（调用方已 trimStart）。
 */
private fun listMarkerWidth(trimmed: String): Int? {
    if (trimmed.length < 2) return null
    // 无序：`- ` / `* ` / `+ `
    if (trimmed[0] in "-*+" && trimmed[1] == ' ') {
        // 任务列表：`- [ ] ` 的内容从任务框之后开始
        if (trimmed.length >= 5 && trimmed[2] == '[' && trimmed[4] == ']' && trimmed.getOrNull(5) == ' ') {
            return 6
        }
        return 2
    }
    // 有序：`1. ` / `12) `
    var digits = 0
    while (digits < trimmed.length && trimmed[digits].isDigit()) digits++
    if (digits == 0) return null
    val dot = trimmed.getOrNull(digits) ?: return null
    if (dot != '.' && dot != ')') return null
    val space = trimmed.getOrNull(digits + 1) ?: return null
    if (space != ' ') return null
    return digits + 2
}
