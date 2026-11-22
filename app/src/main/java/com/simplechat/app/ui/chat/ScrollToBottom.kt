package com.simplechat.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * 聊天流的滚动助手。
 *
 * ## 前提：列表是**顶部为原点**（对齐官方）
 *
 * `MessageList` 用 `reverseLayout = false`：最旧一条在下标 0（视觉顶部），
 * 最新的在最后。**这是为流式服务的** —— 锚点钉在条目的**顶边**，于是流式消息
 * （永远是最后一条）在自己的**底边**长高，也就是长在视口**下方**：
 * 屏上已显示内容纹丝不动，一行业务代码都不需要。
 *
 * 反过来用 `reverseLayout = true`（锚点钉底边）：同一条消息在底边继续长，
 * 就把已显示内容整体往上顶（实测 0.5s 滑走 861px，手拦不住）。
 *
 * 落点"点进对话就是底部"由 `ChatScreen` 的
 * `LazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE)` 解决 ——
 * 下标给到末尾之外，LazyList 自动夹到最后一条。**是布局参数，不是一次滚动**，
 * 首帧就在底部，不需要重试、不需要等高度量出来。
 */

/**
 * 回到底部。
 *
 * ### ⚠️ 这里**不能**用 `scrollBy(超大 delta)`（这是"第二次打开卡死"的根因）
 *
 * `scrollBy(1e9)` 的写法看起来"丢一个无穷大的 delta，LazyList 自己夹到末尾"，
 * 但 LazyList 的条目高度是**按需测量**的：它不知道后面还有多高，只能
 * **一条一条往下量着吃**这个 delta。于是"从第 279 条滚到第 545 条"变成
 * 逐条组合 + 排版 266 条消息 —— 每条都要完整跑一遍 Markdown 渲染
 * （实测单条约 20–80ms），几百条就是十几秒，主线程直接 ANR。
 *
 * 为什么平时看不出来：**第一次**打开会话时列表里还是空内容，
 * `LazyListState(firstVisibleItemIndex = Int.MAX_VALUE)` 那个"末尾之外"的
 * 请求还没被消费，等新内容到达时自动夹到最后一条 —— 根本不需要滚。
 * 而**第二次**打开时列表里还是上一个会话的内容，那个请求当场被消费掉；
 * 新内容一换，视口停在"旧下标"上（比如 279/546），于是这一步就必须真的滚 266 条，
 * 把整段会话全部渲染一遍。真机现象就是"第一次顺畅、第二次卡死"。
 *
 * 正确做法：**先 O(1) 跳到目标下标**（`scrollToItem` 不测量中间条目），
 * 再只补"最后一条比视口高出来的那一点"（只需量最后一条）。
 */
suspend fun LazyListState.scrollToBottom() {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    // 已经停在最后一条上：只补它下缘与视口底之间的差
    if (layoutInfo.visibleItemsInfo.lastOrNull()?.index != lastIndex) {
        scrollToItem(lastIndex)
    }
    val remaining = distanceToBottom()
    if (remaining > 0f) scrollBy(remaining)
}

/**
 * 是否已贴底。
 *
 * 顶部为原点，"贴底"= **不能再往前滚**（已在内容末尾）。O(1)，
 * 不依赖任何一条的高度、也不需要减总高。
 */
fun LazyListState.isAtBottom(): Boolean = !canScrollForward

/** Keep a bottom-aligned conversation in place as the composer takes more space. */
internal fun LazyListState.advanceBottomForObstruction(growthPx: Int) {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return
    val unusedSpace = (info.viewportEndOffset - info.afterContentPadding - last.offset - last.size)
        .coerceAtLeast(0)
    val advance = growthPx - unusedSpace
    if (advance > 0) {
        requestScrollToItem(firstVisibleItemIndex, firstVisibleItemScrollOffset + advance)
    }
}

/**
 * 到列表底部还差多少像素。
 *
 * 只在**最后一条已经可见**时算得出来 —— LazyList 的条目高度是按需量的，
 * 视口外的条目高度未知。那种情况返回 [Float.POSITIVE_INFINITY]，语义是
 * "够不着，直接跳过去"（见 [followBottom]）。
 *
 * 判据与 `canScrollForward` 同源：**底部内边距也算内容的一部分** ——
 * 让开输入栏的那段留白就在列表自己身上（`contentPadding`），
 * 所以减的是 `viewportEndOffset - afterContentPadding`。
 */
fun LazyListState.distanceToBottom(): Float {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index != info.totalItemsCount - 1) return Float.POSITIVE_INFINITY
    return (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset)
        .coerceAtLeast(0)
        .toFloat()
}

/** Use wall time so following feels similar at 60 Hz and 120 Hz. Clamp long frame stalls. */
internal fun followScrollStep(remaining: Float, frameNanos: Long): Float {
    if (remaining <= 1f) return remaining
    val elapsedMs = (frameNanos / 1_000_000.0).coerceIn(1.0, 32.0)
    return (remaining * -kotlin.math.expm1(-elapsedMs / 38.0)).toFloat()
}

suspend fun LazyListState.followBottom(frameNanos: Long) {
    val remaining = distanceToBottom()
    when {
        !remaining.isFinite() -> scrollToBottom()
        remaining > 0f -> scrollBy(followScrollStep(remaining, frameNanos))
        /*
         * 兜底。走到这里意味着 `canScrollForward` 为真（调用方保证）而距离算出来是 0 ——
         * 只可能是内边距那一段的边界算不准。此时退回已知正确的"直接跳"：
         * 少一次平滑无所谓，**停死**（新字永远停在屏外）才是不能接受的。
         * 而且它落到末尾后 `canScrollForward` 就变假，这条不会一直触发。
         */
        canScrollForward -> scrollToBottom()
    }
}

/** 同 [isAtBottom]，状态化版本，供 UI 订阅（回到底部按钮的显隐）。 */
@Composable
fun rememberIsAtBottom(state: LazyListState): State<Boolean> = remember(state) {
    derivedStateOf { state.isAtBottom() }
}



/** 停手后滚动条多久淡出。 */
private const val ScrollBarFadeDelayMs = 700L

/** 滑块宽度、最短长度。 */
private val ScrollBarWidth = 3.dp
private val ScrollBarMinThumb = 28.dp

/**
 * 安卓原生那种**细滚动条**：滚动时出现、停手后淡出。
 *
 * Compose 没给 `LazyColumn` 自带滚动条，这里照原生观感做一个：细、圆头、半透明，
 * 只在滚动时可见。
 *
 * 用视口两端在各自消息内的像素偏移估算位置和长度。消息高度按需测量，
 * 但滚过一条消息时，两端的比例会连续过渡。
 *
 * ⚠️ **滚动状态一律在绘制期读，组合期一个都不碰。** `layoutInfo` /
 * `firstVisibleItemIndex` 滚一帧变一次，落在组合里就是**每帧重组**；再叠上
 * `BoxWithConstraints`（`SubcomposeLayout`，每次测量都要子组合一次）与
 * `offset(Dp)`（每帧重新布局），这根 3dp 的小条就成了拖垮滚动的那根稻草。
 * 现在整条滑块只是一段绘制：**不重组、不测量、不布局**，每帧至多重画一个圆角矩形。
 */
@Composable
fun VerticalScrollBar(
    state: LazyListState,
    modifier: Modifier = Modifier,
) {
    var scrolling by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collectLatest { inProgress ->
            if (inProgress) {
                scrolling = true
            } else {
                delay(ScrollBarFadeDelayMs)
                scrolling = false
            }
        }
    }
    val fade by animateFloatAsState(
        targetValue = if (scrolling) 1f else 0f,
        animationSpec = tween(200),
        label = "scrollBarAlpha",
    )
    val thumbColor = LocalChatColors.current.processText

    Box(
        modifier = modifier
            .padding(horizontal = 3.dp)
            .width(ScrollBarWidth)
            // Bound draw invalidations from scrolling/fading to this narrow display list.
            .graphicsLayer()
            .drawWithCache {
                val corner = CornerRadius(size.width / 2)
                val track = size.height
                val minThumb = ScrollBarMinThumb.toPx().coerceAtMost(track)
                onDrawBehind {
                    /*
                     * 这里的快照读（`fade`、`state.layoutInfo`）都落在**绘制阶段**：
                     * 只让这一层重画，不触发重组与重新布局。
                     */
                    val alpha = fade
                    if (alpha <= 0.01f || track <= 0f) return@onDrawBehind
                    val canScrollBackward = state.canScrollBackward
                    val canScrollForward = state.canScrollForward
                    if (!canScrollBackward && !canScrollForward) return@onDrawBehind
                    val info = state.layoutInfo
                    val total = info.totalItemsCount
                    val visible = info.visibleItemsInfo
                    if (total <= 0 || visible.isEmpty()) return@onDrawBehind
                    val first = visible.first()
                    val last = visible.last()

                    val start = if (canScrollBackward) scrollBarItemPosition(
                        index = first.index,
                        offset = first.offset,
                        extent = first.size + if (first.index == total - 1) info.afterContentPadding else info.mainAxisItemSpacing,
                        viewportEdge = info.viewportStartOffset,
                        leadingPadding = if (first.index == 0) info.beforeContentPadding else 0,
                    ) else 0f
                    val end = if (canScrollForward) scrollBarItemPosition(
                        index = last.index,
                        offset = last.offset,
                        extent = last.size + if (last.index == total - 1) info.afterContentPadding else info.mainAxisItemSpacing,
                        viewportEdge = info.viewportEndOffset,
                        leadingPadding = if (last.index == 0) info.beforeContentPadding else 0,
                    ) else total.toFloat()

                    val visibleSpan = end - start
                    val visibleFraction = (visibleSpan / total).coerceIn(0f, 1f)
                    val thumbHeight = (track * visibleFraction).coerceIn(minThumb, track)
                    val top = scrollBarThumbTop(start, visibleSpan, total, track - thumbHeight)

                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(0f, top),
                        size = Size(size.width, thumbHeight),
                        cornerRadius = corner,
                        alpha = 0.38f * alpha,
                    )
                }
            },
    )
}

/** Scale item-space progress to the travel left after reserving the (possibly minimum-size) thumb. */
internal fun scrollBarThumbTop(start: Float, visibleSpan: Float, total: Int, travel: Float): Float {
    val scrollableItems = total - visibleSpan
    return if (scrollableItems > 0f) travel * (start / scrollableItems).coerceIn(0f, 1f) else 0f
}

/** Map a viewport edge into item units, including the item's share of spacing and padding. */
internal fun scrollBarItemPosition(index: Int, offset: Int, extent: Int, viewportEdge: Int, leadingPadding: Int): Float {
    val start = offset - leadingPadding
    val length = (extent + leadingPadding).coerceAtLeast(1)
    return index + ((viewportEdge - start).toFloat() / length).coerceIn(0f, 1f)
}
