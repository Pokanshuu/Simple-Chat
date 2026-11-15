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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.common.MotionEasing

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
 * ### 入场特效（对齐 Simple-Agent）
 *
 * 新段落出现时 `blur(6px) → 0` + 透明度 `0 → 1`，120ms。
 * 只给**流式尾块**加：历史消息、已完成的块都不动（它们在滚动时不该再演一遍）。
 * 一个段落的 `key` 是它的起始 offset，追加内容时不变 —— 所以这段动画
 * **每个段落只跑一次**，正好是"新段落浮现"。
 *
 * @param source      已 [normalizeSoftBreaks] 的 Markdown 文本。
 * @param streaming   是否处于流式态；为 true 时最后一块套高度下限。
 * @param blockGap    块间距。**取代** mikepenz 的 `markdownPadding(block = …)` ——
 *                    每块是独立 `Markdown()`，块间距只能由外层 Column 给。
 * @param renderBlock 单块渲染器。把原来那段 `Markdown(content = …)` 原样搬进来，
 *                    但 `padding = markdownPadding(block = 0.dp)`、`modifier` 用传入的。
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
                        ),
                ) {
                    renderBlock(block.text, blockModifier)
                }
            }
        }
    }
}

/** 入场时长与最大模糊半径（对齐 Simple-Agent 的 120ms / 6px）。 */
private const val EntranceMs = 120
private val EntranceBlurDp = 6.dp
