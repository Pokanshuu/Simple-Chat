package com.simplechat.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.theme.ChatColors
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 思考块左侧的「圆点 + 竖线」引导列 —— **进行中与已思考共用这一份**。
 *
 * ### 为什么要有这个文件
 *
 * 时间线里的思考块原先有两套画法：
 *
 * - 已思考（写完之后展开）：16dp 引导列，顶端 7dp 圆点 + 2dp 半透明竖线，正文内缩 26dp；
 * - 正在思考（流式期间）：直接套用 [TailContent] 的尾巴模式，3dp 实色竖线贴在最左边，
 *   正文只内缩 15dp。
 *
 * 同一段思考，写的时候和写完以后左缘差 11dp、线宽差 1dp、颜色差一档 ——
 * 流式结束的瞬间整块内容会往右跳一下。现在两种状态都用这里的一份几何与配色，
 * 切换时只有文字在动。
 *
 * OpenAI 通道（[ProcessPanel]）不在这里的范围内：它没有圆点，展开态与流式态
 * 都用自己那根 3dp 竖线，两态本身是一致的。
 */
internal val ThinkingGutterWidth = 16.dp

/** 引导列与正文之间的间距。 */
internal val ThinkingGutterGap = 10.dp

/** 正文相对面板左缘的缩进：引导列 + 间距。 */
internal val ThinkingContentInset = ThinkingGutterWidth + ThinkingGutterGap

private val ThinkingDotSize = 7.dp
private val ThinkingDotTop = 6.dp

/**
 * 思考竖线宽度（docs/42 §52.8.1）。
 *
 * 2dp → 1.5dp：3x 屏 4.5px、2x 屏 3px，仍是一条清晰细线；取 1dp 的话
 * mdpi 只有 1px，低 DPI 设备上会时隐时现。只缩线宽、**不动槽位**，
 * 所以正文左缘/换行结果与改动前逐字一致。
 */
private val ThinkingLineWidth = 1.5.dp
private val ThinkingLineGap = 4.dp

/**
 * OpenAI 通道（无圆点）的思考竖线颜色。
 *
 * 线宽从 3dp 减到 1.5dp 后，同一份 `processBar` 会淡到几乎看不见，
 * 因此按"宽度 × alpha 守恒"把 alpha 翻倍（上限 0.6 防止自定义主题过实），
 * 跟随浅/深色与主题文件自动变化。
 */
internal fun thinkingRailColor(colors: ChatColors): Color =
    colors.processBar.copy(alpha = (colors.processBar.alpha * 2f).coerceAtMost(0.6f))

/**
 * 画一列「圆点 + 竖线」。
 *
 * 高度由调用方给（`fillMaxHeight` / `matchParentSize`）：竖线从圆点下方留一档空白起步，
 * 画到容器底部再留同样一档；圆点固定在顶部 —— 流式尾巴里的文字向上滚，
 * 圆点是「还在思考」的锚点，不该跟着滚出屏幕。
 */
@Composable
internal fun ThinkingGutter(modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    Canvas(modifier) {
        drawThinkingGutter(
            dotColor = colors.processText.copy(alpha = 0.5f),
            lineColor = colors.processBar.copy(alpha = 0.45f),
        )
    }
}

private fun DrawScope.drawThinkingGutter(dotColor: Color, lineColor: Color) {
    val dot = ThinkingDotSize.toPx()
    val line = ThinkingLineWidth.toPx()
    val gap = ThinkingLineGap.toPx()
    val top = ThinkingDotTop.toPx()
    val centerX = ThinkingGutterWidth.toPx() / 2f

    val lineTop = top + dot + gap
    val lineBottom = size.height - gap
    if (lineBottom > lineTop) {
        drawRoundRect(
            color = lineColor,
            topLeft = Offset(centerX - line / 2f, lineTop),
            size = Size(line, lineBottom - lineTop),
            cornerRadius = CornerRadius(line / 2f, line / 2f),
        )
    }
    drawCircle(
        color = dotColor,
        radius = dot / 2f,
        center = Offset(centerX, top + dot / 2f),
    )
}
