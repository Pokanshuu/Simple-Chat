package com.simplechat.app.ui.markdown

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val FadeNanos = 150_000_000L

/** Each append keeps its own birth time even if the next token arrives mid-fade. */
private class FadeRange(val start: Int, val end: Int, val born: Long) {
    var path: Path? = null
}

private class ProseFade(initial: String) {
    var previous = initial
    val ranges = ArrayDeque<FadeRange>()
    val redraw = mutableLongStateOf(0L)
    var animation: Job? = null

    fun measure(layout: TextLayoutResult, now: Long, enabled: Boolean) {
        val text = layout.layoutInput.text.text
        if (!enabled || !text.startsWith(previous)) ranges.clear()
        else if (text.length > previous.length) ranges.addLast(FadeRange(previous.length, text.length, now))
        previous = text
        expire(now)
        // Reflow, RTL and wrapping can move a range. Reuse these paths until the next layout.
        for (range in ranges) range.path = layout.getPathForRange(range.start, range.end)
    }

    fun expire(now: Long) {
        while (ranges.isNotEmpty() && now - ranges.first().born >= FadeNanos) ranges.removeFirst()
    }
}

/** Live plain text on the chat surface. Only the newly measured character ranges are veiled. */
@Composable
internal fun StreamingProse(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val fade = remember { ProseFade(text) }
    val scope = rememberCoroutineScope()
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }.drawWithContent {
            drawContent()
            val frame = fade.redraw.longValue
            for (range in fade.ranges) {
                val progress = ((frame - range.born).toFloat() / FadeNanos).coerceIn(0f, 1f)
                range.path?.let { drawPath(it, Color.Black, alpha = 0.8f * (1f - progress), blendMode = BlendMode.DstOut) }
            }
        },
        onTextLayout = { layout ->
            val enabled = scope.coroutineContext[MotionDurationScale]?.scaleFactor != 0f
            fade.measure(layout, System.nanoTime(), enabled)
            if (fade.ranges.isNotEmpty() && fade.animation?.isActive != true) {
                fade.animation = scope.launch {
                    while (fade.ranges.isNotEmpty()) withFrameNanos {
                        val now = System.nanoTime()
                        fade.expire(now)
                        fade.redraw.longValue = now
                    }
                }
            }
        },
    )
}
