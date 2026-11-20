package com.simplechat.app.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlin.math.expm1

private const val NanosPerMs = 1_000_000L
private const val FrameBudgetMs = 16L
private const val MaxCooldownMs = 96L

/** Leave some time for touch and scrolling after a slow frame. A stall must not freeze the tail. */
internal fun adaptiveCooldownMs(frameCostMs: Long): Long =
    (frameCostMs.coerceIn(FrameBudgetMs, FrameBudgetMs + MaxCooldownMs / 2) - FrameBudgetMs) * 2

/** Consume a fraction of the backlog per unit of wall time, preserving sub-character credit at 120 Hz. */
internal class RevealBudget {
    private var fraction = 0.0

    fun advance(pending: Int, elapsedNs: Long): Int {
        val ms = (elapsedNs.toDouble() / NanosPerMs).coerceIn(0.0, 100.0)
        // At least 60 UTF-16 units/sec for short tails. A burst loses half its backlog in ~70ms.
        val earned = fraction + maxOf(ms * 0.06, pending * -expm1(-ms / 100.0))
        val whole = earned.toInt()
        fraction = earned - whole
        return whole.coerceAtMost(pending)
    }
}

private fun String.completePrefix(): String = if (lastOrNull()?.isHighSurrogate() == true) dropLast(1) else this

/** Start with the existing snapshot (including on list re-entry). Animate subsequent appends only. */
@Composable
internal fun rememberSmoothReveal(streaming: Boolean, value: String, markdownLinks: Boolean = false): String {
    if (!streaming) return value
    val links = remember(value, markdownLinks) {
        if (markdownLinks) streamingLinkBoundaries(value) else null
    }
    val visible = if (links == null) value else value.substring(0, links.visibleEnd)
    if (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return visible.completePrefix()

    var shown by remember { mutableStateOf(visible.completePrefix()) }
    val latest by rememberUpdatedState(visible)
    val latestLinks by rememberUpdatedState(links)
    LaunchedEffect(Unit) {
        // New targets never cancel a reveal in progress. Once caught up (or waiting on a split
        // surrogate), sleep until the next actual source change, without polling or requesting frames.
        snapshotFlow { latest }.collect {
            if (latest == shown) return@collect
            val budget = RevealBudget()
            var frameNs = withFrameNanos { it }
            var elapsedNs = 16_666_667L // One nominal frame so even the first small delta advances.
            while (latest != shown) {
                val target = latest
                val current = shown
                val next = if (target.startsWith(current)) {
                    val advance = budget.advance(target.length - current.length, elapsedNs)
                    val boundary = nextRevealEnd(target, current.length, advance)
                    val end = latestLinks?.completeThrough(boundary) ?: boundary
                    if (advance > 0 && end == current.length) break // Waiting on the low surrogate.
                    if (end == current.length) current else target.substring(0, end)
                } else {
                    target.completePrefix() // Regeneration, edit or page replacement.
                }
                val committed = next != current
                if (committed) shown = next
                val previousNs = frameNs
                frameNs = withFrameNanos { it }
                elapsedNs = frameNs - previousNs
                val cooldown = if (committed) adaptiveCooldownMs(elapsedNs / NanosPerMs) else 0L
                if (cooldown > 0 && latest != shown) {
                    delay(cooldown)
                    frameNs = withFrameNanos { it }
                    elapsedNs = frameNs - previousNs
                }
            }
        }
    }
    return shown
}
