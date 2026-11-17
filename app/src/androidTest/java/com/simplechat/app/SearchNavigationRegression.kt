package com.simplechat.app

import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.simplechat.app.data.*
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.RootScreen
import com.simplechat.app.ui.chat.*
import com.simplechat.app.ui.history.HistoryViewModel
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType

/** Full search -> drawer close -> first conversation load -> locate -> back navigation. */
internal object SearchNavigationRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit, theme: ThemeMode = ThemeMode.LIGHT) {
        val root = File(instrumentation.targetContext.cacheDir, "search-navigation-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = root
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(ctx, PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "navigation.preferences_pb") }))
        store.setProvider("OPENCODE_GO", "https://mock.invalid", "deepseek-flash")
        store.setApiStyle("OPENAI")
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val repo = ChatRepository(db, ctx)
        val apiClient = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"AFTER_SEARCH_REPLY\"}}]}\n\ndata: [DONE]\n\n"
                    .toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val json = ChatApi.defaultJson()
        val vm = withContext(Dispatchers.Main) { ChatViewModel(ChatApi(apiClient, SseClient(apiClient, json), AnthropicApi(apiClient, json), json), store, repo, "navigation") }
        val history = withContext(Dispatchers.Main) { HistoryViewModel(repo) }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val automation = instrumentation.uiAutomation
        suspend fun nodes(): List<AccessibilityNodeInfo> {
            automation.clearCache()
            val all = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) { all += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
            automation.rootInActiveWindow?.let(::visit)
            return all
        }
        suspend fun tap(test: (AccessibilityNodeInfo) -> Boolean) {
            val node = withTimeout(8000) {
                var found: AccessibilityNodeInfo? = null
                while (found == null) { found = nodes().firstOrNull { it.isVisibleToUser && test(it) }; if (found == null) delay(50) }
                found
            }
            var action: AccessibilityNodeInfo? = node
            while (action != null && !action.isClickable) action = action.parent
            check(action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            delay(350)
        }
        suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
            val now = android.os.SystemClock.uptimeMillis()
            for (i in 0..20) {
                val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(now, now + i * 20L, action, x1 + (x2 - x1) * i / 20, y1 + (y2 - y1) * i / 20, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                automation.injectInputEvent(event, true); event.recycle(); delay(20)
            }
            delay(250)
        }
        fun screenshot(name: String) {
            automation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.cacheDir, "navigation-$name.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        suspend fun selectedRowCount(): Int {
            val image = checkNotNull(automation.takeScreenshot())
            return try {
                nodes().filter { it.text?.toString() == "Navigation target" && it.isVisibleToUser }.count { title ->
                    var row: AccessibilityNodeInfo? = title
                    while (row != null && !row.isClickable) row = row.parent
                    val bounds = Rect().also { checkNotNull(row).getBoundsInScreen(it) }
                    bounds.right > 30 && bounds.centerY() in 0 until image.height &&
                        image.getPixel(2, bounds.centerY()) != image.getPixel(bounds.right - 24, bounds.centerY())
                }
            } finally { image.recycle() }
        }
        try {
            val id = repo.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "deepseek-flash", title = "Navigation target")
            var user = repo.appendUserMessage(id, "first question")
            val resultIds = mutableListOf<String>()
            repeat(24) { user = repo.appendUserMessage(id, "NAV_NEEDLE other result $it"); resultIds += user.id }
            val reply = repo.beginAssistantMessage(id, user.id)
            val paragraphs = (1..85).joinToString("\n\n") { "这是正文第 $it 段，保持正常排版，并让首次加载包含足够多的布局节点。" }
            repo.finishMessage(reply, "前言。\n\n**NAV_NEEDLE cold-hit**\n\n$paragraphs\n\nTAIL_MARKER", null, MessageStatus.DONE)
            // Match the reported case: a keyword near the top of the final very long reply.
            val entity = db.messageDao().findById(reply)!!
            db.messageDao().upsert(entity.copy(createdAt = System.currentTimeMillis() + 100))
            instrumentation.runOnMainSync { activity.setContent { SimpleChatTheme(themeMode = theme) { RootScreen(vm, history) } } }
            instrumentation.waitForIdleSync(); delay(500)
            tap { it.contentDescription?.toString() == Res.get(R.string.topbar_history) }
            tap { it.text?.toString() == Res.get(R.string.search_hint) }
            val input = withTimeout(5000) {
                var found: AccessibilityNodeInfo? = null
                while (found == null) { found = nodes().firstOrNull { it.isEditable && it.isVisibleToUser }; delay(50) }
                found
            }
            check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "NAV_NEEDLE")
            }))
            status("navigation fixture: entered search text")
            instrumentation.runOnMainSync { activity.window.insetsController?.show(android.view.WindowInsets.Type.ime()) }
            val readyResults = withTimeout(5000) { history.searchPage.first { it.hits.size == 25 } }
            check(readyResults.hits.first().messageId == reply)
            delay(300)
            withTimeout(10_000) {
                while (nodes().none { it.isVisibleToUser && it.text?.contains("cold-hit") == true }) {
                    nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    delay(200)
                }
            }
            status("navigation fixture: target result visible after list scroll")
            fun rowKey(nodes: List<AccessibilityNodeInfo>): String? = nodes.firstOrNull {
                it.isVisibleToUser && it.text?.contains("other result") == true
            }?.text?.toString()
            val priorRow = rowKey(nodes())
            screenshot("results-before-first-jump")
            tap { it.text?.contains("cold-hit") == true }
            status("navigation fixture: first result clicked")
            withTimeout(10_000) { while (withContext(Dispatchers.Main) { vm.switching || vm.searchTarget != null }) delay(50) }
            delay(400)
            screenshot("first-jump-finished")
            val hit = nodes().firstOrNull { it.text?.contains("NAV_NEEDLE cold-hit") == true && it.isVisibleToUser }
            checkNotNull(hit) { "First jump lost the keyword after drawer/conversation/keyboard transitions" }
            val bounds = Rect().also(hit::getBoundsInScreen)
            check(bounds.top > 100 && bounds.top < instrumentation.targetContext.resources.displayMetrics.heightPixels * .55f) { "First hit misplaced: $bounds" }
            status("actual RootScreen cold search: keyboard and drawer close, first conversation load, keyword remains in upper center after fade")
            val initialPosition = bounds.top
            delay(1200)
            val stable = nodes().firstOrNull { it.text?.contains("NAV_NEEDLE cold-hit") == true && it.isVisibleToUser }
            val stableBounds = Rect().also { checkNotNull(stable) { "Follow-bottom stole the search position after fade" }.getBoundsInScreen(it) }
            check(kotlin.math.abs(stableBounds.top - initialPosition) <= 3)

            tap { it.contentDescription?.toString() == Res.get(R.string.chat_scroll_bottom) }
            withTimeout(5000) { while (nodes().none { it.isVisibleToUser && it.text?.toString() == "TAIL_MARKER" }) delay(50) }
            delay(300)
            val tailBounds = Rect().also { nodes().first { it.isVisibleToUser && it.text?.toString() == "TAIL_MARKER" }.getBoundsInScreen(it) }
            val inputBounds = Rect().also { nodes().first { it.isVisibleToUser && it.text?.toString() == Res.get(R.string.input_hint) }.getBoundsInScreen(it) }
            val gapDp = (inputBounds.top - tailBounds.bottom) / instrumentation.targetContext.resources.displayMetrics.density
            screenshot("back-to-bottom")
            check(gapDp in 0f..160f) { "Search spacer remained after back-to-bottom: gap=$gapDp dp, tail=$tailBounds input=$inputBounds" }
            status("search -> back-to-bottom: latest text stays beside composer without search-only blank space")

            // System back is also the completion path for Android's side-swipe back gesture.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            withTimeout(5000) { while (nodes().none { it.isEditable && it.text?.toString() == "NAV_NEEDLE" && it.isVisibleToUser }) delay(50) }
            check(withContext(Dispatchers.Main) { history.query } == "NAV_NEEDLE")
            check(rowKey(nodes()) == priorRow) { "Search result scroll position was lost" }
            check(selectedRowCount() == 1) { "Returning to search must select only the clicked message" }
            screenshot("returned-to-results")
            status("side/back navigation restores the prior query and search result page")
            nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(300)
            val scrolledRow = rowKey(nodes())
            check(scrolledRow != null && scrolledRow != priorRow)
            tap { it.text?.toString() == scrolledRow }
            withTimeout(10_000) { while (withContext(Dispatchers.Main) { vm.switching || vm.searchTarget != null }) delay(50) }
            check(nodes().any { it.isVisibleToUser && it.text?.toString() == scrolledRow })
            val metrics = instrumentation.targetContext.resources.displayMetrics
            val now = android.os.SystemClock.uptimeMillis()
            val startX = 30f * metrics.density
            val endX = metrics.widthPixels * .8f
            val y = metrics.heightPixels * .48f
            for (i in 0..20) {
                val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                val event = MotionEvent.obtain(now, now + i * 20L, action, startX + (endX - startX) * i / 20, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                automation.injectInputEvent(event, true); event.recycle()
                delay(20)
            }
            withTimeout(5000) { while (nodes().none { it.isEditable && it.text?.toString() == "NAV_NEEDLE" && it.isVisibleToUser }) delay(50) }
            check(rowKey(nodes()) == scrolledRow)
            check(selectedRowCount() == 1) { "Selection must move to the second clicked message" }
            status("warm repeat jump and right-swipe return restore the same query/results/scroll position")
            val returnedInput = nodes().first { it.isEditable && it.isVisibleToUser }
            check(returnedInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "NAV_NEEDLE other")
            }))
            withTimeout(5000) { history.searchPage.first { it.hits.size == 24 } }
            delay(250)
            check(selectedRowCount() == 0) { "Changing query must clear the prior result selection" }
            status("message selection survives return, transfers on another hit, and clears when the query changes")
            tap { it.text?.contains("other result") == true }
            withTimeout(10_000) { while (withContext(Dispatchers.Main) { vm.switching || vm.searchTarget != null }) delay(50) }
            val composer = nodes().first { it.isEditable && it.isVisibleToUser }
            check(composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "AFTER_SEARCH_SEND")
            }))
            tap { it.contentDescription?.toString() == Res.get(R.string.input_send) }
            withTimeout(10_000) { while (withContext(Dispatchers.Main) { vm.isStreaming || vm.messages.lastOrNull()?.content != "AFTER_SEARCH_REPLY" }) delay(50) }
            delay(350)
            check(nodes().any { it.isVisibleToUser && it.text?.toString() == "AFTER_SEARCH_REPLY" })
            status("search -> continue sending: mock reply persists and remains visible beside composer")

            val tableId = repo.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "deepseek-flash", title = "Wide table regression")
            val tableUser = repo.appendUserMessage(tableId, "TABLE_INDEX_SENTINEL")
            val tableReply = repo.beginAssistantMessage(tableId, tableUser.id)
            val table = "| 时间 | 事件 | 相关势力 | 备注 |\n| --- | --- | --- | --- |\n" + (1..10).joinToString("\n") {
                "| 公元${1000 + it}年 | 这是第${it}行较长的事件描述，需要完整显示，不能压成一字一行。 | 第${it}个相关组织 | 备注命中词：这是第${it}行完整的说明文字 FULL_CONTENT_END |"
            }
            repo.finishMessage(tableReply, (1..24).joinToString("\n\n") { "表格之前的第${it}段正文。" } + "\n\n$table\n\nTABLE_TAIL", null, MessageStatus.DONE)
            withContext(Dispatchers.Main) { vm.openConversation(tableId, tableReply, "时间") }
            withTimeout(10_000) { while (withContext(Dispatchers.Main) { vm.switching || vm.searchTarget != null }) delay(50) }
            val firstHeader = nodes().first { it.isVisibleToUser && it.text?.toString()?.trim() == "时间" }
            val headerBounds = Rect().also(firstHeader::getBoundsInScreen)
            val screenWidth = metrics.widthPixels.toFloat()
            val tableY = headerBounds.centerY().toFloat()
            check(headerBounds.width() / metrics.density >= 24f && headerBounds.height() / metrics.density < 40f)
            suspend fun assertNoNavigation() {
                check(nodes().none { it.isVisibleToUser && it.isEditable && it.text?.toString() == "NAV_NEEDLE other" }) { "Table swipe opened search drawer" }
                check(nodes().none { it.isVisibleToUser && it.text?.toString() == "TABLE_INDEX_SENTINEL" }) { "Table swipe opened message index" }
            }
            repeat(5) { swipe(screenWidth * .82f, tableY, screenWidth * .18f, tableY); assertNoNavigation() }
            check(nodes().any { it.isVisibleToUser && it.text?.toString()?.trim() == "备注" })
            check(nodes().any { it.isVisibleToUser && it.text?.contains("FULL_CONTENT_END") == true })
            val notesHeader = Rect().also { nodes().first { it.isVisibleToUser && it.text?.toString()?.trim() == "备注" }.getBoundsInScreen(it) }
            val firstNote = Rect().also { nodes().first { it.isVisibleToUser && it.text?.contains("这是第1行完整") == true }.getBoundsInScreen(it) }
            check(kotlin.math.abs(notesHeader.left - firstNote.left) <= 3) { "Table header/body lost column alignment" }
            screenshot("wide-table-right-edge")
            repeat(5) { swipe(screenWidth * .18f, tableY, screenWidth * .82f, tableY); assertNoNavigation() }
            check(nodes().any { it.isVisibleToUser && it.text?.toString()?.trim() == "时间" })
            screenshot("wide-table-left-edge")
            val dateBefore = Rect().also { nodes().first { it.isVisibleToUser && it.text?.toString()?.trim() == "公元1001年" }.getBoundsInScreen(it) }
            swipe(screenWidth * .35f, tableY + 150 * metrics.density, screenWidth * .35f, tableY - 80 * metrics.density)
            val dateAfter = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString()?.trim() == "公元1001年" }
                ?.let { node -> Rect().also(node::getBoundsInScreen) }
            check(dateAfter == null || dateAfter.top < dateBefore.top - 40)
            assertNoNavigation()
            status("wide table: complete readable columns, both horizontal directions and boundary drags stay in table; vertical drag still scrolls messages")
            withContext(Dispatchers.Main) { vm.openConversation(tableId, tableReply, "备注命中词") }
            withTimeout(10_000) { while (withContext(Dispatchers.Main) { vm.switching || vm.searchTarget != null }) delay(50) }
            check(nodes().any { it.isVisibleToUser && it.text?.contains("备注命中词") == true })
            screenshot("wide-table-search-right-column")
            status("search in offscreen right table column reveals the matching cell automatically")
        } catch (error: Throwable) {
            screenshot("failure")
            status("failure nodes: " + nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString()?.take(100) }.joinToString(" | ").take(1600))
            throw error
        } finally {
            withContext(Dispatchers.Main) { vm.stop(); activity.finish() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close(); root.deleteRecursively()
        }
    }
}
