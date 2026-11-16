package com.simplechat.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.ui.markdown.LocalSearchHighlight
import com.simplechat.app.ui.markdown.MarkdownWithMath
import com.simplechat.app.ui.markdown.rememberSearchTextDecoration

/** Bounds both layout nodes and text layout memory, including a single 20-million-character message. */
@Composable
internal fun PagedMessageBody(text: String, markdown: Boolean = true) {
    var page by remember { mutableIntStateOf(0) }
    val search = LocalSearchHighlight.current
    LaunchedEffect(search?.request) {
        val hit = search?.let { text.indexOf(it.query, ignoreCase = true) } ?: -1
        if (hit >= 0) page = hit / MESSAGE_PAGE_CHARS
    }
    val window = messagePage(text, page)
    Column {
        Text(stringResource(R.string.large_message_paged), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { page = window.index - 1 }, enabled = window.index > 0) { Text(stringResource(R.string.page_previous)) }
            Text("${window.index + 1}/${window.count}", modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = { page = window.index + 1 }, enabled = window.index + 1 < window.count) { Text(stringResource(R.string.page_next)) }
        }
        val shown = text.substring(window.start, window.end)
        if (markdown) MarkdownWithMath(shown)
        else {
            val (searchModifier, onSearchLayout) = rememberSearchTextDecoration(shown)
            Text(shown, modifier = searchModifier, onTextLayout = onSearchLayout,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
