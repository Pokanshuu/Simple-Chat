package com.simplechat.app.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import com.simplechat.app.ui.markdown.LocalSearchHighlight
import com.simplechat.app.ui.markdown.LocalSearchViewport
import com.simplechat.app.ui.markdown.SearchViewport
import com.simplechat.app.ui.markdown.rememberSearchTextDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keep extremely large imports out of the conversation's eager per-message layout. */
@Composable
internal fun LargeMessageBody(text: String, fontFamily: FontFamily?) {
    val search = LocalSearchHighlight.current
    var showReader by remember { mutableStateOf(false) }
    var hit by remember(search?.request, text) { mutableIntStateOf(-1) }
    LaunchedEffect(search?.request, search?.ready, text) {
        if (search?.ready == true && !search.located && search.query.isNotEmpty()) {
            val index = withContext(Dispatchers.Default) { text.indexOf(search.query, ignoreCase = true) }
            if (index >= 0) {
                hit = index
                showReader = true
            }
        }
    }
    val style = MaterialTheme.typography.bodyLarge.let { if (fontFamily == null) it else it.copy(fontFamily = fontFamily) }
    val preview = remember(text) {
        val end = minOf(text.length, 320)
        val safeEnd = if (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end - 1 else end
        text.substring(0, safeEnd)
    }
    Column {
        Text(preview, style = style, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        TextButton(onClick = { showReader = true }) { Text(stringResource(R.string.large_message_open)) }
    }
    if (showReader) {
        Dialog(onDismissRequest = { showReader = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    TextButton(onClick = { showReader = false }) { Text(stringResource(R.string.action_close)) }
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = hit.coerceAtLeast(0) / MESSAGE_PAGE_CHARS)
                    LaunchedEffect(hit) {
                        if (hit >= 0) listState.scrollToItem(hit / MESSAGE_PAGE_CHARS)
                    }
                    val matchEnd = if (hit >= 0) hit + (search?.query?.length ?: 0) else -1
                    val viewportBounds = remember { mutableStateOf(Rect.Zero) }
                    val viewport = remember(listState, viewportBounds.value) {
                        SearchViewport(viewportBounds.value) { listState.scrollBy(it) }
                    }
                    val verticalSpace = if (hit >= 0) with(LocalDensity.current) { viewportBounds.value.height.toDp() } else 0.dp
                    CompositionLocalProvider(LocalSearchViewport provides viewport) {
                        SelectionContainer(Modifier.weight(1f)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().onGloballyPositioned { viewportBounds.value = it.boundsInWindow() },
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp + verticalSpace * 0.38f,
                                    bottom = 12.dp + verticalSpace * 0.62f),
                            ) {
                                items(messagePage(text, 0).count) { index ->
                                    val window = messagePage(text, index, hit, matchEnd)
                                    val chunk = remember(text, window) { text.substring(window.start, window.end) }
                                    val (modifier, onTextLayout) = rememberSearchTextDecoration(chunk)
                                    Text(chunk, modifier = modifier, style = style, onTextLayout = onTextLayout)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
