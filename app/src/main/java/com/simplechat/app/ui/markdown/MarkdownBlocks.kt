package com.simplechat.app.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 逐块渲染 Markdown —— 把单次整篇渲染换成"一块一次"。
 *
 * - **已完成的块**：文本不变 → `key` 不变 → Compose 跳过重组 → 不重解析
 * - **最后一块**（流式尾块）：只有它随 token 变化而重解析
 * - **尾块套高度下限**：半截语法引起的折行/换排版不再让高度回缩
 *
 * 分工：分块负责"降低单次解析量"（见 [splitMarkdownBlocks]），
 * 上游的 50ms 采样继续当"频率上限"（只是不许它更慢），两者叠加。
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
                renderBlock(block.text, blockModifier)
            }
        }
    }
}
