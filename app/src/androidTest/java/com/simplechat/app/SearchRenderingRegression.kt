package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.chat.*
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal object SearchRenderingRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val automation = instrumentation.uiAutomation
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        suspend fun nodes(): List<AccessibilityNodeInfo> {
            automation.clearCache()
            var root = automation.rootInActiveWindow
            repeat(20) { if (root == null) { delay(100); root = automation.rootInActiveWindow } }
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) { result += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
            visit(checkNotNull(root))
            return result
        }
        try {
            val paragraphs = (1..80).joinToString("\n\n") { "这是第 $it 段普通正文，含有 **正常加粗** 和可读文字。" }
            val cases = listOf(
                Triple("markdown", MessageRole.ASSISTANT, "### 保留正常标题\n\n$paragraphs\n\n- **章鱼**：这条命中必须保留加粗和列表排版。\n\n后文保持不动。"),
                Triple("code", MessageRole.ASSISTANT, "$paragraphs\n\n```kotlin\n" + (1..100).joinToString("\n") { "val line$it = $it" } + "\nval keyword = \"章鱼\"\n```"),
                Triple("table", MessageRole.ASSISTANT, "$paragraphs\n\n| 名称 | 描述 |\n| --- | --- |\n| 测试 | **章鱼** |"),
                Triple("user", MessageRole.USER, (1..80).joinToString("\n") { "用户输入第 $it 行" } + "\n章鱼：保留用户消息样式"),
                Triple("page", MessageRole.ASSISTANT, "正常段落。\n\n".repeat(6000) + "**章鱼**：搜索自动打开对应分页。"),
            )
            var request = 0L
            for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
              for ((name, role, body) in cases) {
                val query = "章鱼${name}${theme.name}"
                val message = UiMessage("search-$theme-$name", role, body.replace("章鱼", query), status = MessageStatus.DONE)
                val target = mutableStateOf<SearchTarget?>(SearchTarget(message.id, query, ++request, ready = true))
                var finished = false
                instrumentation.runOnMainSync {
                    activity.setContent {
                        SimpleChatTheme(themeMode = theme) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                MessageList(listOf(message), Modifier.fillMaxSize(), listState = rememberLazyListState(),
                                    contentPadding = PaddingValues(16.dp), searchTarget = target.value,
                                    onDismissSearch = { target.value = null; finished = true })
                            }
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                suspend fun hit(): AccessibilityNodeInfo? = nodes().firstOrNull { it.text?.contains(query) == true && it.isVisibleToUser }
                withTimeout(10_000) { while (hit() == null) delay(80) }
                delay(400)
                val before = Rect().also { checkNotNull(hit()).getBoundsInScreen(it) }
                check(before.top >= 0 && before.height() > 0)
                val text = nodes().mapNotNull { it.text?.toString() }
                check(text.none { it.contains("原文模式") || it.contains("Return to normal view") || (role == MessageRole.ASSISTANT && it.contains("**$query**")) })
                automation.takeScreenshot()?.let { bitmap ->
                    File(instrumentation.targetContext.cacheDir, "search-${theme.name.lowercase()}-$name-highlight.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                withTimeout(7_000) { while (!finished) delay(80) }
                instrumentation.waitForIdleSync()
                automation.takeScreenshot()?.let { bitmap ->
                    File(instrumentation.targetContext.cacheDir, "search-${theme.name.lowercase()}-$name-finished.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                val after = Rect().also { checkNotNull(hit()) { "Keyword disappeared after fade: $theme/$name" }.getBoundsInScreen(it) }
                check(kotlin.math.abs(before.top - after.top) <= 3 && kotlin.math.abs(before.bottom - after.bottom) <= 3) {
                    "Layout shifted after fade: $before -> $after"
                }
                status("${theme.name}/$name: normal search rendering, visible keyword, automatic fade and stable layout")
              }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
