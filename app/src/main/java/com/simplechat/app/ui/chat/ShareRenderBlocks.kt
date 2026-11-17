package com.simplechat.app.ui.chat

import com.simplechat.app.ui.markdown.ContentSegment
import com.simplechat.app.ui.markdown.normalizeHtml
import com.simplechat.app.ui.markdown.normalizeListIndent
import com.simplechat.app.ui.markdown.normalizeSoftBreaks
import com.simplechat.app.ui.markdown.splitMarkdownBlocks
import com.simplechat.app.ui.markdown.splitMath
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

internal sealed interface ShareRenderBlock {
    data class Text(val source: String, val markdown: Boolean) : ShareRenderBlock
    data class Formula(val tex: String) : ShareRenderBlock
    data class Table(val source: String, val rowCount: Int) : ShareRenderBlock
}

/** Protect complete tables before splitting math: a formula in a cell must not tear apart its row. */
internal fun shareRenderBlocks(text: String, markdown: Boolean): List<ShareRenderBlock> {
    if (!markdown) return boundedShareChunks(text).map { ShareRenderBlock.Text(it, false) }
    val result = mutableListOf<ShareRenderBlock>()
    fun prose(source: String) {
        splitMath(source).forEach { segment ->
            when (segment) {
                is ContentSegment.Formula -> result += ShareRenderBlock.Formula(segment.tex)
                is ContentSegment.Text -> {
                    val normalized = normalizeListIndent(normalizeSoftBreaks(normalizeHtml(segment.text)))
                    splitMarkdownBlocks(normalized).forEach { block ->
                        if (block.text.length <= 6000) result += ShareRenderBlock.Text(block.text, true)
                        else {
                            val lines = block.text.lines()
                            val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").matchEntire(lines.first())
                            if (fence != null) {
                                val marker = fence.groupValues[1]
                                val closed = lines.last().trim().let { it.length >= marker.length && it.all { c -> c == marker[0] } }
                                val body = lines.drop(1).let { if (closed) it.dropLast(1) else it }.joinToString("\n")
                                boundedShareChunks(body).forEach { result += ShareRenderBlock.Text("${lines.first()}\n$it\n$marker", true) }
                            } else boundedShareChunks(block.text).forEach { result += ShareRenderBlock.Text(it, true) }
                        }
                    }
                }
            }
        }
    }
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
    var position = 0
    root.children.filter { it.type == GFMElementTypes.TABLE }.forEach { table ->
        prose(text.substring(position, table.startOffset))
        result += ShareRenderBlock.Table(text.substring(table.startOffset, table.endOffset),
            table.children.count { it.type == GFMElementTypes.ROW })
        position = table.endOffset
    }
    prose(text.substring(position))
    return result
}

/** Bound native text layout, preserving every character and UTF-16 surrogate pair. */
internal fun boundedShareChunks(text: String, limit: Int = 6000): List<String> {
    require(limit >= 2)
    val result = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline > start + limit / 2) end = newline + 1
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        result += text.substring(start, end)
        start = end
    }
    return result
}
