package com.simplechat.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.ui.markdown.LocalSearchHighlight
import com.simplechat.app.ui.markdown.MarkdownWithMath
import com.simplechat.app.ui.markdown.rememberSearchTextDecoration

/** Bounds both layout nodes and text layout memory, including a single 20-million-character message. */
@Composable
internal fun PagedMessageBody(
    text: String,
    markdown: Boolean = true,
    /**
     * 正文用的字体族（用户发言层 / 模型回复层的自定义字体）。
     *
     * 这个组件同时服务三条路径：超长消息分页、搜索态、文本选择态。
     * 后两条不走 `UserBubble` / `MarkdownWithMath`，所以字体必须在这里也接一次 ——
     * 否则"给用户发言换了字体，一进搜索态就变回系统字体"。
     */
    fontFamily: FontFamily? = null,
) {
    var page by rememberSaveable(text) { mutableIntStateOf(0) }
    val search = LocalSearchHighlight.current
    LaunchedEffect(search?.request) {
        val hit = search?.let { text.indexOf(it.query, ignoreCase = true) } ?: -1
        if (hit >= 0) page = hit / MESSAGE_PAGE_CHARS
    }
    val window = messagePage(text, page)
    Column {
        DisableSelection {
            Text(stringResource(R.string.large_message_paged), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { page = window.index - 1 }, enabled = window.index > 0) { Text(stringResource(R.string.page_previous)) }
                Text("${window.index + 1}/${window.count}", modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = { page = window.index + 1 }, enabled = window.index + 1 < window.count) { Text(stringResource(R.string.page_next)) }
            }
        }
        val shown = text.substring(window.start, window.end)
        if (markdown) MarkdownWithMath(shown)
        else {
            val (searchModifier, onSearchLayout) = rememberSearchTextDecoration(shown)
            val base = MaterialTheme.typography.bodyLarge
            Text(
                shown,
                modifier = searchModifier,
                onTextLayout = onSearchLayout,
                style = if (fontFamily == null) base else base.copy(fontFamily = fontFamily),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
