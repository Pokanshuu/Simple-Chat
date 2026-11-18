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

internal data class InlineMathDocument(val markdown: String, val formulas: Map<String, String>)

/** Keep inline spans inside their original paragraph; only explicit display math splits a block. */
internal fun displayMathSegments(source: String): List<ContentSegment> = buildList {
    val paragraph = StringBuilder()
    fun flush() { if (paragraph.isNotEmpty()) { add(ContentSegment.Text(paragraph.toString())); paragraph.clear() } }
    splitMath(source).forEach { segment -> when (segment) {
        is ContentSegment.Text -> paragraph.append(segment.text)
        is ContentSegment.Formula -> if (segment.display) { flush(); add(segment) }
            else paragraph.append("\\(").append(segment.tex).append("\\)")
    } }
    flush()
}

/** Replace only closed inline math with AST-safe tokens, preserving surrounding Markdown containers. */
internal fun inlineMathDocument(source: String): InlineMathDocument {
    val segments = splitMath(source)
    if (segments.none { it is ContentSegment.Formula && !it.display }) return InlineMathDocument(source, emptyMap())
    var prefix = "SCMATH"
    while (source.contains(prefix)) prefix += "X"
    val formulas = linkedMapOf<String, String>()
    val markdown = buildString {
        segments.forEach { segment -> when (segment) {
            is ContentSegment.Text -> append(segment.text)
            is ContentSegment.Formula -> if (segment.display) append("\\[").append(segment.tex).append("\\]") else {
                val id = "$prefix${formulas.size}"
                formulas[id] = segment.tex
                append('`').append(id).append('`')
            }
        } }
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
    val inline = remember(document, measurer, config, dark) { document.formulas.mapNotNull { (id, tex) ->
        val presentation = prepareLatexPresentation(tex)
        presentation.nativeTex?.let { measurer.inlineContent(it, config, dark) }?.let { id to it }
    }.toMap() }
    val annotator = remember(document, inline) { markdownAnnotator { content, child ->
        if (child.type != MarkdownElementTypes.CODE_SPAN) false else {
            val token = content.substring(child.startOffset, child.endOffset).trim('`')
            val tex = document.formulas[token]
            if (tex == null) false else {
                if (inline.containsKey(token)) appendInlineContent(token, tex) else append(tex)
                true
            }
        }
    } }
    return annotator to markdownInlineContent(inline)
}
