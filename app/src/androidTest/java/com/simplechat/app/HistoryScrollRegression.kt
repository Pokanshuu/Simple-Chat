package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.chat.MessageList
import com.simplechat.app.ui.chat.MessageRole
import com.simplechat.app.ui.chat.MessageStatus
import com.simplechat.app.ui.chat.UiMessage
import com.simplechat.app.ui.theme.SimpleChatTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** adb shell am instrument -w -e historyScroll true <test-package>/<runner> */
internal object HistoryScrollRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val rows = List(400) { i ->
            val role = if (i % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT
            val body = if (role == MessageRole.USER) "History question $i" else buildString {
                appendLine("## History answer $i")
                repeat(6) { p -> append("\nParagraph $p in answer $i. ").appendLine("Text for a completed reply. ".repeat(6)) }
                append("\nA **formatted** note with `code` at the end.")
            }
            UiMessage("history-$i", role, body, status = MessageStatus.DONE)
        }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val result = CompletableDeferred<String>()
        try {
            instrumentation.runOnMainSync { activity.setContent { SimpleChatTheme {
                val state = rememberLazyListState(initialFirstVisibleItemIndex = rows.lastIndex)
                val stepPx = with(LocalDensity.current) { 96.dp.toPx() }
                MessageList(rows, Modifier.fillMaxSize(), listState = state)
                LaunchedEffect(state) {
                    delay(1000)
                    val start = state.firstVisibleItemIndex
                    val gaps = LongArray(180)
                    state.scroll {
                        var previous = withFrameNanos { it }
                        for (i in gaps.indices) {
                            scrollBy(-stepPx)
                            val now = withFrameNanos { it }
                            gaps[i] = now - previous
                            previous = now
                        }
                    }
                    val end = state.firstVisibleItemIndex
                    gaps.sort()
                    if (end >= start - 12) result.completeExceptionally(AssertionError("No progress: $start to $end"))
                    else result.complete("400-row history: index $start to $end; 180 frame intervals, " +
                        "p95=${gaps[170] / 1_000_000f} ms, >32ms=${gaps.count { it > 32_000_000L }}")
                }
            } } }
            status(withTimeout(60_000) { result.await() })
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
