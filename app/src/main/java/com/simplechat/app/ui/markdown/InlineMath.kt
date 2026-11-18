package com.simplechat.app.ui.markdown

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import com.simplechat.app.ui.theme.LocalIsDarkTheme
import org.intellij.markdown.MarkdownElementTypes

internal data class InlineMathDocument(val markdown: String, val formulas: Map<String, ContentSegment.Formula>)

/** Keep inline spans inside their original paragraph; only explicit display math splits a block. */
internal fun displayMathSegments(source: String): List<ContentSegment> = buildList {
    val paragraph = StringBuilder()
    fun flush() { if (paragraph.isNotEmpty()) { add(ContentSegment.Text(paragraph.toString())); paragraph.clear() } }
    splitMath(source).forEach { segment -> when (segment) {
        is ContentSegment.Text -> paragraph.append(segment.text)
        is ContentSegment.Formula -> if (segment.display) { flush(); add(segment) }
            else paragraph.append(segment.source)
    } }
    flush()
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

/** The Markdown engine lays out text and formulas in one paragraph, including inside lists and quotes. */
@Composable
internal fun rememberInlineMath(document: InlineMathDocument): Pair<com.mikepenz.markdown.model.MarkdownAnnotator,
    com.mikepenz.markdown.model.MarkdownInlineContent> {
    val dark = LocalIsDarkTheme.current
    val color = MaterialTheme.colorScheme.onBackground
    val fonts = LocalMarkdownExport.current?.fonts
    val fontSize = MaterialTheme.typography.bodyLarge.fontSize
    val config = remember(dark, color, fonts, fontSize) { LatexConfig(fontSize = fontSize, fontFamilies = fonts,
        theme = if (dark) LatexTheme.dark(color = color) else LatexTheme.light(color = color)) }
    val measurer = rememberLatexMeasurer(config)
    val inline = remember(document, measurer, config, dark) { document.formulas.mapNotNull { (id, formula) ->
        val presentation = prepareLatexPresentation(formula.tex)
        presentation.nativeTex?.let { measurer.inlineContent(it, config, dark) }?.let { id to it }
    }.toMap() }
    val annotator = remember(document, inline) { markdownAnnotator { content, child ->
        if (child.type != MarkdownElementTypes.CODE_SPAN) false else {
            val tokens = content.substring(child.startOffset, child.endOffset).trim('`').split(' ')
            if (!tokens.all(document.formulas::containsKey)) false else {
                for (token in tokens) {
                    val formula = document.formulas.getValue(token)
                    if (inline.containsKey(token)) appendInlineContent(token, formula.tex) else append(formula.source)
                }
                true
            }
        }
    } }
    return annotator to markdownInlineContent(inline)
}
