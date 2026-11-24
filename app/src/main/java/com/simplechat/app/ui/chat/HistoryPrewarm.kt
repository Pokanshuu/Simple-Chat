package com.simplechat.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.simplechat.app.ui.markdown.MarkdownPrewarmMaxChars
import com.simplechat.app.ui.markdown.warmMarkdownCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** Inspect at most two typical user/assistant exchanges immediately before the viewport. */
internal fun historyPrewarmBodies(messages: List<UiMessage>, before: Int): List<String> {
    if (before <= 0 || before > messages.size) return emptyList()
    return buildList {
        var remainingChars = MarkdownPrewarmMaxChars
        for (index in before - 1 downTo (before - 4).coerceAtLeast(0)) {
            val row = messages[index]
            // Timeline answers can contain rewritten citations, so their exact block keys
            // are only available when the message renders.
            if (row.role != MessageRole.ASSISTANT || row.streaming || row.timeline.isNotEmpty() ||
                row.content.length !in 1..remainingChars
            ) continue
            add(row.content)
            remainingChars -= row.content.length
            if (size == 2) break
        }
    }
}

@Composable
internal fun HistoryPrewarmEffect(listState: LazyListState, messages: List<UiMessage>, messageIndex: (Int) -> Int) {
    val currentIndex by rememberUpdatedState(messageIndex)
    val currentMessages by rememberUpdatedState(messages)
    LaunchedEffect(listState) {
        // Scroll direction stays latched after a drag/fling settles, allowing its window to finish.
        // Observe the index, never the per-pixel offset or the streaming tail content.
        snapshotFlow {
            if (listState.lastScrolledBackward) historyPrewarmBodies(currentMessages, currentIndex(listState.firstVisibleItemIndex))
            else emptyList()
        }.collectLatest { bodies ->
            if (bodies.isNotEmpty()) withContext(Dispatchers.Default) {
                for (body in bodies) warmMarkdownCache(body)
            }
        }
    }
}
