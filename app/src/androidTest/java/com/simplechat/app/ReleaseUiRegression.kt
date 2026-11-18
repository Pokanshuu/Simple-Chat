package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.simplechat.app.net.Citation
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.WebSource
import com.simplechat.app.ui.chat.*
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.*

/** Real pointer release and production citation/source UI on the installed Android app. */
internal object ReleaseUiRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val automation = instrumentation.uiAutomation
        fun nodes(): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { automation.clearCache() }
            val out = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                if (android.os.Build.VERSION.SDK_INT < 33) node.refresh()
                out += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            automation.rootInActiveWindow?.let(::visit)
            return out
        }
        suspend fun find(label: String): AccessibilityNodeInfo = withTimeout(8000) {
            while (true) {
                nodes().firstOrNull { it.isVisibleToUser &&
                    (it.text?.toString() == label || it.contentDescription?.toString() == label) }?.let { return@withTimeout it }
                delay(50)
            }
            error("unreachable")
        }
        fun event(action: Int, x: Float, y: Float, start: Long) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true))
            event.recycle()
        }
        suspend fun tap(node: AccessibilityNodeInfo) {
            val rect = Rect().also(node::getBoundsInScreen)
            val start = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, rect.exactCenterX(), rect.exactCenterY(), start)
            delay(40)
            event(MotionEvent.ACTION_UP, rect.exactCenterX(), rect.exactCenterY(), start)
            delay(400)
        }
        fun screenshot(name: String) {
            val bitmap = checkNotNull(automation.takeScreenshot())
            File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            val path = (0 until 100).map { index -> UiMessage("fling-$index",
                if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                "消息 $index\n\n" + "用于检查分享选择模式下的惯性滚动。".repeat(8)) }
            val selection = MessageShareSelection().apply { begin(path.first().id, messageShareGroups(path)) }
            val listState = LazyListState()
            instrumentation.runOnMainSync {
                activity.setContent { SimpleChatTheme {
                    MessageList(path, Modifier.fillMaxSize().systemBarsPadding(), listState = listState, shareSelection = selection)
                } }
            }
            delay(800)
            val metrics = context.resources.displayMetrics
            val x = metrics.widthPixels * .7f
            val y = metrics.heightPixels * .78f
            val travel = metrics.heightPixels * .35f
            val start = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, x, y, start)
            repeat(9) { step ->
                delay(10)
                event(MotionEvent.ACTION_MOVE, x, y - travel * (step + 1) / 9, start)
            }
            event(MotionEvent.ACTION_UP, x, y - travel, start)
            delay(50)
            val before = withContext(Dispatchers.Main) { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            delay(180)
            val after = withContext(Dispatchers.Main) { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            check(after.first > before.first || after.first == before.first && after.second > before.second + 10) {
                "Share fling stopped at pointer release: $before -> $after"
            }
            check(selection.selectedIds == setOf(path.first().id)) { "Dragging changed selection" }
            status("share selection continues moving after UP without changing selected groups: $before -> $after")

            val sources = listOf(
                WebSource("https://example.invalid/one", "第一条来源标题", "示例站点", snippet = "详情段落\n".repeat(30) + "摘要末尾完整显示", pageAge = "2026-11-18"),
                WebSource("https://second.invalid/two", "第二条来源标题", "另一站点", snippet = "第二条摘要"),
            )
            val blocks = listOf(
                ReplyBlock.ToolUse("search", "deepseek_web_search", "search", "search", listOf("来源显示验收")),
                ReplyBlock.SearchResult("search", "search", sources),
                ReplyBlock.Text("中文正文引用", listOf(Citation(sources[0].url, sources[0].title))))
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                instrumentation.runOnMainSync {
                    activity.setContent { SimpleChatTheme(themeMode = theme) {
                        Box(Modifier.fillMaxSize().systemBarsPadding().background(MaterialTheme.colorScheme.background)) {
                            MessageList(listOf(UiMessage("citation", MessageRole.ASSISTANT, "中文正文引用", timeline = blocks)),
                                Modifier.fillMaxSize(), listState = LazyListState())
                        }
                    } }
                }
                delay(700)
                screenshot("citations-${theme.name.lowercase()}.png")
                val pill = nodes().firstOrNull { it.text?.toString()?.contains("2") == true &&
                    (it.text?.toString()?.contains("来源") == true || it.text?.toString()?.contains("sources", true) == true) }
                    ?: error("Missing sources pill: ${nodes().map { it.text }}")
                tap(pill)
                find("第一条来源标题")
                find("第二条来源标题")
                val numberBounds = listOf("1", "2").map { number ->
                    Rect().also { find(number).getBoundsInScreen(it) }
                }
                check(kotlin.math.abs(numberBounds[0].right - numberBounds[1].right) <= 2 &&
                    numberBounds.all { it.right > metrics.widthPixels * .9f }) {
                    "Source numbers must align at the right edge: $numberBounds"
                }
                screenshot("sources-${theme.name.lowercase()}.png")
                tap(find(context.getString(R.string.action_close)))
                val citation = nodes().firstOrNull { it.text?.toString() == "1" && it.isVisibleToUser }
                if (citation != null) tap(citation) else {
                    val body = nodes().first { it.text?.toString()?.startsWith("中文正文引用") == true }
                    val rect = Rect().also(body::getBoundsInScreen)
                    val time = SystemClock.uptimeMillis()
                    event(MotionEvent.ACTION_DOWN, rect.right - 8f, rect.exactCenterY(), time)
                    delay(40)
                    event(MotionEvent.ACTION_UP, rect.right - 8f, rect.exactCenterY(), time)
                    delay(400)
                }
                find("第一条来源标题")
                check(nodes().none { it.text?.toString() == "第二条来源标题" })
                val expanded = nodes().firstOrNull { it.text?.toString()?.endsWith("摘要末尾完整显示") == true }
                check(expanded != null) { "Single-source snippet was truncated" }
                screenshot("source-single-${theme.name.lowercase()}.png")
                tap(find(context.getString(R.string.action_close)))
                status("$theme: sources pill opens all results; inline citation opens matching source with complete snippet; close works")
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
