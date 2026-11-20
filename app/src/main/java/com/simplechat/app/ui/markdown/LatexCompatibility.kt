package com.simplechat.app.ui.markdown

import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.model.LatexNode

internal data class LatexPresentation(
    val nativeTex: String?,
    val simplified: Boolean = false,
    val ovalParts: List<LatexPart> = emptyList(),
) {
    val canRender: Boolean get() = nativeTex != null || ovalParts.isNotEmpty()
}

internal data class LatexPart(val tex: String, val outline: OvalOutline? = null)

internal enum class OvalOutline { THIN, THICK }

/** Display-only adaptation. The original message and export/request data are never changed. */
internal fun prepareLatexPresentation(source: String): LatexPresentation {
    if (source.length > 32_000) return LatexPresentation(null)
    val normalized = normalizeLatexBoxes(source) ?: return LatexPresentation(null)
    val compatible = normalizeTextSizes(normalized)
    val parts = if ("\\ovalbox" in compatible || "\\Ovalbox" in compatible) {
        splitLatexOvals(compatible) ?: return LatexPresentation(null)
    } else emptyList()
    val simplified = normalized != source
    if (parts.isEmpty()) {
        return LatexPresentation(compatible.takeIf(::isSupportedLatex), simplified)
    }
    return if (parts.all { isSupportedLatex(it.tex) }) {
        LatexPresentation(null, simplified, parts)
    } else LatexPresentation(null)
}

private fun isSupportedLatex(tex: String): Boolean {
    return try {
        val parsed = LatexParser().parseWithDiagnostics(tex)
        if (parsed.errors.isNotEmpty()) return false
        val pending = ArrayDeque<LatexNode>().apply { add(parsed.document) }
        var count = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (++count > 8_000 || node is LatexNode.Error || node is LatexNode.Command) return false
            node.children().forEach { pending.addLast(it) }
        }
        true
    } catch (_: Exception) { false }
      catch (_: StackOverflowError) { false }
}

/** Remove only unsupported layout wrappers, preserving their complete body with balanced braces. */
internal fun normalizeLatexBoxes(source: String, level: Int = 0): String? {
    if (level > 32) return null
    val out = StringBuilder()
    var i = 0
    while (i < source.length) {
        if (source[i] != '\\') { out.append(source[i++]); continue }
        val start = i++
        while (i < source.length && source[i].isLetter()) i++
        val command = source.substring(start + 1, i)
        if (command !in LayoutWrappers) {
            if (command.isEmpty() && i < source.length) i++
            out.append(source, start, i)
            continue
        }
        fun whitespace() { while (i < source.length && source[i].isWhitespace()) i++ }
        fun group(open: Char = '{'): String? {
            whitespace()
            if (i >= source.length || source[i] != open) return null
            val contentStart = ++i
            val close = if (open == '{') '}' else ']'
            var depth = 1
            var braces = 0
            while (i < source.length) {
                val c = source[i++]
                if (c == '\\') { if (i < source.length) i++; continue }
                if (open == '[') {
                    if (c == '{') braces++
                    if (c == '}') braces--
                    if (braces > 0) continue
                }
                if (c == open) depth++
                if (c == close) {
                    depth--
                    if (depth == 0) return source.substring(contentStart, i - 1)
                }
            }
            return null
        }
        fun option(): Boolean {
            whitespace()
            return i >= source.length || source[i] != '[' || group('[') != null
        }
        when (command) {
            "scalebox" -> { group() ?: return null; if (!option()) return null }
            "resizebox" -> {
                if (i < source.length && source[i] == '*') i++
                group() ?: return null; group() ?: return null
            }
            "rotatebox" -> { if (!option()) return null; group() ?: return null }
            "raisebox" -> { group() ?: return null; if (!option() || !option()) return null }
            "parbox" -> { if (!option() || !option() || !option()) return null; group() ?: return null }
            "makebox" -> { if (!option() || !option()) return null }
        }
        val body = group() ?: return null
        val content = normalizeLatexBoxes(body, level + 1) ?: return null
        out.append('{').append(content).append('}')
    }
    return out.toString()
}

private val LayoutWrappers = setOf("scalebox", "resizebox", "rotatebox", "raisebox", "parbox", "makebox")

// Keep the size declaration scoped, while leaving the contents in text mode.
private fun normalizeTextSizes(tex: String): String {
    if ("\\text" !in tex && "\\mbox" !in tex && "\\hbox" !in tex) return tex
    return buildString {
        var start = 0
        for (match in TextSizePrefix.findAll(tex)) {
            if (match.range.first < start || tex.isEscapedCommand(match.range.first)) continue
            val openingBrace = tex.indexOf('{', match.range.first)
            val end = tex.latexGroupEnd(openingBrace) ?: continue
            append(tex, start, match.range.first)
            append("{\\").append(match.groupValues[1]).append("\\text{")
            append(tex, match.range.last + 1, end - 1)
            append("}}")
            start = end
        }
        append(tex, start, tex.length)
    }
}

private val TextSizePrefix = Regex("""\\(?:text|mbox|hbox)\s*\{\s*\\(tiny|scriptsize|footnotesize|small|normalsize|large|Large|LARGE|huge|Huge)(?![A-Za-z])\s*""")

private fun String.isEscapedCommand(start: Int): Boolean {
    var i = start - 1
    while (i >= 0 && this[i] == '\\') i--
    return (start - i - 1) % 2 != 0
}

/** End-exclusive, ignoring escaped braces. */
private fun String.latexGroupEnd(start: Int): Int? {
    if (getOrNull(start) != '{') return null
    var depth = 1
    var i = start + 1
    while (i < length) {
        when (this[i++]) {
            '\\' -> i++
            '{' -> depth++
            '}' -> if (--depth == 0) return i
        }
    }
    return null
}

/** Lay out formula-level oval boxes alongside the native spans. */
private fun splitLatexOvals(tex: String): List<LatexPart>? {
    val parts = mutableListOf<LatexPart>()
    var start = 0
    var i = 0
    while (i < tex.length) {
        when (tex[i++]) {
            '{' -> i = tex.latexGroupEnd(i - 1) ?: return null
            '\\' -> {
                val commandStart = i - 1
                while (i < tex.length && (tex[i] in 'a'..'z' || tex[i] in 'A'..'Z')) i++
                val command = tex.substring(commandStart + 1, i)
                if (command.isEmpty()) {
                    if (i < tex.length) i++
                } else if (command == "ovalbox" || command == "Ovalbox") {
                    while (i < tex.length && tex[i].isWhitespace()) i++
                    val end = tex.latexGroupEnd(i) ?: return null
                    val prefix = tex.substring(start, commandStart)
                    if (prefix.isNotBlank()) parts += LatexPart(prefix)
                    val body = tex.substring(i + 1, end - 1)
                    // A bare CJK sentence in a box is prose, so the math font must not italicize it.
                    val content = if (PlainCjkBox.matches(body)) "\\text{$body}" else body
                    val outline = if (command == "Ovalbox") OvalOutline.THICK else OvalOutline.THIN
                    parts += LatexPart(content, outline)
                    if (parts.size > 64) return null
                    i = end
                    start = end
                }
            }
        }
    }
    if (parts.isNotEmpty()) {
        val suffix = tex.substring(start)
        if (suffix.isNotBlank()) parts += LatexPart(suffix)
    }
    return parts
}

private val PlainCjkBox = Regex("""(?!.*[\\{}$%_#^&~])(?=.*\p{IsHan})[\p{L}\p{N}\p{P} \t]+""")
