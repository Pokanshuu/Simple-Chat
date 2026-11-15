package com.simplechat.app.ui.markdown

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simplechat.app.data.LocaleHelper
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.markdownBodyStyle

/**
 * 逐块渲染 Markdown —— 把单次整篇渲染换成"一块一次"。
 *
 * - **已完成的块**：文本不变 → `key` 不变 → Compose 跳过重组 → 不重解析
 * - **最后一块**（流式尾块）：只有它随 token 变化而重解析
 * - **尾块套高度下限**：半截语法引起的折行/换排版不再让高度回缩
 *
 * 分工：分块负责"降低单次解析量"（见 [splitMarkdownBlocks]），
 * 上游的帧对齐采样继续当"频率上限"（只是不许它更慢），两者叠加。
 *
 * ### 两处特效，都只碰绘制
 *
 * - **段落入场**：新段落 `blur(9dp) → 0` + 透明度 `0 → 1`，170ms，只跑一次。
 * - **笔尖遮罩**：尾块最后一个字外面那条渐变（见 [tailPenMask]）。
 *
 * 两处都**不重排文字** —— 流式期间一旦每帧重建文本，排版抖动会立刻显出来
 * （整段像在抖）。特效的代价必须落在"画"上，不能落在"排"上。
 *
 * @param source      已 [normalizeSoftBreaks] 的 Markdown 文本。
 * @param streaming   是否处于流式态；为 true 时最后一块套高度下限并加笔尖遮罩。
 * @param blockGap    块间距（外层 Column 那一层）。
 * @param renderBlock 单块渲染器。`modifier` 用传入的那个。
 */
@Composable
internal fun MarkdownTextBlocks(
    source: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    blockGap: Dp = 12.dp,
    renderBlock: @Composable (text: String, modifier: Modifier) -> Unit,
) {
    // 切块是 O(n) 纯计算；source 没变就不重算。
    val blocks = remember(source) { splitMarkdownBlocks(source) }

    /*
     * 笔尖遮罩要用的三样东西。都从主题/常量取，**不进 renderBlock 的参数** ——
     * 参数一变就跳过不了重组，分块省下的解析又白花了。
     */
    val bodyStyle = markdownBodyStyle(LocaleHelper.isCjkUi(LocalContext.current))
    val measurer = rememberTextMeasurer()
    val fogColor = LocalChatColors.current.pageBackground

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(blockGap),
    ) {
        blocks.forEachIndexed { index, block ->
            val isTail = streaming && index == blocks.lastIndex
            // key 用块起始 offset：追加内容时前面的块 key 不变 → 跳过重组与重解析
            key(block.key) {
                val blockModifier =
                    if (isTail) {
                        Modifier.retainMinHeightWhileStreaming(
                            streaming = true,
                            resetKey = block.key,
                        )
                    } else {
                        Modifier
                    }

                /*
                 * 入场：只有"出现时正在流式"的块才演，且只演一次。
                 *
                 * 判定用 `remember(block.key)` 而不是每帧看 `isTail` —— 一个块从尾块
                 * 变成历史块之后不该再动画；反过来，滚动进视口才组合出来的老块也不该
                 * 忽然模糊一下。
                 */
                val entrance = remember(block.key) {
                    Animatable(if (streaming) 0f else 1f)
                }
                LaunchedEffect(block.key) {
                    if (entrance.value < 1f) {
                        entrance.animateTo(1f, tween(EntranceMs, easing = MotionEasing))
                    }
                }

                val progress = entrance.value
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(progress)
                        .then(
                            // blur 需要 API 31（RenderEffect）；低版本直接跳过，
                            // 只剩淡入 —— 不值得为一个装饰去拉低 minSdk
                            if (progress < 1f && Build.VERSION.SDK_INT >= 31) {
                                Modifier.blur((EntranceBlurDp * (1f - progress)))
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (isTail) {
                                Modifier.tailPenMask(block.text, bodyStyle, measurer, fogColor)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    renderBlock(block.text, blockModifier)
                }
            }
        }
    }
}

/** 入场时长与最大模糊半径（对齐 Simple-Agent 的 120ms / 6px，这里加重一档）。 */
private const val EntranceMs = 170
private val EntranceBlurDp = 9.dp

// ══════════════════════════════════════════════════════════
//  笔尖遮罩：最后一个字外面那条渐变
// ══════════════════════════════════════════════════════════

/**
 * 尾块的笔尖遮罩 —— 贴着"最后一个字"的一条水平渐变，让新字浮出来。
 *
 * ### 只有遮罩，没有逐字改透明度
 *
 * 上一版是按字符改 `SpanStyle` 的颜色：每帧都要重建 `AnnotatedString` 并**重新排版**
 * 整块文字。排版每帧变一次，目标位置就会有亚像素级的漂移，屏幕上看就是**整段在抖**。
 * 特效必须落在绘制上 —— 文字排一次就不动，只叠一层遮罩。
 *
 * ### 为什么遮罩要跟着"笔尖"走，而不是贴着块底
 *
 * 新字永远写在最后一个字**右边**（同一行），贴着块底画一条横带会把**整行**一起洗掉，
 * 看起来就是"一条线把字切了"。遮罩跟着笔尖走，只有最后几个字在渐变里，
 * 往前几个字就离开渐变、变实 —— 每个字都是"淡出来的"，且**没有一行是被整体切掉的**。
 *
 * ### 为什么渐变是静态的（没有时钟）
 *
 * 笔尖在往前跑，字就自己"从渐变里走出来"了，快流慢流都自动适配：
 * 一秒十个字，一个字在渐变里待 0.4 秒；一秒一百个字，待 40 毫秒。
 * 不需要计时器，也就不可能出现"刷新节拍和 vsync 错拍"那种抖动。
 *
 * @param text     尾块文本（已归一化）。
 * @param style    正文样式 —— 必须和渲染器同一份，否则量出来的笔尖位置会偏。
 * @param measurer 文本测量器（内部按文本缓存）。
 * @param fogColor 雾色 = 页面底色。正文画在它上面，所以"盖一层底色"等于"淡出"。
 */
private fun Modifier.tailPenMask(
    text: String,
    style: TextStyle,
    measurer: TextMeasurer,
    fogColor: Color,
): Modifier = drawWithCache {
    val empty = onDrawWithContent { drawContent() }
    if (text.isEmpty() || size.width < 1f) return@drawWithCache empty

    /*
     * 用**同一份样式**按块宽量一遍，拿到"笔尖"（最后一行末端的 x）与最后一行的上下边。
     *
     * 只在 `drawWithCache` 里量：文本或宽度没变就直接复用，流式期间每次提交只量一次，
     * 而且不参与排版 —— 画出来的字仍然来自渲染器。
     */
    val layout = measurer.measure(
        text = AnnotatedString(text),
        style = style,
        constraints = Constraints(maxWidth = size.width.toInt().coerceAtLeast(1)),
    )
    if (layout.lineCount == 0) return@drawWithCache empty

    val line = layout.lineCount - 1
    val lineStart = layout.getLineStart(line)
    val penX = layout.getLineRight(line)
    val top = layout.getLineTop(line)
    val bottom = layout.getLineBottom(line)

    // 渐变左端 = 往前数 [PenFadeChars] 个字的位置；不足就退到行首
    val fadeFromIndex = (text.length - PenFadeChars).coerceAtLeast(lineStart)
    val fromX = layout.getHorizontalPosition(fadeFromIndex, usePrimaryDirection = true)
        .coerceIn(layout.getLineLeft(line), penX)
    val bandHeight = (bottom - top).coerceAtLeast(1f)

    val brush = Brush.horizontalGradient(
        0f to fogColor.copy(alpha = 0f),
        1f to fogColor.copy(alpha = PenFogAlpha),
        startX = fromX,
        endX = penX.coerceAtLeast(fromX + 1f),
    )

    onDrawWithContent {
        drawContent()
        // 渐变段：最后几个字由实到虚
        drawRect(
            brush = brush,
            topLeft = Offset(fromX, top),
            size = Size((penX - fromX).coerceAtLeast(0f), bandHeight),
        )
        // 笔尖右边那一小截（该行已无字）也盖住，免得渐变末端露出一条硬边
        val right = size.width - penX
        if (right > 0f) {
            drawRect(
                color = fogColor.copy(alpha = PenFogAlpha),
                topLeft = Offset(penX, top),
                size = Size(right, bandHeight),
            )
        }
    }
}

/**
 * 渐变覆盖几个字。
 *
 * 8 个字：看得出来一条"由虚到实"的过渡，又不会长到影响阅读。
 * 想更明显就加大这个数（同时把 [PenFogAlpha] 调高），想更淡就反过来。
 */
private const val PenFadeChars = 8

/** 笔尖处的雾浓度。太低看不出渐变，太高会像"最后几个字没写出来"。 */
private const val PenFogAlpha = 0.72f
