package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.chat.MessageList
import com.simplechat.app.ui.chat.MessageRole
import com.simplechat.app.ui.chat.MessageStatus
import com.simplechat.app.ui.chat.UiMessage
import com.simplechat.app.ui.chat.messageListEntries
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
            status(shortenPathDuringMeasure(instrumentation, activity, rows))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private suspend fun shortenPathDuringMeasure(
        instrumentation: Instrumentation,
        activity: MainActivity,
        rows: List<UiMessage>,
    ): String {
        val result = CompletableDeferred<String>()
        instrumentation.runOnMainSync {
            activity.setContent { SimpleChatTheme {
                val messages = remember { mutableStateListOf<UiMessage>().apply { addAll(rows.take(40)) } }
                val state = rememberLazyListState(initialFirstVisibleItemIndex = 30)
                MessageList(messages, Modifier.fillMaxSize(), rows = messageListEntries(messages, false), listState = state)
                LaunchedEffect(state) {
                    try {
                        withTimeout(8_000) {
                            while (state.layoutInfo.totalItemsCount != 40) withFrameNanos {}
                            check(state.firstVisibleItemIndex >= 20)
                            repeat(8) {
                                state.scrollToItem(30)
                                // A synchronous layout pass can run before the previous provider is recomposed.
                                Snapshot.withMutableSnapshot { messages.subList(2, messages.size).clear() }
                                state.dispatchRawDelta(-64f)
                                while (state.layoutInfo.totalItemsCount != 2) withFrameNanos {}
                                check(state.firstVisibleItemIndex in 0..1)
                                Snapshot.withMutableSnapshot { messages.addAll(rows.subList(2, 40)) }
                                while (state.layoutInfo.totalItemsCount != 40) withFrameNanos {}
                            }
                        }
                        result.complete("Eight switches from 40 messages to 2 during measurement completed without an out-of-bounds read")
                    } catch (error: Exception) {
                        result.completeExceptionally(error)
                    }
                }
            } }
        }
        return withTimeout(15_000) { result.await() }
    }

}
