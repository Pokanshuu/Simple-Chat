package com.simplechat.app.ui.chat

import com.simplechat.app.ui.markdown.rememberSearchTextDecoration
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.FoldAnimMs
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.ProcessTextStyle
import com.simplechat.app.ui.markdown.rememberSmoothReveal

/**
 * 过程面板（深度思考 / 未来的工具调用）。
 *
 * 三态，**由思维链自己的进度驱动，而不是整条回复的进度**：
 *
 * 1. **思维链还在流**（[reasoningStreaming]）：Tail 模式。高度随内容增长、
 *    上限 [TailHeight]（约 4 行），内容**底部对齐** —— 新行从下方接入，
 *    旧行向上溢出被裁切，因此永远只看得见最后几行，且布局**不跳动**。
 *    溢出时顶部有渐隐遮罩。
 * 2. **思维链写完 + 折叠**：一行摘要「已思考（用时 8 秒）⌄」。
 * 3. **思维链写完 + 展开**：完整内容，高度不限。
 *
 * ⚠️ 判据是 [reasoningStreaming]（= 还在流 **且** 思维链没写完），
 * **不是**整条消息的 `streaming`。正文一开始写，思维链就结束了，
 * 那一刻就该折叠 —— 否则用户会在正文已经写了一大半时，
 * 还看到思考栏挂着「正在思考」的尾巴在滚。
 *
 * 命名刻意用 ProcessPanel 而非 ThinkingPanel：未来接入工具调用时，
 * 把工具调用卡片行塞进内容区即可复用同一套尾随 / 折叠逻辑。
 */
@Composable
fun ProcessPanel(
    reasoning: String,
    reasoningStreaming: Boolean,
    reasoningSeconds: Long?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 思考尾部流式期间禁用尺寸动画：animateContentSize 追不上每几十毫秒一次的
            // 增量，会让高度持续滞后，把最新的那行裁掉。
            // 折叠 / 展开 / 思维链结束时的收缩都走动画 —— **时长与气泡同一档**，
            // 两处都是"折叠"，节奏不一致会很明显。
            .then(
                if (reasoningStreaming) {
                    Modifier
                } else {
                    Modifier.animateContentSize(tween(FoldAnimMs, easing = MotionEasing))
                },
            ),
    ) {
        // ── 标题行 ────────────────────────────────────────
        // 与扩展通道**同一个**组件、同一套措辞（见 ThinkingHeader）
        ThinkingHeader(
            label = thinkingLabel(running = reasoningStreaming, seconds = reasoningSeconds),
            expanded = expanded,
            enabled = !reasoningStreaming,
            onToggle = onToggleExpand,
        )

        Spacer(Modifier.size(8.dp))

        when {
            reasoningStreaming -> TailContent(reasoning)
            expanded && reasoning.length > LARGE_MESSAGE_CHARS -> LargeMessageBody(reasoning, null)
            expanded -> FullContent(reasoning)
            else -> Unit
        }
    }
}


/**
 * Tail 模式：高度 = min(内容高度, [TailHeight])，内容底部对齐。
 *
 * 之所以不用固定高度 + 滚动：固定高度在内容短时会留下大片空白，
 * 且滚动到顶时会跳动。底部对齐 + 溢出裁切则是平滑的「日志尾巴」。
 *
 * @param gutter 是否改用时间线的「圆点 + 细竖线」引导列（[ThinkingGutter]）。
 *   扩展通道的思考块定稿后就是这个样子，流式期间必须长成同一副，
 *   否则「正在思考 → 已思考」切换的那一帧整块内容会往右跳。
 *   OpenAI 通道保持默认的 3dp 竖线 —— 它的展开态也是 3dp，两态自洽。
 */
@Composable
internal fun TailContent(reasoning: String, gutter: Boolean = false) {
    val colors = LocalChatColors.current
    val density = LocalDensity.current
    val maxPx = with(density) { TailHeight.toPx() }

    /*
     * ★ **只排版看得见的那几行**（见 [visibleTailOf]）。
     *
     * 思考可以写到几千字，而尾巴盒子最高 [TailHeight]（约 4 行）——
     * 原先把整段交给下面那个 `Text`（`unbounded = true`），
     * 等于每 50ms 把全部行重排一遍。思考越长这一笔越贵，主线程扛不住就掉帧，
     * 表现就是"思考内容滚起来一卡一卡"。
     */
    // 先推进整段思考的显示位置，再截取窗口，否则窗口滑动会被误判为文本替换。
    val revealed = rememberSmoothReveal(streaming = true, value = reasoning)
    val tail = remember(revealed) { visibleTailOf(revealed) }

    var contentPx by remember { mutableIntStateOf(0) }
    val overflowing = contentPx > maxPx + 1

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = TailHeight)
            .clipToBounds(),
    ) {
        // Keep the fade inside the bounded viewport, below the timeline markers.
        Box(
            modifier = if (!overflowing) Modifier else Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val fade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = 22.dp.toPx())
                    onDrawWithContent {
                        drawContent()
                        drawRect(fade, blendMode = BlendMode.DstIn)
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                    .fillMaxWidth()
                    .onSizeChanged { contentPx = it.height }
                    // 左侧竖线用绘制实现：内容高度是动态的，用 fillMaxHeight 会拿不到约束
                    .drawBehind {
                        if (gutter) return@drawBehind
                        val barWidth = 3.dp.toPx()
                        drawRoundRect(
                            color = colors.processBar,
                            size = Size(barWidth, size.height),
                            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                        )
                    }
                    .padding(start = if (gutter) ThinkingContentInset else 15.dp),
            ) {
                Text(
                    text = tail,
                    style = ProcessTextStyle,
                    color = colors.processText,
                )
            }
        }

        /*
         * 引导列画在**最上层**，高度取盒子（而不是全文）：
         * 内容向上溢出时只有文字被裁掉，圆点固定在盒子顶部。
         * 放在渐隐之后，避免圆点被遮罩冲淡。
         */
        if (gutter) {
            ThinkingGutter(modifier = Modifier.matchParentSize())
        }
    }
}

/** 展开态：完整内容，高度不限。竖线用 IntrinsicSize 跟随文字高度。 */
@Composable
private fun FullContent(reasoning: String) {
    val colors = LocalChatColors.current
    val (searchDecoration, onTextLayout) = rememberSearchTextDecoration(reasoning)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(colors.processBar),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = reasoning,
            style = ProcessTextStyle,
            color = colors.processText,
            modifier = Modifier.weight(1f).then(searchDecoration),
            onTextLayout = onTextLayout,
        )
    }
}

/** Tail 模式的高度上限：约 4 行正文。 */
private val TailHeight = 96.dp

/**
 * 「尾巴」最多保留多少**字符**。
 *
 * 取值只要**远超**一屏能显示的量就够了（这里是 [TailHeight] 的十几倍），
 * 目的不是"正好取一屏"，而是把解析量从"整段思考"压成"一个常数"。
 */
private const val TailKeepChars = 1200

/**
 * 取思考文本**末尾**够显示的那一段。
 *
 * ### 为什么从行边界切
 *
 * `Text` 的换行是逐行推进的：从哪儿开始，决定了第一行在哪断，从而影响后面每一行。
 * 若从**硬换行**之后切，切出来的每一行与整段渲染**逐字一致**；
 * 只有"整段没有任何换行"时才会退化成按字符切 —— 那也只有**最上面那一行**
 * 可能不同，而它正好在顶部渐隐遮罩底下。
 */
internal fun visibleTailOf(reasoning: String): String {
    if (reasoning.length <= TailKeepChars) return reasoning
    val cut = reasoning.length - TailKeepChars
    val newline = reasoning.indexOf('\n', cut)
    // 长段落末尾刚收到换行时，不能把仍在视口内的上一段整段截掉。
    if (newline in cut until cut + TailKeepChars / 2) return reasoning.substring(newline + 1)
    val start = if (reasoning[cut].isLowSurrogate() && reasoning[cut - 1].isHighSurrogate()) cut - 1 else cut
    return reasoning.substring(start)
}
