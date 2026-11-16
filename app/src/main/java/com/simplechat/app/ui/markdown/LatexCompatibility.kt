package com.simplechat.app.ui.markdown

import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.model.LatexNode

internal data class LatexPresentation(val nativeTex: String?, val simplified: Boolean = false)

/** Display-only adaptation. The original message and export/request data are never changed. */
internal fun prepareLatexPresentation(source: String): LatexPresentation {
    if (source.length > 32_000) return LatexPresentation(null)
    val compatible = normalizeLatexBoxes(source) ?: return LatexPresentation(null)
    return try {
        val parsed = LatexParser().parseWithDiagnostics(compatible)
        if (parsed.errors.isNotEmpty()) return LatexPresentation(null)
        val pending = ArrayDeque<LatexNode>().apply { add(parsed.document) }
        var count = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (++count > 8_000 || node is LatexNode.Error || node is LatexNode.Command) return LatexPresentation(null)
            node.children().forEach { pending.addLast(it) }
        }
        LatexPresentation(compatible, compatible != source)
    } catch (_: Exception) { LatexPresentation(null) }
      catch (_: StackOverflowError) { LatexPresentation(null) }
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
