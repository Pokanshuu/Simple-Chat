package com.simplechat.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.LocalUseSimpleCatIcon
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SimpleCatLoadingIndicator
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 思考栏的**唯一**标题行 —— 两个协议通道共用。
 *
 * ### 为什么要有这个文件
 *
 * 同一件事（"思考过没有、用了多久"）原先在两个面板里各写了一套：
 * 扩展通道（`TimelinePanel`）是「🧠 已思考（用时 3 秒）」，OpenAI 通道
 * （`ProcessPanel`）是「已深度思考 · 3 秒」且没有图标、左边还差 8dp。
 * 同一个 App 里同一行信息两种长相，看起来像两个功能。
 *
 * 现在措辞、图标、间距、点击热区只有一份（[thinkingLabel] 管措辞）。
 * 两个面板各自的**差异**只留在该留的地方：扩展通道多出搜索/浏览步骤，
 * OpenAI 通道只有纯思维链。
 *
 * @param label    标题文案，用 [thinkingLabel] 生成。
 * @param enabled  能否点开折叠区。流式期间为 false（展开会把进度顶走）。
 */
@Composable
internal fun ThinkingHeader(
    label: String,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current

    Row(
        modifier = modifier
            .clip(PillShape)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            // 往左顶回 8dp：让**图标**的左缘与正文左边界对齐。
            // （只是视觉左移，不改变布局宽度 —— 用 offset 而不是负 padding）
            .offset(x = (-8).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = AppIcons.Sparkles,
            contentDescription = null,
            tint = colors.processText,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = colors.processText,
        )
        // 进行中不给箭头：还在写，没有"折叠"这个动作可做
        if (enabled) {
            Icon(
                imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                contentDescription = stringResource(
                    if (expanded) R.string.process_collapse else R.string.process_expand,
                ),
                tint = colors.processText,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 「加载中」占位 —— 首 token 还没到、正文一片空白时的过渡。
 *
 * 为什么需要它：请求发出到第一个 token 之间，界面原先**什么都没有**。
 * 思考型模型在长上下文里这一段可以走十几秒，用户看到的就是"点了发送，然后一片空白"，
 * 很像是没发出去。
 *
 * 形状刻意和 [ThinkingHeader] 一致（同样的图标位、同样的 `processText` 色、
 * 同样的左缘对齐）：从"加载中"切到"正在思考"时，视觉上是**同一行在换字**，
 * 而不是凭空冒出一个新控件。
 */
@Composable
internal fun WaitingIndicator(
    selectedPhrase: String?,
    useThemeColor: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (useThemeColor) MaterialTheme.colorScheme.primary else LocalChatColors.current.processText
    val phrase = (selectedPhrase ?: stringArrayResource(R.array.reply_waiting_phrases).let { defaults ->
        val index = rememberSaveable { defaults.indices.random() }
        defaults[index]
    }).trimEnd('.', '…').trimEnd()
    val transition = rememberInfiniteTransition(label = "waitingFirstToken")
    val dots by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "waitingDots",
    )

    Row(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            // 与思考栏同一条补偿：让图标左缘对齐正文左边界
            .offset(x = (-8).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (LocalUseSimpleCatIcon.current) {
            SimpleCatLoadingIndicator(Modifier.size(15.dp), color)
        } else {
            val rotation by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
                label = "waitingRotation",
            )
            Canvas(modifier = Modifier.size(15.dp)) {
                val stroke = 2.dp.toPx()
                drawArc(
                    color = color,
                    startAngle = rotation,
                    sweepAngle = 250f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                )
            }
        }
        Text(
            // 省略号按秒循环出现：三个点，一格一个
            text = phrase + ".".repeat(dots.toInt().coerceIn(0, 3)),
            style = MaterialTheme.typography.titleSmall,
            color = color,
        )
    }
}

/**
 * 思考栏标题文案 —— **两个通道共用这一套措辞**。
 *
 * 改为 `Res.get` 而不是 `stringResource`：调用点要传字符串给 [ThinkingHeader]，
 * 而在组合外取字符串更省事（[Res.get] 内部走 `LocalContext`，见它的实现）。
 */
internal fun thinkingLabel(
    running: Boolean,
    seconds: Long?,
    stopped: Boolean = false,
): String = when {
    running -> Res.get(R.string.thinking_running)
    stopped -> Res.get(R.string.thinking_stopped)
    seconds != null -> Res.get(R.string.thinking_done_seconds, seconds)
    else -> Res.get(R.string.thinking_done)
}
