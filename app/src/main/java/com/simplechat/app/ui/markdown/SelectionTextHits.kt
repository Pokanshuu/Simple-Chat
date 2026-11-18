package com.simplechat.app.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.IntOffset
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions

/** Reuses measured paragraphs; selection startup never reparses source or waits for a fake long press. */
internal class SelectionTextHits {
    internal class Hit { var coordinates: LayoutCoordinates? = null; var layout: TextLayoutResult? = null }
    private val hits = linkedSetOf<Hit>()
    internal fun add(hit: Hit) { hits += hit }
    internal fun remove(hit: Hit) { hits -= hit }
    internal fun layouts(): List<TextLayoutResult> = hits.mapNotNull { it.layout }

    fun selectAt(point: IntOffset, state: SelectionState): Boolean {
        val position = Offset(point.x.toFloat(), point.y.toFloat())
        val ordered = hits.filter { it.coordinates?.isAttached == true && it.layout != null }
            .sortedWith(compareBy({ it.coordinates!!.boundsInWindow().top }, { it.coordinates!!.boundsInWindow().left }))
        val hit = ordered.firstOrNull { it.coordinates!!.boundsInWindow().contains(position) } ?: return false
        val layout = hit.layout ?: return false
        val text = layout.layoutInput.text.text
        if (text.isEmpty()) return false
        val local = hit.coordinates!!.windowToLocal(position)
        val offset = layout.getOffsetForPosition(local).coerceIn(0, text.lastIndex)
        val word = layout.getWordBoundary(offset)
        val sameBefore = ordered.takeWhile { it !== hit }.count { it.layout!!.layoutInput.text.text == text }
        val native = state.getSelectableTexts()
        var occurrence = 0
        var base = 0
        for (item in native) {
            if (item.text == text && occurrence++ == sameBefore) {
                state.select(TextRange(base + word.start, base + maxOf(word.end, word.start + 1)))
                return state.selectedTexts.isNotEmpty()
            }
            base += item.length
        }
        return false
    }
}

internal val LocalSelectionTextHits = staticCompositionLocalOf<SelectionTextHits?> { null }
private val LocalSelectionHit = staticCompositionLocalOf<SelectionTextHits.Hit?> { null }

@Composable
internal fun selectionMeasuredComponents(original: MarkdownComponents): MarkdownComponents {
    val registry = LocalSelectionTextHits.current ?: return original
    fun wrap(component: MarkdownComponent): MarkdownComponent = { model ->
        val hit = remember { SelectionTextHits.Hit() }
        DisposableEffect(registry, hit) { registry.add(hit); onDispose { registry.remove(hit) } }
        Box(Modifier.onGloballyPositioned { hit.coordinates = it }) {
            CompositionLocalProvider(LocalSelectionHit provides hit) { component(model) }
        }
    }
    return remember(original, registry) { object : MarkdownComponents by original {
        override val text = wrap(original.text)
        override val paragraph = wrap(original.paragraph)
        override val heading1 = wrap(original.heading1)
        override val heading2 = wrap(original.heading2)
        override val heading3 = wrap(original.heading3)
        override val heading4 = wrap(original.heading4)
        override val heading5 = wrap(original.heading5)
        override val heading6 = wrap(original.heading6)
    } }
}

@Composable
internal fun rememberSelectionLayoutPainter(): ExtendedSpanPainter? {
    val hit = LocalSelectionHit.current ?: return null
    return remember(hit) { object : ExtendedSpanPainter() {
        override fun decorate(span: SpanStyle, start: Int, end: Int, text: AnnotatedString, builder: AnnotatedString.Builder) = span
        override fun decorate(linkAnnotation: LinkAnnotation, start: Int, end: Int, text: AnnotatedString,
            builder: AnnotatedString.Builder) = linkAnnotation
        override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
            hit.layout = layoutResult
            return SpanDrawInstructions { }
        }
    } }
}
