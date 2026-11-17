package com.simplechat.app.ui.markdown

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpanPainter
import com.mikepenz.markdown.compose.extendedspans.SpanDrawInstructions
import com.simplechat.app.ui.chat.SearchTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Stable
internal class SearchHighlightState(val request: Long, val query: String) {
    var ready by mutableStateOf(false)
    var located by mutableStateOf(false)
    var owner: Any? = null
    val alpha = Animatable(0f)
}

internal val LocalSearchHighlight = compositionLocalOf<SearchHighlightState?> { null }
/** Optional diagnostics for device rendering tests; observes the real animation without changing its timing. */
internal val LocalSearchHighlightObserver = staticCompositionLocalOf<((SearchHighlightState?) -> Unit)?> { null }
internal class SearchViewport(val bounds: Rect, val scrollBy: suspend (Float) -> Float)
internal val LocalSearchViewport = compositionLocalOf<SearchViewport?> { null }
private val LocalSearchAnchor = compositionLocalOf<SearchTextAnchor?> { null }

/** Always keep the ordinary message components; only the drawing overlay is transient. */
@Composable
internal fun SearchHighlightHost(target: SearchTarget?, onFinished: () -> Unit, content: @Composable () -> Unit) {
    // Keep the same renderer subtree after fading. Dropping its anchors rebuilds text semantics
    // and can disturb a long message's viewport even though its visible content is unchanged.
    val retained = remember { mutableStateOf<SearchHighlightState?>(null) }
    val state = remember(target?.request) {
        target?.let { SearchHighlightState(it.request, it.query) } ?: retained.value
    }
    val finish by rememberUpdatedState(onFinished)
    val observer = LocalSearchHighlightObserver.current
    SideEffect {
        retained.value = state
        state?.ready = target?.ready == true
        observer?.invoke(state)
    }
    LaunchedEffect(state, target?.ready) {
        if (state == null) return@LaunchedEffect
        if (target?.ready != true) {
            state.alpha.snapTo(0f)
            return@LaunchedEffect
        }
        val located = withTimeoutOrNull(4000) { snapshotFlow { state.located }.first { it } }
        if (located != null) {
            state.alpha.animateTo(1f, tween(180))
            delay(1100)
            state.alpha.animateTo(0f, tween(900))
        }
        finish()
    }
    CompositionLocalProvider(LocalSearchHighlight provides state) { content() }
}

/** Match rendered text rather than source offsets; markup never becomes visible to the user. */
internal fun renderedSearchRange(text: String, query: String): IntRange? {
    if (query.isEmpty()) return null
    val direct = text.indexOf(query, ignoreCase = true)
    if (direct >= 0) return direct until direct + query.length
    // Rendering may insert a zero-width separator for CJK emphasis boundaries.
    if ('\u200B' !in text && '\uFEFF' !in text) return null
    val positions = text.indices.filter { text[it] != '\u200B' && text[it] != '\uFEFF' }
    val visible = buildString { positions.forEach { append(text[it]) } }
    val hit = visible.indexOf(query, ignoreCase = true)
    return if (hit < 0) null else positions[hit]..positions[hit + query.length - 1]
}

@Stable
private class SearchTextAnchor(val state: SearchHighlightState) {
    val requester = BringIntoViewRequester()
    var bounds by mutableStateOf<Rect?>(null)
    var coordinates: LayoutCoordinates? = null
    fun measured(layout: TextLayoutResult): Path? {
        val range = renderedSearchRange(layout.layoutInput.text.text, state.query) ?: return null
        val path = layout.getPathForRange(range.first, range.last + 1)
        bounds = path.getBounds()
        return path
    }
}

@Composable
private fun LocateSearchAnchor(anchor: SearchTextAnchor) {
    val margin = with(LocalDensity.current) { 20.dp.toPx() }
    val viewport = LocalSearchViewport.current
    LaunchedEffect(anchor.state.request, anchor.state.ready, anchor.bounds, viewport?.bounds) {
        val bounds = anchor.bounds ?: return@LaunchedEffect
        val state = anchor.state
        if (!state.ready || (state.owner != null && state.owner !== anchor)) return@LaunchedEffect
        state.owner = anchor
        if (!state.located) {
            withFrameNanos { }
            anchor.requester.bringIntoView(bounds)
        }
        // BringIntoView only knows the whole LazyColumn. The composer floats over that viewport.
        // Measure the actual keyword in window coordinates and scroll above the measured overlay.
        if (viewport != null && viewport.bounds.height > margin * 2) {
            repeat(3) {
                withFrameNanos { }
                val coordinates = anchor.coordinates?.takeIf { it.isAttached } ?: return@repeat
                val top = coordinates.localToWindow(Offset(bounds.left, bounds.top)).y
                val bottom = coordinates.localToWindow(Offset(bounds.right, bounds.bottom)).y
                val targetY = viewport.bounds.top + viewport.bounds.height * 0.38f
                val delta = (top + bottom) / 2f - targetY
                if (kotlin.math.abs(delta) > 1f) viewport.scrollBy(delta)
            }
        }
        state.located = true
    }
}

/** Wrap existing library components so their own Markdown, images and text styles remain intact. */
internal fun searchableMarkdownComponent(original: MarkdownComponent): MarkdownComponent = { model ->
    SearchMarkdownAnchor(model) { original(model) }
}

@Composable
internal fun rememberSearchableMarkdownComponents(original: MarkdownComponents): MarkdownComponents {
    val state = LocalSearchHighlight.current
    return remember(original, state) {
        if (state == null) original else object : MarkdownComponents by original {
            override val text = searchableMarkdownComponent(original.text)
            override val paragraph = searchableMarkdownComponent(original.paragraph)
            override val heading1 = searchableMarkdownComponent(original.heading1)
            override val heading2 = searchableMarkdownComponent(original.heading2)
            override val heading3 = searchableMarkdownComponent(original.heading3)
            override val heading4 = searchableMarkdownComponent(original.heading4)
            override val heading5 = searchableMarkdownComponent(original.heading5)
            override val heading6 = searchableMarkdownComponent(original.heading6)
            override val setextHeading1 = searchableMarkdownComponent(original.setextHeading1)
            override val setextHeading2 = searchableMarkdownComponent(original.setextHeading2)
        }
    }
}

@Composable
internal fun SearchMarkdownAnchor(model: MarkdownComponentModel, content: @Composable () -> Unit) {
    val state = LocalSearchHighlight.current
    val source = model.content
    val hit = if (state == null) -1 else source.indexOf(state.query, ignoreCase = true)
    val eligible = state != null && hit >= model.node.startOffset && hit < model.node.endOffset
    val anchor = remember(state, model.node) { state?.let { SearchTextAnchor(it) } }
    if (!eligible || anchor == null) content()
    else {
        Box(Modifier.bringIntoViewRequester(anchor.requester).onGloballyPositioned { anchor.coordinates = it }) {
            CompositionLocalProvider(LocalSearchAnchor provides anchor) { content() }
        }
        LocateSearchAnchor(anchor)
    }
}

/** The renderer calls this after parsing, so emphasis, headings and links keep their normal layout. */
@Composable
internal fun rememberSearchSpanPainter(): ExtendedSpanPainter? {
    val anchor = LocalSearchAnchor.current ?: return null
    val highlightColor = MaterialTheme.colorScheme.primary
    return remember(anchor, highlightColor) {
        object : ExtendedSpanPainter() {
            override fun decorate(span: SpanStyle, start: Int, end: Int, text: AnnotatedString,
                builder: AnnotatedString.Builder): SpanStyle = span
            override fun decorate(linkAnnotation: LinkAnnotation, start: Int, end: Int, text: AnnotatedString,
                builder: AnnotatedString.Builder): LinkAnnotation = linkAnnotation
            override fun drawInstructionsFor(layoutResult: TextLayoutResult, color: Color?): SpanDrawInstructions {
                val path = anchor.measured(layoutResult)
                return object : SpanDrawInstructions {
                    override fun DrawScope.draw() {
                        val alpha = anchor.state.alpha.value
                        if (path != null && alpha > 0f) {
                            drawPath(path, highlightColor.copy(alpha = 0.30f * alpha))
                        }
                    }
                }
            }
        }
    }
}

/** Plain user text and code use the same location/fade logic without changing their text styles. */
@Composable
internal fun rememberSearchTextDecoration(text: String): Pair<Modifier, (TextLayoutResult) -> Unit> {
    val state = LocalSearchHighlight.current
    val range = remember(text, state?.query) { state?.let { renderedSearchRange(text, it.query) } }
    val anchor = remember(state, text) { state?.let { SearchTextAnchor(it) } }
    var path by remember(state, text) { mutableStateOf<Path?>(null) }
    val color = MaterialTheme.colorScheme.primary
    val modifier = if (anchor == null || range == null) Modifier else Modifier
        .bringIntoViewRequester(anchor.requester)
        .onGloballyPositioned { anchor.coordinates = it }
        .drawWithContent {
            val alpha = anchor.state.alpha.value
            path?.takeIf { alpha > 0f }?.let { drawPath(it, color.copy(alpha = 0.30f * alpha)) }
            drawContent()
        }
    if (anchor != null && range != null) LocateSearchAnchor(anchor)
    return modifier to { layout -> if (range != null && anchor != null) path = anchor.measured(layout) }
}
