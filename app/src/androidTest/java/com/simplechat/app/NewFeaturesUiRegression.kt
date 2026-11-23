package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.simplechat.app.data.*
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.*
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.*
import com.simplechat.app.ui.settings.ApiProfilesPage
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.markdown.MarkdownWithMath
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Real Compose/event wiring with a fake audio sink; never sends text or keys off the device. */
internal object NewFeaturesUiRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val automation = instrumentation.uiAutomation
        val root = File(instrumentation.targetContext.cacheDir, "features-ui-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : android.content.ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = root
        }
        val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(ctx, PreferenceDataStoreFactory.create(scope = settingsScope,
            produceFile = { File(root, "test.preferences_pb") }))
        store.setProvider("OPENCODE_GO", "https://test.invalid", "deepseek-flash")
        store.setApiStyle("OPENAI")
        val blocked = AtomicBoolean(false)
        val reached = AtomicBoolean(false)
        val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
            reached.set(true)
            while (blocked.get() && !chain.call().isCanceled()) Thread.sleep(10)
            if (chain.call().isCanceled()) throw java.io.IOException("test stopped")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"New spoken answer\"}}]}\n\ndata: [DONE]\n\n"
                    .toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val json = ChatApi.defaultJson()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val vm = withContext(Dispatchers.Main) { ChatViewModel(ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json), store,
            ChatRepository(db, ctx), "features") }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val reads = mutableListOf<String>()
        var stopCount = 0
        var playing = false
        try {
            delay(300)
            instrumentation.runOnMainSync {
                activity.setContent {
                    SimpleChatTheme {
                        AutoReadAloudEffect(vm, true, { id, _ -> reads += id; playing = true }, { stopCount++; playing = false })
                        Column {
                            ChatTopBar("Feature test", {}, { vm.newConversation() }, autoReadAloud = vm.autoReadAloud,
                                onToggleAutoReadAloud = { if (vm.autoReadAloud) { stopCount++; playing = false }; vm.toggleAutoReadAloud() })
                            MessageList(vm.messages)
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            suspend fun nodes(): List<AccessibilityNodeInfo> {
                if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { automation.clearCache() }
                var node = automation.rootInActiveWindow
                repeat(20) { if (node == null) { delay(100); node = automation.rootInActiveWindow } }
                val out = mutableListOf<AccessibilityNodeInfo>()
                fun visit(n: AccessibilityNodeInfo) { out += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::visit) }
                visit(checkNotNull(node))
                return out
            }
            suspend fun tapDescription(label: String) {
                val node = nodes().firstOrNull { it.contentDescription?.toString() == label } ?: error("Missing action: $label")
                var clickable: AccessibilityNodeInfo? = node
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                check(clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                instrumentation.waitForIdleSync()
                delay(150)
            }
            suspend fun send(text: String) {
                val before = withContext(Dispatchers.Main) { vm.messages.size }
                withContext(Dispatchers.Main) { vm.send(text) }
                withTimeout(10_000) {
                    while (withContext(Dispatchers.Main) { vm.messages.size < before + 2 || vm.isStreaming }) delay(20)
                }
                delay(200)
            }
            awaitMain { !vm.switching }
            send("off")
            check(reads.isEmpty())
            awaitMain { vm.messages.last().status == MessageStatus.DONE }
            val conversation = withContext(Dispatchers.Main) { vm.currentConversationId!! }
            tapDescription(Res.get(R.string.auto_read_aloud_off))
            check(withContext(Dispatchers.Main) { vm.autoReadAloud })
            check(reads.isEmpty())
            send("on")
            check(reads.size == 1 && playing)
            automation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.cacheDir, "feature-auto-read.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val beforeStop = stopCount
            tapDescription(Res.get(R.string.auto_read_aloud_on))
            check(!playing && stopCount > beforeStop)
            send("off again")
            check(reads.size == 1)
            tapDescription(Res.get(R.string.auto_read_aloud_off))
            withContext(Dispatchers.Main) { vm.newConversation(); vm.openConversation(conversation) }
            awaitMain { !vm.switching }
            delay(250)
            check(reads.size == 1)
            blocked.set(true); reached.set(false)
            withContext(Dispatchers.Main) { vm.send("stop before completion") }
            withTimeout(5_000) { while (!reached.get()) delay(10) }
            withContext(Dispatchers.Main) { vm.stop() }
            blocked.set(false)
            delay(300)
            check(reads.size == 1)
            status("auto-read: new completion once, toggle off stops, history/re-enable/stopped replies do not replay")

            store.createApiProfile("Home server")
            val stored = store.flow.first()
            val selected = mutableStateOf<String?>(null)
            instrumentation.runOnMainSync {
                activity.setContent {
                    SimpleChatTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            ApiProfilesPage(stored, null, { selected.value = it.id }, {}, {}, {}, {})
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync(); delay(250)
            val label = nodes().first { it.text?.toString() == "Home server" }
            var clickable: AccessibilityNodeInfo? = label
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            check(clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            instrumentation.waitForIdleSync()
            check(selected.value == stored.activeApiProfileId)
            automation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.cacheDir, "feature-api-profiles.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            status("API profile list renders selected profile and supports row selection")

            val boxed = """\scalebox{0.8}{\fcolorbox{purple}{pink}{\text{示例场景：状态完整保留}}}"""
            val unknown = """\unknownlayout{这段不能丢失}"""
            instrumentation.runOnMainSync {
                activity.setContent {
                    SimpleChatTheme {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            MarkdownWithMath("普通公式：\\[\\frac{1}{2}+\\sqrt{x}\\]\n\n\\[$boxed\\]\n\n\\[$unknown\\]")
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            withTimeout(10_000) {
                while (nodes().none { it.text?.toString() == Res.get(R.string.latex_compatible_source) }) delay(100)
            }
            automation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.cacheDir, "feature-latex-rendered.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            val accessibilityText = nodes().mapNotNull { it.text?.toString() }.joinToString("\n")
            check(accessibilityText.contains("unknownlayout") && accessibilityText.contains("这段不能丢失")) { "Unsupported TeX source was not exposed: $accessibilityText" }
            val sourceAction = nodes().first { it.text?.toString() == Res.get(R.string.latex_compatible_source) }
            var sourceButton: AccessibilityNodeInfo? = sourceAction
            while (sourceButton != null && !sourceButton.isClickable) sourceButton = sourceButton.parent
            check(sourceButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            instrumentation.waitForIdleSync(); delay(500)
            automation.takeScreenshot()?.let { bitmap ->
                File(instrumentation.targetContext.cacheDir, "feature-latex-fallback.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            check(nodes().any { it.text?.toString()?.contains(boxed) == true }) {
                "Source toggle: " + nodes().mapNotNull { it.text?.toString() }.joinToString(" | ")
            }
            status("LaTeX: boxed Chinese content uses compatible layout; original and unknown-command text remain fully accessible")
        } finally {
            withContext(Dispatchers.Main) { vm.stop() }
            instrumentation.runOnMainSync { activity.finish() }
            db.close()
            settingsScope.coroutineContext[Job]!!.cancelAndJoin()
            root.deleteRecursively()
        }
    }
    private suspend fun awaitMain(condition: () -> Boolean) {
        withTimeout(10_000) { while (!withContext(Dispatchers.Main) { condition() }) delay(20) }
    }
}
