package com.simplechat.app.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 点正文里的引用角标时调用，参数是角标的链接（已换成真实 URL）。
 *
 * 由消息气泡提供：它知道这条回复的来源表，能把 URL 对回「第 N 条」并打开搜索结果面板。
 * 没人提供（比如导出图、预设预览）时为 null —— 角标就按普通链接交给系统浏览器。
 */
val LocalCitationClick = compositionLocalOf<((String) -> Unit)?> { null }

/** 角标胶囊在 AnnotatedString 里的记号（只在本 painter 内部往返）。 */
private const val CitationTag = "citation_chip"

/**
 * 引用角标的画法：把「链接文字全是数字」的链接画成一枚小胶囊。
 *
 * ### 为什么认"纯数字链接"而不是认 URL
 * 渲染前 `#cite-N` 已被换成真实 URL（见 `resolveCitationLinks`），URL 上不剩记号；
 * 而正文里写 `[3](https://…)` 的普通链接几乎不存在 —— 真有，画成胶囊也说得通。
 *
 * ### 为什么不用 `RoundedCornerSpanPainter`
 * 它画的是**整行行框**（行高 30sp），角标只有 11sp 左右；行内代码靠上下 margin 硬收，
 * 但角标字号是正文的 0.72 倍，收多少都对不齐。这里按**基线**自己定胶囊的上下边。
 *
 * ⚠️ 与行内代码那个 painter 一样，**每个块各建一个**（见 `MarkdownBlockView`）。
 */
private class CitationChipPainter(
    private val background: Color,
    private val textColor: Color,
    private val density: Density,
    private val onClick: ((String) -> Unit)?,
) : ExtendedSpanPainter() {

    override fun decorate(
        span: SpanStyle, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder,
    ): SpanStyle = span

    override fun decorate(
        linkAnnotation: LinkAnnotation, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder,
    ): LinkAnnotation {
        val url = (linkAnnotation as? LinkAnnotation.Url)?.url ?: return linkAnnotation
        if (end <= start || end > text.length) return linkAnnotation
        if (!text.substring(start, end).all { it.isDigit() }) return linkAnnotation

        builder.addStringAnnotation(CitationTag, url, start, end)
        val listener = onClick?.let { click -> LinkInteractionListener { click(url) } }
            ?: linkAnnotation.linkInteractionListener
        return LinkAnnotation.Url(
            url = url,
            styles = TextLinkStyles(
                style = SpanStyle(
                    color = textColor,
                    fontSize = ChipFontScale.em,
                    fontWeight = FontWeight.Medium,
                ),
            ),
            linkInteractionListener = listener,
        )
    }

    override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
        val text = layoutResult.layoutInput.text
        val ranges = text.getStringAnnotations(CitationTag, 0, text.length)
        if (ranges.isEmpty()) return SpanDrawInstructions { }

        val baseFont = layoutResult.layoutInput.style.fontSize
        val chipFontPx = with(density) {
            (if (baseFont.isSpecified) baseFont.toPx() else 15.sp.toPx()) * ChipFontScale
        }
        val padX = with(density) { 3.5.sp.toPx() }
        val height = chipFontPx * 1.5f
        val radius = CornerRadius(height / 2f)

        val chips = ranges.mapNotNull { range ->
            if (range.end > text.length || range.start >= range.end) return@mapNotNull null
            val line = layoutResult.getLineForOffset(range.start)
            // 跨行的胶囊（两位数恰好被拆开）不画，宁缺毋错
            if (layoutResult.getLineForOffset(range.end - 1) != line) return@mapNotNull null
            val left = layoutResult.getBoundingBox(range.start).left
            val right = layoutResult.getBoundingBox(range.end - 1).right
            // 胶囊的竖直中心对准数字字身的中心（基线往上约 0.36 个字号）
            val centerY = layoutResult.getLineBaseline(line) - chipFontPx * 0.36f
            Offset(minOf(left, right) - padX, centerY - height / 2f) to
                Size(kotlin.math.abs(right - left) + padX * 2f, height)
        }
        return SpanDrawInstructions {
            chips.forEach { (topLeft, size) ->
                drawRoundRect(color = background, topLeft = topLeft, size = size, cornerRadius = radius)
            }
        }
    }
}

private const val ChipFontScale = 0.72f

/** 当前块用的角标 painter。颜色跟主题走，点击走 [LocalCitationClick]。 */
@Composable
internal fun rememberCitationChipPainter(): ExtendedSpanPainter {
    val colors = LocalChatColors.current
    val density = LocalDensity.current
    val onClick = LocalCitationClick.current
    return remember(colors.markdownInlineCode, colors.processText, density, onClick) {
        CitationChipPainter(colors.markdownInlineCode, colors.processText, density, onClick)
    }
}

private fun SpanDrawInstructions(block: DrawScope.() -> Unit): SpanDrawInstructions =
    object : SpanDrawInstructions {
        override fun DrawScope.draw() = block()
    }
