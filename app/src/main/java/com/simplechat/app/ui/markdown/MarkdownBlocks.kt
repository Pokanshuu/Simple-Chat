package com.simplechat.app.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
 * - **标记流式尾块**：渲染器按解析结果决定是否保留高度
 *
 * 分工：分块负责"降低单次解析量"（见 [splitMarkdownBlocks]），
 * 上游的帧对齐采样继续当"频率上限"（只是不许它更慢），两者叠加。
 *
 * [StreamingProse] 只在普通正文的尾块内绘制新增文字的淡入。尾块的 key 在追加时稳定，
 * 使已有文字与每批新增文字的淡入进度都能保留。
 *
 * @param source      已 [normalizeSoftBreaks] 的 Markdown 文本。
 * @param streaming   是否处于流式态；为 true 时标记最后一块。
 * @param blockGap    块间距（外层 Column 那一层）。
 * @param renderBlock 单块渲染器，接收块文本和流式尾块标记。
 */
@Composable
internal fun MarkdownTextBlocks(
    source: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
    blockGap: Dp = 12.dp,
    renderBlock: @Composable (text: String, isTail: Boolean) -> Unit,
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
                Box(modifier = Modifier.fillMaxWidth()) {
                    renderBlock(block.text, isTail)
                }
            }
        }
    }
}
