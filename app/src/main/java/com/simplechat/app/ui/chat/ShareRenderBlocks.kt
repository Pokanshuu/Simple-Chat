package com.simplechat.app.ui.chat

import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.ui.markdown.ContentSegment
import com.simplechat.app.ui.markdown.normalizeListIndent
import com.simplechat.app.ui.markdown.splitMarkdownBlocks
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

internal data class ShareImageText(val text: String, val reasoning: Boolean = false)

/** Use one reasoning source; the legacy field often contains the same text as the timeline. */
internal fun shareImageText(message: UiMessage, hideReasoning: Boolean = true): List<ShareImageText> {
    val body = ShareImageText(message.content)
    if (hideReasoning || message.role != MessageRole.ASSISTANT) return listOf(body)

    if (message.timeline.none { it is ReplyBlock.Thinking && it.thinking.isNotBlank() }) {
        val reasoning = message.reasoning?.takeIf { it.isNotBlank() } ?: return listOf(body)
        return listOf(ShareImageText(reasoning, reasoning = true), body)
    }

    return buildList {
        val pending = StringBuilder()
        var hasAnswer = false
        var isReasoning = false
        fun append(text: String, reasoning: Boolean) {
            if (reasoning != isReasoning && pending.isNotEmpty()) {
                add(ShareImageText(pending.toString(), isReasoning))
                pending.clear()
            }
            isReasoning = reasoning
            pending.append(text)
        }
        for (block in message.timeline) when (block) {
            is ReplyBlock.Text -> {
                if (block.text.isNotEmpty()) {
                    hasAnswer = true
                    append(block.text, reasoning = false)
                }
            }
            is ReplyBlock.Thinking -> if (block.thinking.isNotBlank()) append(block.thinking, reasoning = true)
            else -> Unit
        }
        if (pending.isNotEmpty()) add(ShareImageText(pending.toString(), isReasoning))
        if (!hasAnswer) add(body)
    }
}

internal fun shareSectionUsesMarkdown(user: Boolean, excerpt: Boolean, reasoning: Boolean): Boolean =
    !reasoning && (!user || excerpt)

internal sealed interface ShareRenderBlock {
    data class Text(val source: String, val markdown: Boolean) : ShareRenderBlock
    data class Formula(val tex: String, val source: String) : ShareRenderBlock
    data class Table(val source: String, val rowCount: Int) : ShareRenderBlock
}

/** Protect complete tables before splitting math: a formula in a cell must not tear apart its row. */
internal fun shareRenderBlocks(text: String, markdown: Boolean): List<ShareRenderBlock> {
    if (!markdown) return boundedShareChunks(text).map { ShareRenderBlock.Text(it, false) }
    val result = mutableListOf<ShareRenderBlock>()
    fun prose(source: String) {
        com.simplechat.app.ui.markdown.displayMathSegments(source).forEach { segment ->
            when (segment) {
                is ContentSegment.Formula -> result += ShareRenderBlock.Formula(segment.tex, segment.source)
                is ContentSegment.Text -> {
                    val document = com.simplechat.app.ui.markdown.normalizedMarkdownDocument(segment.text)
                    val normalized = normalizeListIndent(document.markdown)
                    splitMarkdownBlocks(normalized).forEach { block ->
                        val original = document.restore(block.text)
                        if (original.length <= 6000) result += ShareRenderBlock.Text(original, true)
                        else {
                            val lines = block.text.lines()
                            val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").matchEntire(lines.first())
                            if (fence != null) {
                                val marker = fence.groupValues[1]
                                val closed = lines.last().trim().let { it.length >= marker.length && it.all { c -> c == marker[0] } }
                                val body = lines.drop(1).let { if (closed) it.dropLast(1) else it }.joinToString("\n")
                                boundedShareChunks(body).forEach { result += ShareRenderBlock.Text("${lines.first()}\n$it\n$marker", true) }
                            } else boundedShareChunks(block.text, protected = document.tokenRanges(block.text)).forEach {
                                result += ShareRenderBlock.Text(document.restore(it), true)
                            }
                        }
                    }
                }
            }
        }
    }
    val document = com.simplechat.app.ui.markdown.inlineMathDocument(text)
    val encoded = document.markdown
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(encoded)
    var position = 0
    root.children.filter { it.type == GFMElementTypes.TABLE }.forEach { table ->
        prose(document.restore(encoded.substring(position, table.startOffset)))
        result += ShareRenderBlock.Table(document.restore(encoded.substring(table.startOffset, table.endOffset)),
            table.children.count { it.type == GFMElementTypes.ROW })
        position = table.endOffset
    }
    prose(document.restore(encoded.substring(position)))
    return result
}

/** Bound native text layout, preserving every character and UTF-16 surrogate pair. */
internal fun boundedShareChunks(text: String, limit: Int = 6000, protected: List<IntRange> = emptyList()): List<String> {
    require(limit >= 2)
    val result = mutableListOf<String>()
    var start = 0
    var rangeIndex = 0
    while (start < text.length) {
        var end = minOf(start + limit, text.length)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline > start + limit / 2) end = newline + 1
            while (rangeIndex < protected.size && protected[rangeIndex].last < end) rangeIndex++
            val range = protected.getOrNull(rangeIndex)
            if (range != null && end > range.first) {
                end = if (range.first > start) range.first else range.last + 1
            }
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        result += text.substring(start, end)
        start = end
    }
    return result
}
