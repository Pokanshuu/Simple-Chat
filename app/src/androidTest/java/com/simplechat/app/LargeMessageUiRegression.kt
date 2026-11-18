package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.simplechat.app.ui.chat.*
import com.simplechat.app.ui.theme.SimpleChatTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.room.Room
import com.simplechat.app.data.*
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.*
import com.simplechat.app.net.anthropic.AnthropicApi
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.buffer
import okio.blackholeSink

/** Exercise real Compose measurement on a 192 MB heap, using synthetic text only. */
internal object LargeMessageUiRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val automation = instrumentation.uiAutomation
        val content = "large text line\n".repeat(1_400_000) + "UI_SEARCH_NEAR_END"
        val target = mutableStateOf<SearchTarget?>(null)
        val message = UiMessage("large-ui", MessageRole.USER, content, status = MessageStatus.DONE)
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AppDatabase::class.java).build()
        val root = java.io.File(instrumentation.targetContext.cacheDir, "large-ui-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : android.content.ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = root
            override fun getApplicationContext() = this
        }
        val store = SettingsStore(ctx)
        store.setProvider("OPENCODE_GO", "https://device-test.invalid", "deepseek-flash")
        store.setApiStyle("OPENAI")
        val serialized = java.util.concurrent.atomic.AtomicBoolean(false)
        val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
            blackholeSink().buffer().use { chain.request().body!!.writeTo(it) }
            serialized.set(true)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"stress reply\"}}]}\n\ndata: [DONE]\n\n"
                    .toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val json = ChatApi.defaultJson()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        val repo = ChatRepository(db, ctx)
        val vm = withContext(Dispatchers.Main) { ChatViewModel(api, store, repo, "large-send") }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    SimpleChatTheme {
                        MessageList(listOf(message), Modifier.fillMaxSize(), searchTarget = target.value)
                    }
                }
            }
            instrumentation.waitForIdleSync()
            delay(1200)
            suspend fun nodes(): List<AccessibilityNodeInfo> {
                runCatching { automation.clearCache() }
                var root = automation.rootInActiveWindow
                repeat(20) {
                    if (root == null) { delay(250); root = automation.rootInActiveWindow }
                }
                val result = mutableListOf<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo) {
                    result += node
                    for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
                }
                visit(checkNotNull(root) { "No accessibility root" })
                return result
            }
            val pageCount = (content.length + 7999) / 8000
            check(nodes().any { it.text?.contains("/$pageCount") == true }) { "Large-message pager was not rendered" }
            status("21-million-character message measured with bounded Compose nodes")
            instrumentation.runOnMainSync {
                target.value = SearchTarget(message.id, "UI_SEARCH_NEAR_END", 1, ready = true)
            }
            instrumentation.waitForIdleSync()
            delay(1200)
            val hit = nodes().firstOrNull { it.text?.contains("UI_SEARCH_NEAR_END") == true }
                ?: error("Search target was not composed")
            val bounds = Rect()
            hit.getBoundsInScreen(bounds)
            check(hit.isVisibleToUser && bounds.height() > 0)
            status("search near the end opens the matching page and exposes the hit")
            instrumentation.runOnMainSync {
                activity.setContent { SimpleChatTheme { MessageList(vm.messages, Modifier.fillMaxSize()) } }
            }
            var persisted = false
            withContext(Dispatchers.Main) { vm.send(content, onPersisted = { persisted = true }) }
            withTimeout(30_000) {
                while (!serialized.get() || withContext(Dispatchers.Main) { vm.isStreaming || !persisted }) delay(50)
            }
            val id = withContext(Dispatchers.Main) { checkNotNull(vm.currentConversationId) }
            check(db.messageDao().countFor(id) == 2)
            check(withContext(Dispatchers.Main) { vm.messages.last().content == "stress reply" })
            instrumentation.waitForIdleSync()
            status("21-million-character send serialized, reply persisted, and real message list rendered without crashing")
            withContext(Dispatchers.Main) { vm.newConversation() }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            db.close()
            root.deleteRecursively()
        }
    }
}
