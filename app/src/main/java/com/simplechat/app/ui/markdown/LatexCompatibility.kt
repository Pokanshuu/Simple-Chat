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

internal data class LatexPart(val tex: String, val outline: OvalOutline? = null, val frame: LatexFrame? = null)

internal data class LatexFrame(
    val width: LatexLength? = null,
    val height: LatexLength? = null,
    val alignment: String = "c",
    val padding: LatexLength? = null,
    val radius: LatexLength? = null,
    val background: String? = null,
    val border: LatexLength? = null,
    val borderColor: String? = null,
)

internal data class LatexLength(val value: Float, val unit: String)

internal enum class OvalOutline { THIN, THICK }

/** Display-only adaptation. The original message and export/request data are never changed. */
internal fun prepareLatexPresentation(source: String): LatexPresentation {
    if (source.length > 32_000) return LatexPresentation(null)
    val normalized = normalizeLatexBoxes(source) ?: return LatexPresentation(null)
    val compatible = normalizeTextSizes(normalized)
    val parts = if (FrameCommands.any { "\\$it" in compatible }) {
        splitLatexFrames(compatible) ?: return LatexPresentation(null)
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
            // The parser accepts unknown environments as generic containers. Rendering
            // their children alone would silently discard the environment's meaning.
            if (++count > 8_000 || node is LatexNode.Error || node is LatexNode.Command || node is LatexNode.Environment) return false
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

/** Lay out formula-level frames alongside native spans. */
private fun splitLatexFrames(tex: String): List<LatexPart>? {
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
                } else if (command in FrameCommands) {
                    val prefix = tex.substring(start, commandStart)
                    if (prefix.isNotBlank()) parts += LatexPart(prefix)
                    while (tex.getOrNull(i)?.isWhitespace() == true) i++
                    val options = mutableListOf<String>()
                    while (tex.getOrNull(i) == '[' && options.size < 2) {
                        val close = tex.indexOf(']', i + 1).takeIf { it > i } ?: return null
                        options += tex.substring(i + 1, close).trim()
                        i = close + 1
                        while (tex.getOrNull(i)?.isWhitespace() == true) i++
                    }
                    val end = tex.latexGroupEnd(i) ?: return null
                    val body = tex.substring(i + 1, end - 1)
                    when (command) {
                        "ovalbox", "Ovalbox" -> {
                            if (options.isNotEmpty()) return null
                            val outline = if (command == "Ovalbox") OvalOutline.THICK else OvalOutline.THIN
                            parts += LatexPart(boxText(body), outline)
                        }
                        "fbox", "framebox" -> {
                            if (command == "fbox" && options.isNotEmpty()) return null
                            val width = options.firstOrNull()?.let { parseLatexLength(it) ?: return null }
                            val alignment = options.getOrNull(1) ?: "c"
                            if (alignment !in setOf("l", "c", "r")) return null
                            val strut = FrameStrut.find(body)
                            val height = strut?.groupValues?.get(1)?.let { parseLatexLength(it) ?: return null }
                            val content = if (strut != null) body.substring(strut.range.last + 1) else body
                            val centering = FrameCentering.find(content)
                            val label = if (centering != null) content.substring(centering.range.last + 1) else content
                            parts += LatexPart(boxText(label), frame = LatexFrame(width, height, alignment,
                                border = LatexLength(1f, "px")))
                        }
                        "bbox" -> {
                            if (options.size > 1) return null
                            val frame = parseBbox(options.firstOrNull().orEmpty()) ?: return null
                            parts += LatexPart(boxText(body), frame = frame)
                        }
                    }
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

private val FrameCommands = setOf("ovalbox", "Ovalbox", "fbox", "framebox", "bbox")
private val FrameStrut = Regex("""^\s*\\rule\s*\{\s*0(?:pt|px|em|ex|cm|mm|in)?\s*\}\s*\{([^{}]+)\}""")
private val FrameCentering = Regex("""^\s*\\centering(?![a-zA-Z])\s*""")
private val FrameLength = Regex("""([0-9]{1,3}(?:\.[0-9]{1,3})?|\.[0-9]{1,3})\s*(pt|px|em|ex|cm|mm|in)?""")
private val FrameColor = Regex("""[a-zA-Z]{2,20}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3}""")
private val BboxBorder = Regex("""([^\s]+)\s+solid(?:\s+([^\s]+))?""")

private fun boxText(body: String): String {
    val text = body.trim()
    if (text.startsWith('$') && text.endsWith('$') && text.length > 2 && '$' !in text.substring(1, text.lastIndex)) {
        return text.substring(1, text.lastIndex)
    }
    return if (PlainCjkBox.matches(body)) "\\text{$body}" else body
}

private fun parseLatexLength(source: String): LatexLength? {
    val match = FrameLength.matchEntire(source.trim()) ?: return null
    val value = match.groupValues[1].toFloatOrNull() ?: return null
    val unit = match.groupValues[2]
    if (unit.isEmpty() && value != 0f) return null
    return LatexLength(value, unit.ifEmpty { "px" })
}

private fun parseBbox(options: String): LatexFrame? {
    if (options.length > 256) return null
    var frame = LatexFrame()
    for (option in options.split(',')) {
        val value = option.trim()
        if (value.isEmpty()) continue
        when {
            value.startsWith("border-radius:") -> {
                val radius = parseLatexLength(value.substringAfter(':')) ?: return null
                frame = frame.copy(radius = radius)
            }
            value.startsWith("border:") -> {
                val border = BboxBorder.matchEntire(value.substringAfter(':').trim()) ?: return null
                val width = parseLatexLength(border.groupValues[1]) ?: return null
                val color = border.groupValues[2].ifEmpty { null }
                if (color != null && !FrameColor.matches(color) && color != "currentColor") return null
                frame = frame.copy(border = width, borderColor = color)
            }
            FrameColor.matches(value) -> frame = frame.copy(background = value)
            else -> frame = frame.copy(padding = parseLatexLength(value) ?: return null)
        }
    }
    return frame
}
