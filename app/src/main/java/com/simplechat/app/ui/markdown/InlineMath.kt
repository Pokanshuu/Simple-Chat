package com.simplechat.app.ui.markdown

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.staticCompositionLocalOf
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import com.simplechat.app.ui.theme.LocalIsDarkTheme
import org.intellij.markdown.MarkdownElementTypes

internal data class InlineMathDocument(val markdown: String, val formulas: Map<String, ContentSegment.Formula>) {
    private val tokens = formulas.keys.firstOrNull()?.dropLastWhile(Char::isDigit)?.let { prefix ->
        Regex("\u0060($prefix\\d+(?: $prefix\\d+)*)\u0060")
    }

    fun forBlock(text: String): InlineMathDocument {
        val entries = tokens?.findAll(text)?.flatMap { it.groupValues[1].split(' ').asSequence() }
            ?.mapNotNull { id -> formulas[id]?.let { id to it } }?.toMap().orEmpty()
        return InlineMathDocument(text, entries)
    }

    fun tokenRanges(text: String): List<IntRange> = tokens?.findAll(text)?.map { it.range }?.toList().orEmpty()

    fun restore(text: String): String = tokens?.replace(text) { match ->
        val ids = match.groupValues[1].split(' ')
        if (ids.all(formulas::containsKey)) ids.joinToString("") { formulas.getValue(it).source } else match.value
    } ?: text
}

/** Keep inline spans inside their original paragraph; only explicit display math splits a block. */
internal fun displayMathSegments(source: String): List<ContentSegment> {
    if (source.isEmpty()) return emptyList()
    if (!source.contains("$$") && !source.contains("\\[") && !source.contains("\\begin{")) return listOf(ContentSegment.Text(source))
    return buildList {
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isEmpty()) return
            add(ContentSegment.Text(paragraph.toString()))
            paragraph.clear()
        }
        for (segment in splitMath(source)) {
            when (segment) {
                is ContentSegment.Text -> paragraph.append(segment.text)
                is ContentSegment.Formula -> if (segment.display) {
                    flush()
                    add(segment)
                } else {
                    paragraph.append(segment.source)
                }
            }
        }
        flush()
    }
}

/** Replace only closed inline math with AST-safe tokens, preserving surrounding Markdown containers. */
internal fun inlineMathDocument(source: String): InlineMathDocument {
    val segments = splitMath(source)
    if (segments.none { it is ContentSegment.Formula && !it.display }) return InlineMathDocument(source, emptyMap())
    var prefix = "SCMATH"
    while (source.contains(prefix)) prefix += "X"
    val formulas = linkedMapOf<String, ContentSegment.Formula>()
    val markdown = buildString {
        var inToken = false
        for (segment in segments) {
            if (segment is ContentSegment.Formula && !segment.display) {
                // Adjacent code-span markers would form a single longer backtick run.
                append(if (inToken) ' ' else '`')
                val id = "$prefix${formulas.size}"
                formulas[id] = segment
                append(id)
                inToken = true
            } else {
                if (inToken) append('`')
                inToken = false
                append(when (segment) {
                    is ContentSegment.Text -> segment.text
                    is ContentSegment.Formula -> segment.source
                })
            }
        }
        if (inToken) append('`')
    }
    return InlineMathDocument(markdown, formulas)
}

internal val LocalInlineMathDocument = staticCompositionLocalOf<InlineMathDocument?> { null }

/** The Markdown engine lays out text and formulas in one paragraph, including inside lists and quotes. */
@Composable
internal fun rememberInlineMath(
    document: InlineMathDocument,
    maxWidth: Int,
    fontSize: TextUnit = MaterialTheme.typography.bodyLarge.fontSize,
): Pair<com.mikepenz.markdown.model.MarkdownAnnotator,
    com.mikepenz.markdown.model.MarkdownInlineContent> {
    val dark = LocalIsDarkTheme.current
    val color = MaterialTheme.colorScheme.onBackground
    val fonts = LocalMarkdownExport.current?.fonts
    val config = remember(dark, color, fonts, fontSize) { LatexConfig(fontSize = fontSize, fontFamilies = fonts,
        theme = if (dark) LatexTheme.dark(color = color) else LatexTheme.light(color = color)) }
    val measurer = rememberLatexMeasurer(config)
    val density = LocalDensity.current
    val presentations = remember(document.formulas) { document.formulas.mapValues { prepareLatexPresentation(it.value.tex) } }
    val measured = remember(presentations, measurer, config, dark, density) {
        presentations.mapNotNull { (id, presentation) ->
            measurer.inlinePresentation(presentation, config, dark, color, density)?.let { id to it }
        }.toMap()
    }
    val exporting = fonts != null
    val inline = remember(measured, maxWidth, density, exporting) {
        measured.mapValues { (_, formula) -> formula.fitWidth(maxWidth, density, exporting) }
    }
    val annotator = remember(document, inline) { markdownAnnotator { content, child ->
        if (child.type != MarkdownElementTypes.CODE_SPAN) false else {
            val tokens = content.substring(child.startOffset, child.endOffset).trim('`').split(' ')
            if (!tokens.all(document.formulas::containsKey)) false else {
                for (token in tokens) {
                    val formula = document.formulas.getValue(token)
                    if (inline.containsKey(token)) {
                        // Compose placeholders cannot cross paragraph boundaries.
                        appendInlineContent(token, formula.source.lineSequence().joinToString(" "))
                    } else append(formula.source)
                }
                true
            }
        }
    } }
    return annotator to markdownInlineContent(inline)
}
