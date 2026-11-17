package com.simplechat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 搜索结果面板（自绘底部面板）。
 *
 * 三个入口共用这一处：时间线里的「已阅读 N 个网页」、正文下方的来源胶囊（全部来源）、
 * 正文里的引用角标（只有被点的那一条）。只有一条时直接把摘要整段展开，
 * 让用户先看清"这个角标说的是哪个网页、讲了什么"，再决定要不要跳出去。
 *
 * 每行：站点图标 + 站点名 · 日期 + 标题 + 摘要，左上角的编号与正文角标一一对应。
 *
 * ⚠️ 与项目里其他底部面板一样**不用 `ModalBottomSheet`**（见 `Attachments.kt` 的说明），
 * 用 `Dialog` + 关掉平台默认宽度、让窗口铺到边到边，卡片贴底。
 *
 * 点一行交给系统浏览器（`LocalUriHandler`）。刻意**不做内置 WebView**：
 * 那等于把一个浏览器塞进 APK，与本项目「轻量」的第一要义冲突。
 */
@Composable
fun SourceSheet(
    sources: List<SourceRef>,
    onDismiss: () -> Unit,
) {
    val colors = LocalChatColors.current
    val uri = LocalUriHandler.current
    val single = sources.size == 1
    // 最多占屏高的七成：上方留出对话，用户知道自己还在原处
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(colors.card)
                    // 吞掉卡片内的点击，避免落到外面的关闭层上
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .navigationBarsPadding()
                    .padding(top = 14.dp, bottom = 8.dp),
            ) {
                // ── 标题行：搜索结果 · N 个来源 · ✕ ─────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.search_results),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = pluralStringResource(R.plurals.search_sources, sources.size, sources.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.placeholder,
                        modifier = Modifier.weight(1f),
                    )
                    IconCircleButton(
                        icon = AppIcons.Close,
                        contentDescription = stringResource(R.string.action_close),
                        onClick = onDismiss,
                        size = 30.dp,
                        iconSize = 16.dp,
                        background = colors.fieldBackground,
                        tint = colors.processText,
                    )
                }
                Spacer(Modifier.size(8.dp))

                if (sources.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_panel_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.placeholder,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        sources.forEach { source ->
                            SourceRow(source, expanded = single) {
                                if (source.url.isNotBlank()) runCatching { uri.openUri(source.url) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(source: SourceRef, expanded: Boolean, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
    ) {
        // ── 站点行：图标 · 站点名 · 日期 ·（右侧）编号 ──────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            SiteIcon(source = source, size = 16.dp)
            // Metadata keeps its natural spacing inside a full-width weighted slot;
            // the citation badge is anchored independently at the row's trailing edge.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Text(
                    text = source.siteName.ifBlank { hostOf(source.url) },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.processText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                source.pageAge?.takeIf { it.isNotBlank() }?.let { age ->
                    Text(
                        text = "· " + age.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                        maxLines = 1,
                    )
                }
            }
            // 编号：与正文角标一一对应
            Text(
                text = source.index.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.processText,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.markdownInlineCode)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.size(5.dp))
        Text(
            text = source.title.ifBlank { source.url },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        source.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
            Spacer(Modifier.size(3.dp))
            Text(
                text = snippet,
                style = MaterialTheme.typography.labelMedium,
                color = colors.placeholder,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 站点名缺省时退回域名（去掉 `www.`）。 */
private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore('?').removePrefix("www.")
