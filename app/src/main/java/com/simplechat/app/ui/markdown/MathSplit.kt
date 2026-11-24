package com.simplechat.app.ui.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * 正文里的一段内容：普通 Markdown，或一段数学公式。
 *
 * 切分是**纯函数**（[splitMath]），有单测（`MathSplitTest`）——
 * 公式切错的表现是"整段正文少了一截"或者"代码块里突然冒出个公式"，
 * 从界面上几乎不可能定位。
 */
sealed interface ContentSegment {

    data class Text(val text: String) : ContentSegment

    /** [source] preserves the delimiters when the span is passed back to Markdown. */
    data class Formula(val tex: String, val display: Boolean, val source: String) : ContentSegment
}

/**
 * 把一段 Markdown 切成「文本 / 公式」交替的片段。
 *
 * ### 支持的定界符
 * | 写法 | 形态 |
 * |---|---|
 * | `$$…$$` | 独占一行时为行间；同一行夹在正文中时为行内 |
 * | `\[…\]` | 独占行时为行间，同一行夹在正文中时为行内 |
 * | `$…$` | 行内 |
 * | `\(…\)` | 行内 |
 *
 * ### 三条刻意的规则
 * 1. **代码里的 `$` 不算公式**：围栏代码块与行内代码整段跳过 —— 否则一段 shell
 *    脚本里的 `$PATH` 会把后面整篇正文吞掉。
 * 2. **单 `$` 定界符内侧不能是空白**：这条把 `$5 和 $10` 这类金额挡在外面
 *    （开引号后面紧跟数字、闭引号前面是空格 → 不成对）。
 * 3. **没闭合就按普通文本**：流式期间半截公式会先以原文显示，闭合后才变成公式。
 *    宁可晚一拍，也不要在屏幕上闪一个渲染失败的红框。
 */
internal fun splitMath(content: String): List<ContentSegment> {
    if (content.isEmpty()) return emptyList()
    // 没有任何定界符：快路径，原样返回（绝大多数消息走到这里）
    if (!content.contains('$') && !content.contains("\\[") && !content.contains("\\(") && !content.contains("\\begin{")) {
        return listOf(ContentSegment.Text(content))
    }

    val excluded = mathExcludedRanges(content)
    var rangeIndex = 0
    val out = mutableListOf<ContentSegment>()
    val buffer = StringBuilder()
    var i = 0

    fun flushText() {
        if (buffer.isNotEmpty()) {
            out += ContentSegment.Text(buffer.toString())
            buffer.clear()
        }
    }

    while (i < content.length) {
        while (rangeIndex < excluded.size && excluded[rangeIndex].last < i) rangeIndex++
        val nextRange = excluded.getOrNull(rangeIndex)
        if (nextRange != null && i >= nextRange.first) {
            buffer.append(content, i, nextRange.last + 1)
            i = nextRange.last + 1
            continue
        }
        val limit = nextRange?.first ?: content.length
        if (content.startsWith("\\$", i) || content.startsWith("\\\\", i) || content.startsWith("\\`", i)) {
            buffer.append(content, i, i + 2)
            i += 2
            continue
        }
        if (content.startsWith("\\begin{", i)) {
            val stop = displayEnvironmentEnd(content, i, minOf(limit, i + 32_000))
            if (stop > i) {
                flushText()
                val source = content.substring(i, stop)
                out += ContentSegment.Formula(source, display = true, source = source)
                i = stop
                continue
            }
        }
        val closer = when {
            content.startsWith("$$", i) -> "$$"
            content.startsWith("\\[", i) -> "\\]"
            content.startsWith("\\(", i) -> "\\)"
            else -> null
        }
        if (closer != null) {
            val end = mathClose(content, i + 2, closer, minOf(limit, i + 32_004), opener = if (closer == "\\)") "\\(" else null)
            if (end > i + 2) {
                val tex = content.substring(i + 2, end).trim()
                if (tex.isNotEmpty()) {
                    val stop = end + 2
                    val display = closer != "\\)" && content.isStandalone(i, stop)
                    if (closer != "\\)" && !display && content.hasLineBreak(i + 2, end)) {
                        buffer.append(content, i, i + 2)
                        i += 2
                        continue
                    }
                    flushText()
                    out += ContentSegment.Formula(tex, display, content.substring(i, stop))
                    i = stop
                    continue
                }
            }
            // Do not reinterpret the second dollar of an incomplete pair as a single opener.
            buffer.append(content, i, i + 2)
            i += 2
            continue
        }
        if (content[i] == '$') {
            val close = inlineClose(content, i, minOf(limit, i + 32_002))
            if (close > 0) {
                flushText()
                out += ContentSegment.Formula(
                    content.substring(i + 1, close).trim(),
                    display = false,
                    source = content.substring(i, close + 1),
                )
                i = close + 1
                continue
            }
        }

        buffer.append(content[i])
        i++
    }
    flushText()
    return out
}

private val StandaloneEnvironments = setOf(
    "equation", "equation*", "align", "align*", "aligned", "alignat", "alignat*", "alignedat",
    "flalign", "flalign*", "gather", "gather*", "gathered", "multline", "multline*", "split", "subequations",
    "cases", "dcases", "rcases", "drcases", "array", "matrix", "pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix", "smallmatrix",
)

private fun displayEnvironmentEnd(source: String, start: Int, limit: Int): Int {
    val nameStart = start + "\\begin{".length
    val nameEnd = (nameStart until minOf(limit, nameStart + 16)).firstOrNull { source[it] == '}' } ?: return -1
    val name = source.substring(nameStart, nameEnd)
    if (name !in StandaloneEnvironments) return -1
    val close = "\\end{$name}"
    val end = mathClose(source, nameEnd + 1, close, limit, allowBlankLines = true)
    val stop = end + close.length
    return if (end > nameEnd + 1 && source.isStandalone(start, stop)) stop else -1
}

/** Use Markdown's own fence lengths, container rules and link offsets. */
private fun mathExcludedRanges(source: String): List<IntRange> {
    if (!MathMarkdownSyntax.containsMatchIn(source)) return emptyList()
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
    val ranges = mutableListOf<IntRange>()
    val pending = ArrayDeque<org.intellij.markdown.ast.ASTNode>().apply { add(tree) }
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        when (node.type) {
            MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.CODE_SPAN,
            MarkdownElementTypes.LINK_DESTINATION, MarkdownElementTypes.AUTOLINK -> {
                if (node.startOffset < node.endOffset) ranges += node.startOffset until node.endOffset
            }
            else -> node.children.asReversed().forEach(pending::addLast)
        }
    }
    return ranges.sortedBy { it.first }
}

private val MathMarkdownSyntax = Regex("[\u0060~<]|\\]\\(|(?m)^(?: {4}|\\t| {0,3}\\[)")

private fun mathClose(
    content: String,
    start: Int,
    closer: String,
    limit: Int,
    opener: String? = null,
    allowBlankLines: Boolean = false,
): Int {
    var i = start
    var lineHasContent = true
    while (i < limit) {
        val char = content[i]
        if (i + closer.length <= limit && content.startsWith(closer, i)) return i
        if (opener != null && content.startsWith(opener, i)) return -1
        if (char in LineBreaks) {
            if (!allowBlankLines && !lineHasContent) return -1
            lineHasContent = false
            if (char == '\r' && content.getOrNull(i + 1) == '\n') i++
        } else if (!char.isWhitespace()) {
            lineHasContent = true
            // Never skip a line break following an escape.
            if (char == '\\' && i + 1 < limit && content[i + 1] !in LineBreaks) i++
        }
        i++
    }
    return -1
}

private val LineBreaks = charArrayOf('\r', '\n')

private fun String.hasLineBreak(start: Int, end: Int): Boolean =
    (start until end).any { this[it] in LineBreaks }

private fun String.isStandalone(start: Int, end: Int): Boolean {
    var before = start - 1
    while (before >= 0 && this[before] !in LineBreaks) {
        if (!this[before--].isWhitespace()) return false
    }
    var after = end
    while (after < length && this[after] !in LineBreaks) {
        if (!this[after++].isWhitespace()) return false
    }
    return true
}

/**
 * 找行内公式的闭合 `$`，找不到返回 -1。
 *
 * 规则：开引号后面不能是空白（排除 "$ 5"），闭引号前面不能是空白（排除 "$5 和 $10"），
 * 中间不能有换行（行内公式不跨行），`\$` 是**转义**的美元号、不当作闭合。
 */
private fun inlineClose(content: String, open: Int, limit: Int): Int {
    var i = open + 1
    if (i >= content.length) return -1
    if (content[i].isWhitespace()) return -1
    if (content[i] == '$') return -1 // "$$" 是行间公式，交给上面处理
    var braces = 0
    while (i < limit) {
        val c = content[i]
        if (c in LineBreaks) return -1
        if (c == '\\') {
            // 跳过被转义的那个字符（`\$` / `\\` / `\{`）
            if ((content.getOrNull(i + 1) ?: '\u0000') in LineBreaks) return -1
            i += 2
            continue
        }
        if (c == '{') braces++
        if (c == '}' && braces > 0) braces--
        if (c == '$' && braces == 0) {
            if (content[i - 1].isWhitespace() || content.getOrNull(i + 1)?.isDigit() == true) return -1
            return i
        }
        i++
    }
    return -1
}
