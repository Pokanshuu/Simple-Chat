package com.simplechat.app.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 可拖拽的对话滚动条 + 跟随胶囊（docs/41 §51）。
 *
 * 与旧版细滚动条的区别：
 * - 滚动时先出现「细条」（Thin，3dp）；持续滚动 ≥150ms 变粗成「Ready」（6dp），
 *   这时整条 32dp 宽度都是命中带、可以按住拖动；
 * - 拖动中用 `requestScrollToItem` O(1) 定位（绝不 `scrollBy(超大值)`，见
 *   `ScrollToBottom.kt` 里那次 ANR 的说明）；
 * - 拖动时右侧跟一枚胶囊，显示目标消息首行的前 13 个码点。
 *
 * 状态一律在**绘制期**读：`dragUnit` / `mode` 变化只让这一层重画，
 * 拖动每帧不触发重组。
 *
 * ⚠️ 这一层必须铺满消息区（`fillMaxSize`），但**不挂任何 pointerInput**：
 * 手势在父节点的 [scrollBarDrag] 上，这里只负责画。铺满才能让胶囊有横向空间。
 */

/** 滚动条视觉/交互状态。 */
internal class ChatScrollBarState {
    var mode: ScrollBarMode by mutableStateOf(ScrollBarMode.HIDDEN)

    /** 拖动中的目标位置（0..1，相对可用行程）；null = 用布局推导的位置。 */
    var dragUnit: Float? by mutableStateOf(null)

    /** 胶囊文本；null 表示不显示。 */
    var previewText: String? by mutableStateOf(null)

    /** 拖动期间冻结的几何，避免列表滚动导致滑块在手指下跳。 */
    var dragTrack: Float = 0f
    var dragThumbHeight: Float = 0f
    var dragTravel: Float = 0f

    /** 最近一次布局尺寸（命中测试用；由手势修饰符维护）。 */
    var layoutWidthPx: Float = 0f
    var layoutHeightPx: Float = 0f

    /**
     * 拖动时的落点请求。
     *
     * 不能直接 `requestScrollToItem`：拖动开始时上一段 fling 可能还在跑，
     * 它会覆盖 pending 的请求。这里用 `collectLatest + scrollToItem` 以同一
     * 优先级顶掉 fling，并且永远只施加最新的落点（docs/41 §51.5.4）。
     */
    val jumpRequests = kotlinx.coroutines.flow.MutableSharedFlow<Int>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    fun requestRow(row: Int) {
        jumpRequests.tryEmit(row)
    }

    val interactive: Boolean get() = mode == ScrollBarMode.READY || mode == ScrollBarMode.DRAGGING

    fun endDrag() {
        dragUnit = null
        previewText = null
        if (mode == ScrollBarMode.DRAGGING) mode = ScrollBarMode.READY
    }
}

internal enum class ScrollBarMode { HIDDEN, THIN, READY, DRAGGING, FADING }

internal enum class ScrollBarHit { NONE, THUMB, TRACK }

/** 滑块几何（像素）；绘制与命中测试共用一份。 */
internal data class ScrollBarGeometry(
    val unit: Float,
    val thumbTop: Float,
    val thumbHeight: Float,
    val travel: Float,
    val track: Float,
)

private val ScrollBarThinWidth = 3.dp
private val ScrollBarReadyWidth = 6.dp
private val ScrollBarMinThumb = 28.dp
private val ScrollBarReadyMinThumb = 48.dp
private val ScrollBarHitWidth = 32.dp
private val ScrollBarEndMargin = 3.dp
private val ScrollBarThinSlop = 12.dp

private const val ScrollBarReadyDelayMs = 150L
private const val ScrollBarThinFadeMs = 700L
private const val ScrollBarReadyFadeMs = 900L
private const val ScrollBarFadeAnimMs = 200L

/**
 * 绘制层：细条 / 粗条 / 拖动中的胶囊。
 *
 * @param state 手势与绘制共用的状态（由调用方 remember）。
 * @param listState 列表状态。
 */
@Composable
internal fun ChatScrollBar(
    state: ChatScrollBarState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val density = LocalDensity.current

    // 滚动开始：细条 → 持续 150ms 后变成可拖的粗条。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling) return@collectLatest
            if (state.mode == ScrollBarMode.DRAGGING) return@collectLatest
            state.mode = ScrollBarMode.THIN
            delay(ScrollBarReadyDelayMs)
            if (listState.isScrollInProgress && state.mode != ScrollBarMode.DRAGGING) {
                state.mode = ScrollBarMode.READY
            }
        }
    }
    // 拖动落点：collectLatest 只保留最新目标；scrollToItem 会取消仍在跑的 fling。
    LaunchedEffect(listState) {
        state.jumpRequests.collectLatest { row ->
            val total = listState.layoutInfo.totalItemsCount
            if (row in 0 until total) listState.scrollToItem(row, 0)
        }
    }
    // 停手后淡出：Ready 给 900ms 窗口（够用户抓），Thin 700ms。
    LaunchedEffect(state.mode) {
        val wait = when (state.mode) {
            ScrollBarMode.READY -> ScrollBarReadyFadeMs
            ScrollBarMode.THIN -> ScrollBarThinFadeMs
            else -> null
        } ?: return@LaunchedEffect
        delay(wait)
        if (state.mode == ScrollBarMode.READY || state.mode == ScrollBarMode.THIN) {
            state.mode = ScrollBarMode.FADING
            delay(ScrollBarFadeAnimMs)
            if (state.mode == ScrollBarMode.FADING) state.mode = ScrollBarMode.HIDDEN
        }
    }

    val mode = state.mode
    val alpha by animateFloatAsState(
        targetValue = when (mode) {
            ScrollBarMode.HIDDEN, ScrollBarMode.FADING -> 0f
            ScrollBarMode.READY, ScrollBarMode.DRAGGING -> 0.55f
            ScrollBarMode.THIN -> 0.38f
        },
        animationSpec = tween(160, easing = MotionEasing),
        label = "scrollBarAlpha",
    )
    val width by animateDpAsState(
        targetValue = if (mode == ScrollBarMode.READY || mode == ScrollBarMode.DRAGGING) {
            ScrollBarReadyWidth
        } else {
            ScrollBarThinWidth
        },
        animationSpec = tween(ScrollBarReadyDelayMs.toInt(), easing = MotionEasing),
        label = "scrollBarWidth",
    )
    val minThumbPx = with(density) {
        (if (mode == ScrollBarMode.READY || mode == ScrollBarMode.DRAGGING) {
            ScrollBarReadyMinThumb
        } else {
            ScrollBarMinThumb
        }).toPx()
    }
    val endMarginPx = with(density) { ScrollBarEndMargin.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            // 一整屏宽的绘制层：滚动条贴右缘，胶囊也从右缘往左排。
            // 所有滚动状态只在绘制期读 → 不重组、不测量。
            modifier = Modifier.fillMaxSize().graphicsLayer(),
        ) {
            val a = alpha
            if (a <= 0.01f) return@Canvas
            val geometry = scrollBarGeometry(listState, size.height, minThumbPx)
            val unit = state.dragUnit ?: geometry.unit
            val thumbHeight = (if (state.dragUnit != null && state.dragThumbHeight > 0f) {
                state.dragThumbHeight
            } else {
                geometry.thumbHeight
            })
            val travel = (size.height - thumbHeight).coerceAtLeast(0f)
            val top = scrollBarThumbTopForUnit(unit, travel)
            val barWidth = width.toPx()
            drawRoundRect(
                color = colors.processText,
                topLeft = Offset(size.width - barWidth - endMarginPx, top),
                size = Size(barWidth, thumbHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
                alpha = a,
            )
        }

        if (mode == ScrollBarMode.DRAGGING) {
            state.previewText?.let { text ->
                ScrollBarPreviewCapsule(
                    text = text,
                    state = state,
                    endMargin = with(density) { ScrollBarEndMargin + 6.dp },
                )
            }
        }
    }
}

/**
 * 跟手胶囊：垂直位置由 `graphicsLayer` 平移（绘制期读状态），
 * 文本只在目标行变化时更新，不参与逐帧重组。
 *
 * 用 `inverseSurface` / `inverseOnSurface`（系统 tooltip 那对配色）而不是卡片色：
 * 浅色主题下卡片色是白的，压在白色页面上等于隐形。
 */
@Composable
private fun ScrollBarPreviewCapsule(
    text: String,
    state: ChatScrollBarState,
    endMargin: androidx.compose.ui.unit.Dp,
) {
    val colors = LocalChatColors.current
    val background = MaterialTheme.colorScheme.inverseSurface
    val foreground = MaterialTheme.colorScheme.inverseOnSurface
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(end = endMargin)
            .widthIn(max = 200.dp)
            .graphicsLayer {
                val track = state.layoutHeightPx
                val thumbHeight = state.dragThumbHeight.takeIf { it > 0f } ?: 48.dp.toPx()
                val travel = (track - thumbHeight).coerceAtLeast(0f)
                val unit = state.dragUnit ?: 0f
                val thumbCenter = unit * travel + thumbHeight / 2f
                val maxTop = (track - size.height - 16.dp.toPx()).coerceAtLeast(0f)
                translationY = (thumbCenter - size.height / 2f).coerceIn(0f, maxTop)
                translationX = state.layoutWidthPx - size.width - endMargin.toPx()
            }
            .background(background, PillShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

// ── 纯函数（internal，JVM 可测）──────────────────────────

/** 滑块高度：可见比例 × 轨道，夹在 [minThumb] 与轨道之间。 */
internal fun scrollBarThumbHeight(track: Float, visibleFraction: Float, minThumb: Float): Float =
    (track * visibleFraction.coerceIn(0f, 1f)).coerceIn(minThumb.coerceAtMost(track), track)

/** 0..1 的拖动位置 → 滑块顶部像素。 */
internal fun scrollBarThumbTopForUnit(unit: Float, travel: Float): Float =
    travel.coerceAtLeast(0f) * unit.coerceIn(0f, 1f)

/** 滑块顶部像素 → 0..1 的拖动位置。 */
internal fun scrollBarUnitForThumbTop(thumbTop: Float, travel: Float): Float =
    if (travel <= 0f) 0f else (thumbTop / travel).coerceIn(0f, 1f)

/** 拖动位置 → 目标行下标（行级定位，MVP 不细分行内偏移）。 */
internal fun scrollBarRowForUnit(unit: Float, totalRows: Int): Int =
    if (totalRows <= 0) 0 else (unit.coerceIn(0f, 1f) * (totalRows - 1)).roundToInt().coerceIn(0, totalRows - 1)

/**
 * 滑动越过两端时的橡皮筋：越界越多、实际位移越小；终点最多到 `overshoot` 的 40%。
 * 松手后由下一帧的布局位置自然复位。
 */
internal fun scrollBarDampedUnit(rawUnit: Float, overshoot: Float = 0.06f): Float = when {
    rawUnit < 0f -> -((-rawUnit) * overshoot / (overshoot + -rawUnit))
    rawUnit > 1f -> 1f + ((rawUnit - 1f) * overshoot / (overshoot + (rawUnit - 1f)))
    else -> rawUnit
}

/** 从列表布局推导滑块几何（与绘制共用同一套公式，保证所见即所拖）。 */
internal fun scrollBarGeometry(
    listState: LazyListState,
    track: Float,
    minThumb: Float,
): ScrollBarGeometry {
    val fullThumb = scrollBarThumbHeight(track, 1f, minThumb)
    val empty = ScrollBarGeometry(0f, 0f, fullThumb, 0f, track)
    val info = listState.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total <= 0 || visible.isEmpty() || track <= 0f) return empty
    val back = listState.canScrollBackward
    val forward = listState.canScrollForward
    if (!back && !forward) return empty
    val first = visible.first()
    val last = visible.last()
    val start = if (back) {
        scrollBarItemPosition(
            index = first.index,
            offset = first.offset,
            extent = first.size + if (first.index == total - 1) info.afterContentPadding else info.mainAxisItemSpacing,
            viewportEdge = info.viewportStartOffset,
            leadingPadding = if (first.index == 0) info.beforeContentPadding else 0,
        )
    } else {
        0f
    }
    val end = if (forward) {
        scrollBarItemPosition(
            index = last.index,
            offset = last.offset,
            extent = last.size + if (last.index == total - 1) info.afterContentPadding else info.mainAxisItemSpacing,
            viewportEdge = info.viewportEndOffset,
            leadingPadding = if (last.index == 0) info.beforeContentPadding else 0,
        )
    } else {
        total.toFloat()
    }
    val span = (end - start).coerceAtLeast(0f)
    val fraction = (span / total).coerceIn(0f, 1f)
    val thumbHeight = scrollBarThumbHeight(track, fraction, minThumb)
    val travel = (track - thumbHeight).coerceAtLeast(0f)
    val top = scrollBarThumbTop(start, span, total, travel)
    return ScrollBarGeometry(
        unit = scrollBarUnitForThumbTop(top, travel),
        thumbTop = top,
        thumbHeight = thumbHeight,
        travel = travel,
        track = track,
    )
}

/**
 * 命中测试。
 *
 * - `Ready` / `Dragging`：整条 32dp 命中带，滑块内是 [ScrollBarHit.THUMB]，
 *   其余是 [ScrollBarHit.TRACK]（点轨道直接跳）；
 * - `Thin`：只有滑块中心 ±（半高 + 12dp）算 THUMB，其余不拦截 ——
 *   保证"轻滑"时右缘的普通滚动/点击不被抢走。
 */
internal fun scrollBarHitTest(
    x: Float,
    y: Float,
    layoutWidth: Float,
    geometry: ScrollBarGeometry,
    interactive: Boolean,
    hitWidth: Float,
    thinSlop: Float,
): ScrollBarHit {
    if (layoutWidth <= 0f) return ScrollBarHit.NONE
    if (x < layoutWidth - hitWidth) return ScrollBarHit.NONE
    val top = geometry.thumbTop
    val bottom = geometry.thumbTop + geometry.thumbHeight
    if (interactive) {
        return if (y >= top && y <= bottom) ScrollBarHit.THUMB else ScrollBarHit.TRACK
    }
    val center = (top + bottom) / 2f
    return if (abs(y - center) <= geometry.thumbHeight / 2f + thinSlop) ScrollBarHit.THUMB else ScrollBarHit.NONE
}

// ── 手势 ─────────────────────────────────────────────────

/**
 * 拖动 / 点轨道。挂在**父节点**上（与 `messageIndexDrag` 同一层，且放在它前面）：
 * 带 `pointerInput` 的兄弟浮层会挡住列表的命中路径（docs/07 §34.7），挂父节点则
 * 列表、输入栏都是后代，谁也不挡。
 */
internal fun Modifier.scrollBarDrag(
    state: ChatScrollBarState,
    listState: LazyListState,
    rowsProvider: () -> List<MessageListEntry>,
    previewProvider: (MessageListEntry) -> String,
    onDetach: () -> Unit,
    onResume: () -> Unit,
    enabled: Boolean = true,
): Modifier = this
    .onSizeChanged {
        state.layoutWidthPx = it.width.toFloat()
        state.layoutHeightPx = it.height.toFloat()
    }
    .pointerInput(state, listState, enabled) {
        if (!enabled) return@pointerInput
        val hitWidthPx = ScrollBarHitWidth.toPx()
        val thinSlopPx = ScrollBarThinSlop.toPx()
        val minThumbPx = ScrollBarMinThumb.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (state.layoutHeightPx <= 0f) return@awaitEachGesture
            val geometry = scrollBarGeometry(listState, state.layoutHeightPx, minThumbPx)
            val hit = scrollBarHitTest(
                x = down.position.x,
                y = down.position.y,
                layoutWidth = state.layoutWidthPx,
                geometry = geometry,
                interactive = state.interactive,
                hitWidth = hitWidthPx,
                thinSlop = thinSlopPx,
            )
            if (hit == ScrollBarHit.NONE) return@awaitEachGesture
            val downTime = down.uptimeMillis
            var claimed = false
            val trackTap = hit == ScrollBarHit.TRACK
            // 冻结几何：拖动期间列表在滚，滑块高度会变，不冻的话它会从手指下溜走。
            state.dragTrack = geometry.track
            state.dragThumbHeight = geometry.thumbHeight
            state.dragTravel = geometry.travel
            // 滑块内外都按"当前滑块顶部"对齐，按下不跳。
            val anchor = down.position.y - geometry.thumbTop
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    if (!claimed && trackTap) {
                        // 点轨道跳转：消费 UP，避免同时点到底下的消息。
                        change.consume()
                        val unit = ((down.position.y - anchor) / geometry.travel.coerceAtLeast(1f))
                        applyScrollBarTarget(state, listState, unit, rowsProvider, previewProvider)
                    }
                    break
                }
                if (!claimed) {
                    if (change.uptimeMillis - downTime >= viewConfiguration.longPressTimeoutMillis) {
                        break // 长按让位给消息菜单
                    }
                    // 用**相对按下的累计位移**判 slop：逐事件位移在慢速拖动时很小，
                    // 用 delta 会永远判不到超过 slop。
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (kotlin.math.hypot(dx, dy) > viewConfiguration.touchSlop) {
                        if (abs(dx) > abs(dy)) break // 横向让位（系统返回等）
                        claimed = true
                        state.mode = ScrollBarMode.DRAGGING
                        change.consume()
                        onDetach()
                    }
                }
                if (claimed) {
                    change.consume()
                    val raw = (change.position.y - anchor) / geometry.travel.coerceAtLeast(1f)
                    applyScrollBarTarget(
                        state = state,
                        listState = listState,
                        unit = scrollBarDampedUnit(raw),
                        rowsProvider = rowsProvider,
                        previewProvider = previewProvider,
                    )
                }
            }
            state.endDrag()
            if (claimed) onResume()
        }
    }

/** 拖动/点轨道 → 写状态 + O(1) 定位 + 更新胶囊文本（行变化才更新）。 */
private fun applyScrollBarTarget(
    state: ChatScrollBarState,
    listState: LazyListState,
    unit: Float,
    rowsProvider: () -> List<MessageListEntry>,
    previewProvider: (MessageListEntry) -> String,
) {
    val clamped = unit.coerceIn(0f, 1f)
    state.dragUnit = clamped
    val rows = rowsProvider()
    val row = scrollBarRowForUnit(clamped, rows.size)
    state.previewText = rows.getOrNull(row)?.let(previewProvider)
    state.requestRow(row)
}
