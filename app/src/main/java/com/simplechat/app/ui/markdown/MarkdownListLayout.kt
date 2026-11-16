package com.simplechat.app.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalBulletListHandler
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.LocalOrderedListHandler
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponent
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.model.BulletHandler
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.MarkdownMarkerStyle
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * 列表渲染：**符号右对齐**，缩进与间距按官方客户端实测值。
 *
 * ### 为什么不用库自带的 `MarkdownBulletList` / `MarkdownOrderedList`
 *
 * 库把符号放在 `Box(modifier = markerModifier())` 里，而 Compose 的 `Box` 默认
 * `propagateMinConstraints = false` —— 子 `Text` 拿到的是 `minWidth = 0`，
 * 按自身宽度测量。于是我们早先设的 `textAlign = TextAlign.End` **完全没生效**：
 *
 * - 符号永远贴在符号列的**左边缘**，`1.` 和 `10.` 的"点号"不在同一条竖线上；
 * - 与正文之间的空隙随序号位数变化（1 位数约 11dp，2 位数几乎贴住正文）；
 * - 2 位数（`10.` ≈ 22dp）比 22dp 符号列扣掉 5dp 间隙后的可用宽度还宽 →
 *   溢出/折行。
 *
 * 官方 Web 端（浏览器 `::marker`，`list-style-position: outside`）是**右对齐**：
 * 所有序号以"点号右缘"对齐在正文左缘往回 [ListMarkerGap] 的位置。
 * 自绘列表后符号列宽度按**该列表最宽的序号**测量（见 [orderedMarkerSample]），
 * 才能保证右对齐成立、且 2 位数不会挤出列外。
 *
 * ### 缩进为什么也是自绘的一部分
 *
 * 库对嵌套列表是按 `listIndent × depth` 再加起始内边距；我们的符号列本身就已经
 * 是一层缩进，两者叠加后**嵌套正文比父级正文多缩进 40dp**，而官方只有 ≈1em。
 * 自绘之后每一层只由符号列贡献缩进（[listMinIndent]），与官方的层进节奏一致。
 */
@Composable
internal fun MarkdownListItems(model: MarkdownComponentModel, ordered: Boolean, depth: Int = 0) {
    val content = model.content
    val node = model.node
    // 列表项只在 AST 不变时变化；这里按 node 记忆，避免每帧过滤
    val items = remember(node) {
        node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    }
    if (items.isEmpty()) return

    val typography = LocalMarkdownTypography.current
    val markerStyle = markdownMarkerStyle()
    val handler = if (ordered) LocalOrderedListHandler.current else LocalBulletListHandler.current
    val startNumber = remember(node, content, ordered) {
        if (ordered) startNumberOf(node, content) else 1
    }
    // 基线校正（见 [rememberCjkBaselineShift]）：只对有序号的数字生效
    val baselineShift = rememberCjkBaselineShift(markerStyle)

    /*
     * 符号列宽度 = max(该层最小缩进, 最宽序号 + 间隙)。
     *
     * 用"最宽序号"而不是固定宽度：`10.` 比 `1.` 宽 9dp 左右，固定宽度要么放不下
     * （溢出到正文上），要么把 1 位数列表的正文推得太远。
     */
    val sample = remember(ordered, items.size, startNumber, depth) {
        if (ordered) orderedMarkerSample(startNumber, items.size) else bulletMarkerGlyph(depth)
    }
    val columnWidth = rememberMarkerColumnWidth(sample = sample, style = markerStyle, depth = depth)

    // 嵌套列表与上方正文之间留 0.5em（官方 `li > ul { margin-top: 4px }` 折叠后 ≈8px）
    Column(modifier = Modifier.padding(top = if (depth > 0) NestedListTopGap else 0.dp)) {
        items.forEachIndexed { index, item ->
            MarkdownListItem(
                content = content,
                item = item,
                index = index,
                ordered = ordered,
                startNumber = startNumber,
                depth = depth,
                handler = handler,
                markerStyle = markerStyle,
                columnWidth = columnWidth,
                baselineShift = baselineShift,
            )
        }
    }
}

/** 单个列表项：符号列 + 正文列（正文列吸收剩余宽度，保证长文本按正文列宽折行）。 */
@Composable
private fun MarkdownListItem(
    content: String,
    item: ASTNode,
    index: Int,
    ordered: Boolean,
    startNumber: Int,
    depth: Int,
    handler: BulletHandler,
    markerStyle: TextStyle,
    columnWidth: Dp,
    baselineShift: Dp,
) {
    val typography = LocalMarkdownTypography.current
    val components = LocalMarkdownComponents.current

    // 任务列表：符号位置换成一个自绘复选框（与库的 CHECK_BOX token 对齐）
    val checkbox = remember(item) {
        item.children.getOrNull(1)?.takeIf { it.type == GFMTokenTypes.CHECK_BOX }
    }

    /*
     * 有序号要不要做基线校正，看**本条正文的第一行是什么文字**。
     *
     * 汉字走 CJK 回退字体，行内的 ascent 比纯拉丁数字高 ≈0.23em；两个 `Text`
     * 顶部对齐时，数字的基线就会比正文高出一截（真机反馈"序号没有视觉居中"）。
     * 纯英文条目两边都是拉丁字体，本来就是对齐的，不能再补。
     */
    val markerOffset = remember(item, ordered, content, baselineShift) {
        if (!ordered) {
            0.dp
        } else {
            val startsWithCjk = firstParagraphText(content, item)?.let(::containsCjk) == true
            if (startsWithCjk) baselineShift else 0.dp
        }
    }

    val markerText = remember(item, index, ordered, startNumber, depth, handler) {
        val indicator = if (ordered) {
            item.findChildOfType(MarkdownTokenTypes.LIST_NUMBER)
        } else {
            item.findChildOfType(MarkdownTokenTypes.LIST_BULLET)
        }
        handler.transform(
            type = if (ordered) MarkdownTokenTypes.LIST_NUMBER else MarkdownTokenTypes.LIST_BULLET,
            bullet = indicator?.getUnescapedTextInNode(content),
            index = index,
            listNumber = startNumber,
            depth = depth,
        ).trimEnd()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 官方 `li:not(:first-child) { margin-top: 6px }`；实测客户端还要更松一些
            .padding(top = if (index == 0) 0.dp else ListItemGap),
    ) {
        /*
         * 符号列：固定宽度 + 右对齐（`TopEnd`），列尾留 [ListMarkerGap] 的空隙。
         *
         * `Text` 一定要 `fillMaxWidth()`：不撑满的话 `textAlign = End` 依旧无从体现，
         * 表现就是符号贴左、间隙随位数变化 —— 这正是旧实现的病根。
         */
        Box(
            modifier = Modifier
                .width(columnWidth)
                .padding(end = ListMarkerGap)
                .offset(y = markerOffset),
            contentAlignment = Alignment.TopEnd,
        ) {
            if (checkbox != null) {
                components.checkbox(
                    MarkdownComponentModel(
                        content = content,
                        node = checkbox,
                        typography = typography,
                    ),
                )
            } else {
                Text(
                    text = markerText,
                    style = markerStyle,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 正文列（含段落、代码块、嵌套列表……）：`weight(1f)` 吃掉符号列之外的宽度
        Column(modifier = Modifier.weight(1f)) {
            item.children.forEach { child ->
                when (child.type) {
                    MarkdownElementTypes.ORDERED_LIST -> MarkdownListItems(
                        MarkdownComponentModel(content = content, node = child, typography = typography),
                        ordered = true,
                        depth = depth + 1,
                    )

                    MarkdownElementTypes.UNORDERED_LIST -> MarkdownListItems(
                        MarkdownComponentModel(content = content, node = child, typography = typography),
                        ordered = false,
                        depth = depth + 1,
                    )

                    else -> MarkdownElement(
                        node = child,
                        components = components,
                        content = content,
                        includeSpacer = false,
                    )
                }
            }
        }
    }
}

// ── 尺寸与纯函数（有单测）────────────────────────────────

/** 顶层列表正文相对消息正文左缘的缩进（官方 ≈2em；按我们的内容宽度取 24dp）。 */
internal val TopListIndent = 24.dp

/** 每嵌套一层额外贡献的缩进（官方嵌套列表 ≈0.95em）。 */
internal val NestedListIndent = 16.dp

/** 符号右缘与正文左缘之间的空隙（官方 ≈0.5em）。 */
internal val ListMarkerGap = 6.dp

/** 相邻列表项之间的间距（官方实测 ≈0.8em）。 */
internal val ListItemGap = 10.dp

/** 列表项正文与嵌套列表之间的间距（官方 0.5em）。 */
internal val NestedListTopGap = 8.dp

/** 测量取整的余量：避免浮点误差把 2 位数挤成折行。 */
internal val MarkerColumnEpsilon = 0.5.dp

/** 某一层的**最小**符号列宽（= 正文相对父级正文的最小缩进）。 */
internal fun listMinIndent(depth: Int): Dp =
    if (depth == 0) TopListIndent else NestedListIndent

/** 符号列宽：取"最小缩进"与"最宽序号 + 间隙"的较大者。 */
internal fun markerColumnWidthFor(measuredMarker: Dp, minIndent: Dp): Dp =
    maxOf(minIndent, measuredMarker + ListMarkerGap + MarkerColumnEpsilon)

/**
 * 有序列表里最宽序号的样例文本（用于测量符号列宽度）。
 *
 * 取**最后一项**的序号：序号从 `startNumber` 递增，最后一项位数最多。
 */
internal fun orderedMarkerSample(startNumber: Int, itemCount: Int): String {
    val last = startNumber + (itemCount - 1).coerceAtLeast(0)
    return "$last."
}

/** 无序列表符号按层轮换（官方浏览器默认样式）：实心圆 → 空心圆 → 方块。 */
internal fun bulletMarkerGlyph(depth: Int): String = when (depth % 3) {
    0 -> "•"
    1 -> "◦"
    else -> "▪"
}

/** 列表符号处理器：按层级给不同符号（颜色/字号不在这里定）。 */
internal val BulletMarkers = BulletHandler { _, _, _, _, depth -> bulletMarkerGlyph(depth) }

/** 库组件入口：给 `markdownComponents(...)` 用。 */
internal val markdownBulletList: MarkdownComponent = { model ->
    MarkdownListItems(model, ordered = false)
}

internal val markdownOrderedList: MarkdownComponent = { model ->
    MarkdownListItems(model, ordered = true)
}

/**
 * 符号样式：与正文**同字号、同行高**（基线才对得上），颜色取次级文字色。
 *
 * 按颜色记忆：`TextStyle` 是组合里每帧都会读的值，不 remember 的话每次重组都新建一份。
 */
@Composable
internal fun markdownMarkerStyle(): TextStyle {
    val color = LocalChatColors.current.processText
    return remember(color) {
        MarkdownMarkerStyle.copy(color = color, textAlign = TextAlign.End)
    }
}

/** 有序列表起始序号：CommonMark 允许 `0.` / `3.` 这类起始编号（`start-number`）。 */
private fun startNumberOf(node: ASTNode, content: String): Int =
    node.findChildOfType(MarkdownElementTypes.LIST_ITEM)
        ?.getUnescapedTextInNode(content)
        ?.takeWhile(Char::isDigit)
        ?.toIntOrNull()
        ?: 1

/** 用 [sample] 的实际排版宽度算出符号列宽（按当前字体与密度）。 */
@Composable
private fun rememberMarkerColumnWidth(sample: String, style: TextStyle, depth: Int): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, sample, style, depth, density) {
        val measuredPx = measurer.measure(
            text = AnnotatedString(sample),
            style = style,
            maxLines = 1,
            softWrap = false,
        ).size.width
        markerColumnWidthFor(
            measuredMarker = with(density) { measuredPx.toDp() },
            minIndent = listMinIndent(depth),
        )
    }
}

/**
 * 汉字段落与"数字 + 句点"符号之间的**首行基线差**。
 *
 * ### 为什么会有这个差
 *
 * 符号是独立的 `Text`，内容是另一个 `Text`，两者在 `Row` 里顶部对齐。
 * 但首行基线并不在行框顶部 —— 它由**这一行实际用到的字体**的 ascent 决定：
 *
 * - 符号 `1.` 全是拉丁字符，走 Roboto（ascent ≈ 0.927em）；
 * - 正文是汉字，回退到 CJK 字体（Noto Sans CJK，ascent ≈ 1.16em）。
 *
 * 于是正文的基线比符号低 ≈0.23em；16sp 下约 3.7dp。官方 WebView 里两者在
 * **同一个行内盒**里排，天然共基线，不会出现这个偏移 —— 真机反馈的
 * "序号没有视觉居中"就是它。
 *
 * ### 怎么量
 *
 * 不写死 3.7dp（换字体/字号就失效）：用 [TextMeasurer] 以**各自的真实样式**
 * 量出首行基线，差值就是需要下移的距离。纯拉丁内容的差值是 0，调用方跳过即可。
 */
@Composable
private fun rememberCjkBaselineShift(markerStyle: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val paragraphStyle = LocalMarkdownTypography.current.paragraph
    return remember(measurer, density, markerStyle, paragraphStyle) {
        val cjkBaseline = measurer.measure(
            text = AnnotatedString("国"),
            style = paragraphStyle,
            maxLines = 1,
            softWrap = false,
        ).firstBaseline
        val markerBaseline = measurer.measure(
            text = AnnotatedString("1."),
            style = markerStyle,
            maxLines = 1,
            softWrap = false,
        ).firstBaseline
        with(density) { (cjkBaseline - markerBaseline).toDp() }
    }
}

/** 列表项里第一个段落的原文（用于判断首行是汉字还是拉丁文）；没有段落则为 null。 */
internal fun firstParagraphText(content: String, item: ASTNode): String? {
    val paragraph = item.children.firstOrNull { it.type == MarkdownElementTypes.PARAGRAPH } ?: return null
    val start = paragraph.startOffset.coerceIn(0, content.length)
    val end = paragraph.endOffset.coerceIn(start, content.length)
    return content.substring(start, end)
}

/**
 * 这段文字里是否出现 CJK 字符（汉字/假名/谚文/CJK 标点/全角字符）。
 *
 * 只用来决定"首行基线是不是 CJK 字体量出来的"，不要求精确到逐字符：
 * 段落里一旦有汉字，浏览器/Compose 的首行行盒就会按 CJK 字体取 ascent。
 */
internal fun containsCjk(text: String): Boolean = text.any(::isCjk)

private fun isCjk(ch: Char): Boolean {
    val code = ch.code
    return when {
        code < 0x2E80 -> false
        code <= 0x9FFF -> true // CJK 部首/标点/假名/统一表意文字
        code in 0xAC00..0xD7AF -> true // 谚文音节
        code in 0xF900..0xFAFF -> true // 兼容表意文字
        code in 0xFE30..0xFE4F -> true // 兼容形式
        code in 0xFF00..0xFFEF -> true // 全角形式（，。！？：；等）
        else -> false
    }
}
