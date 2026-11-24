package com.simplechat.app.ui.chat

import androidx.compose.animation.AnimatedContent
import com.simplechat.app.ui.markdown.rememberSearchTextDecoration
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.FoldAnimMs
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.LocalFloatingBaseOpacity
import com.simplechat.app.ui.theme.ProcessTextStyle

/**
 * 扩展通道的过程面板：**思考 + 工具调用**混在一条时间线上。
 *
 * 与 [ProcessPanel] 的分工很清楚：
 *
 * | | OpenAI 通道 | 扩展通道 |
 * |---|---|---|
 * | 回复形态 | 思考一段 + 正文一段 | 思考 / 搜索 / 浏览 / 正文**交错** |
 * | 面板 | [ProcessPanel]（思考栏） | 这里（时间线） |
 *
 * 折叠行为与官方 Chat Demo 一致：正文一出现就收起成「已思考（用时 N 秒）」，
 * 想看过程点标题展开。**流式期间不折叠** —— 那时过程本身就是用户最想看的进度。
 *
 * [onOpenSources] 在用户点搜索行时调用：打开来源列表（`SourceSheet`）。
 */
@Composable
fun TimelinePanel(
    view: TimelineView,
    streaming: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenSources: (List<SourceRef>) -> Unit,
    modifier: Modifier = Modifier,
    hasGeneratedImage: Boolean = false,
) {
    val colors = LocalChatColors.current
    val hasAnswer = view.hasAnswer || hasGeneratedImage
    val running = streaming && !hasAnswer
    // 没有标题（这一轮没思考过）时不折叠，直接展示步骤
    val header = timelineHeader(view, streaming, hasAnswer)
    val showHeader = header != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 流式期间禁用尺寸动画：动画追不上每几十毫秒一次的增量，会把最新内容裁掉
            .then(
                if (running) Modifier
                else Modifier.animateContentSize(tween(FoldAnimMs, easing = MotionEasing)),
            ),
    ) {
        if (view.hasSteps && showHeader) {
            // 与 OpenAI 通道**同一个**组件、同一套措辞（见 ThinkingHeader）
            ThinkingHeader(
                label = header.orEmpty(),
                expanded = expanded,
                enabled = !running,
                onToggle = onToggleExpand,
            )
            Spacer(Modifier.size(8.dp))
        }

        // 流式期间始终展示（过程就是进度）；定稿后只在展开时展示
        if (running || expanded || !showHeader) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                view.steps.forEach { step ->
                    when (step) {
                        is TimelineStep.Thinking -> ThinkingStep(step)
                        is TimelineStep.Preamble -> AnimatedContent(
                            targetState = step.markdown,
                            transitionSpec = {
                                fadeIn(tween(180, easing = MotionEasing)) togetherWith
                                    fadeOut(tween(120, easing = MotionEasing))
                            },
                            label = "toolPreambleFade",
                        ) { markdown ->
                            Text(
                                text = markdown,
                                style = ProcessTextStyle,
                                color = colors.processText,
                            )
                        }
                        is TimelineStep.Search -> SearchStep(step, onOpenSources)
                        is TimelineStep.Open -> OpenStepRow(step)
                        is TimelineStep.Find -> FindStepRow(step)
                        is TimelineStep.ReadLink -> ReadLinkStepRow(step)
                    }
                }
            }
        }
    }
}

/**
 * 折叠区标题。
 *
 * 三种状态各有自己的话：进行中要说"正在做什么"，被中断要说"停了"，
 * 有正文就说思考用时 —— 后者才是用户折叠过程之后真正想知道的量。
 */
@Composable
private fun timelineHeader(view: TimelineView, streaming: Boolean, hasAnswer: Boolean): String? {
    if (hasAnswer) {
        // 只有真实思考文本才显示标题。网关可在正文之后才送来摘要，此时没有思考时长。
        if (view.steps.none { it is TimelineStep.Thinking }) return null
        val seconds = view.thinkingSeconds()
        return if (seconds != null) {
            stringResource(R.string.thinking_done_seconds, seconds)
        } else {
            stringResource(R.string.thinking_done)
        }
    }
    if (!streaming) return stringResource(R.string.thinking_stopped)
    val last = view.steps.lastOrNull()
    /*
     * 思考块已经收口（服务端发过 content_block_stop）但正文还没开始 ——
     * 这时候不该再写"正在思考"，那是谎报。改成"已思考（用时 N 秒）"，
     * 与定稿后的文案一致，只是后面还会继续出现正文。
     */
    if (last is TimelineStep.Thinking && !last.active) {
        val seconds = view.thinkingSeconds()
        return if (seconds != null) {
            stringResource(R.string.thinking_done_seconds, seconds)
        } else {
            stringResource(R.string.thinking_done)
        }
    }
    return when (last) {
        is TimelineStep.Search ->
            if (last.status == ToolStatus.WIP) stringResource(R.string.search_deep_searching)
            else stringResource(R.string.thinking_running)

        is TimelineStep.Open -> stringResource(R.string.search_browsing)
        is TimelineStep.Find -> stringResource(R.string.search_browsing)
        is TimelineStep.ReadLink -> stringResource(R.string.search_reading)
        else -> stringResource(R.string.thinking_running)
    }
}

/**
 * 思考片段：左侧圆点 + 竖线，小号正文。
 *
 * **进行中与已思考共用同一个引导列**（[ThinkingGutter]）：流式期间走尾巴模式
 * （只排版最后几行、底部对齐、不跳动），写完就是完整正文 —— 但两种状态的
 * 左缘、线宽、线色、圆点位置完全一致，切换时不会跳版。
 */
@Composable
private fun ThinkingStep(step: TimelineStep.Thinking) {
    if (step.active) {
        TailContent(step.text, gutter = true)
        return
    }
    val colors = LocalChatColors.current
    if (step.text.length > LARGE_MESSAGE_CHARS) {
        LargeMessageBody(step.text, null)
        return
    }
    val (searchDecoration, onTextLayout) = rememberSearchTextDecoration(step.text)
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        ThinkingGutter(
            modifier = Modifier.width(ThinkingGutterWidth).fillMaxHeight(),
        )
        Spacer(Modifier.width(ThinkingGutterGap))
        Text(
            text = step.text,
            style = ProcessTextStyle,
            color = colors.processText,
            modifier = Modifier.weight(1f).then(searchDecoration),
            onTextLayout = onTextLayout,
        )
    }
}

/** 搜索行：「已阅读 N 个网页」/「搜索到 N 个网页」+ 站点缩略。 */
@Composable
private fun SearchStep(step: TimelineStep.Search, onOpenSources: (List<SourceRef>) -> Unit) {
    val colors = LocalChatColors.current
    val clickable = step.status != ToolStatus.WIP && step.sources.isNotEmpty()
    ToolRow(
        icon = AppIcons.Search,
        failed = step.status == ToolStatus.FAILED,
        onClick = if (clickable) ({ onOpenSources(step.sources) }) else null,
    ) {
        when {
            step.status == ToolStatus.WIP -> {
                val prefix = stringResource(R.string.search_intro)
                val query = step.queries.firstOrNull()
                Text(
                    text = if (query != null) "$prefix $query" else stringResource(R.string.search_searching),
                    style = ProcessTextStyle,
                    color = colors.processText,
                )
            }
            step.status == ToolStatus.FAILED -> Text(
                text = stringResource(R.string.search_failed),
                style = ProcessTextStyle,
                color = colors.processText,
            )
            step.sources.isEmpty() -> Text(
                text = stringResource(
                    if (isDeepSearchKind(step.kind)) R.string.search_no_content
                    else R.string.search_message_no_content,
                ),
                style = ProcessTextStyle,
                color = colors.processText,
            )
            else -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = searchSummary(step),
                    style = ProcessTextStyle,
                    color = colors.processText,
                )
                SiteDots(step.sources)
            }
        }
    }
}

@Composable
private fun searchSummary(step: TimelineStep.Search): String {
    val count = step.sources.size
    return if (isDeepSearchKind(step.kind) || step.kind == com.simplechat.app.net.IndependentSearchKind) {
        stringResource(if (count == 1) R.string.search_found_one else R.string.search_found_many, count)
    } else {
        stringResource(if (count == 1) R.string.search_read_one else R.string.search_read_many, count)
    }
}

/** 站点缩略：最多 5 个圆点，用域名首字母代替图标（拉图标要网络，不值当）。 */
@Composable
private fun SiteDots(sources: List<SourceRef>) {
    val colors = LocalChatColors.current
    Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
        sources.take(5).forEach { source ->
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(colors.card),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = source.siteName.firstOrNull()?.uppercase() ?: "·",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.processText,
                )
            }
        }
    }
}

@Composable
private fun OpenStepRow(step: TimelineStep.Open) {
    val uri = LocalUriHandler.current
    val colors = LocalChatColors.current
    val allFailed = step.pages.all { it.failed }
    ToolRow(icon = AppIcons.Globe, failed = allFailed) {
        if (allFailed) {
            Text(
                text = stringResource(R.string.search_open_failed),
                style = ProcessTextStyle,
                color = colors.processText,
            )
            return@ToolRow
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(
                    if (step.pages.size == 1) R.string.search_open_one else R.string.search_open_many,
                    step.pages.size,
                ),
                style = ProcessTextStyle,
                color = colors.processText,
            )
            step.pages.take(5).forEach { page ->
                PageChip(page) {
                    if (page.url.isNotBlank()) runCatching { uri.openUri(page.url) }
                }
            }
        }
    }
}

@Composable
private fun FindStepRow(step: TimelineStep.Find) {
    val colors = LocalChatColors.current
    val allFailed = step.patterns.all { it.failed }
    ToolRow(icon = AppIcons.Globe, failed = allFailed) {
        if (allFailed) {
            Text(
                text = stringResource(R.string.search_find_failed),
                style = ProcessTextStyle,
                color = colors.processText,
            )
            return@ToolRow
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.search_find_keyword),
                style = ProcessTextStyle,
                color = colors.processText,
            )
            step.patterns.forEach { pattern ->
                Chip(
                    text = pattern.pattern,
                    failed = pattern.failed,
                    leadingCheck = !pattern.failed,
                )
            }
        }
    }
}

@Composable
private fun ReadLinkStepRow(step: TimelineStep.ReadLink) {
    val colors = LocalChatColors.current
    val uri = LocalUriHandler.current
    ToolRow(icon = AppIcons.MessageSquare) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.search_reading),
                style = ProcessTextStyle,
                color = colors.processText,
            )
            step.links.forEach { page ->
                PageChip(page) {
                    if (page.url.isNotBlank()) runCatching { uri.openUri(page.url) }
                }
            }
        }
    }
}

/** 工具行外壳：左侧图标 + 可换行内容；[onClick] 非空时整行可点。 */
@Composable
private fun ToolRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    failed: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (failed) colors.danger else colors.processText,
            modifier = Modifier.size(15.dp),
        )
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun PageChip(page: PageRef, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Chip(
        text = page.title,
        failed = page.failed,
        onClick = if (page.failed) null else onClick,
    )
}

@Composable
private fun Chip(
    text: String,
    failed: Boolean = false,
    leadingCheck: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (leadingCheck) {
            Icon(
                imageVector = if (failed) AppIcons.Close else AppIcons.Check,
                contentDescription = null,
                tint = if (failed) colors.danger else colors.processText,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) colors.danger else colors.processText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 正文下方的来源胶囊：叠放的站点图标 + 「N 个来源」，点开是完整的搜索结果面板。
 *
 * 原先是一排横向滚动的「编号 + 站点名」小块，来源一多就挤出屏幕，
 * 而且每块只能打开一条。现在收成一枚胶囊：前几个站点的图标叠在一起，
 * 告诉用户"参考了哪些网站"，要看细节就点开面板。
 * 具体某一条仍可以从正文角标直接点 —— 两条路用的是同一套编号。
 */
@Composable
fun SourcesStrip(
    sources: List<SourceRef>,
    onOpenSources: (List<SourceRef>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty()) return
    val colors = LocalChatColors.current
    val fill = colors.card.copy(alpha = LocalFloatingBaseOpacity.current)
    // 同一站点只露一次图标：搜索结果常有同站多页
    val faces = remember(sources) { sources.distinctBy { it.siteName.ifBlank { it.url } }.take(MaxStackedIcons) }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(fill)
            .clickable { onOpenSources(sources) }
            .padding(start = 5.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            faces.forEachIndexed { i, source ->
                SiteIcon(
                    source = source,
                    size = StackedIconSize,
                    ring = fill,
                    modifier = Modifier
                        .padding(start = StackedIconStep * i)
                        // 先出现的压在上面：从左往右读，第一个最完整
                        .zIndex((faces.size - i).toFloat()),
                )
            }
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = pluralStringResource(R.plurals.search_sources, sources.size, sources.size),
            style = MaterialTheme.typography.labelMedium,
            color = colors.processText,
            maxLines = 1,
        )
    }
}

private const val MaxStackedIcons = 4
private val StackedIconSize = 18.dp
private val StackedIconStep = 12.dp
