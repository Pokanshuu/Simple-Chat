package com.simplechat.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.simplechat.app.data.*
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.*
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode
import java.io.File
import java.util.Collections
import kotlinx.coroutines.*

internal object MessageShareRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "share-regression-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : ContextWrapper(context) { override fun getFilesDir() = root; override fun getApplicationContext() = this }
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(ctx, PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { File(root, "settings.preferences_pb") }))
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val repo = ChatRepository(db, ctx)
        val client = ChatApi.defaultClient()
        val json = ChatApi.defaultJson()
        val vm = withContext(Dispatchers.Main) { ChatViewModel(ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json), store, repo, "share-test") }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val intents = Collections.synchronizedList(mutableListOf<Intent>())
        val savedMarkdown = File(shareDirectory(context), "saved.md").apply { writeText("") }
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CHOOSER) {
                    intents += intent
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
                if (intent.action == Intent.ACTION_CREATE_DOCUMENT) {
                    intents += intent
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", savedMarkdown)))
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        suspend fun nodes(): List<AccessibilityNodeInfo> {
            if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { automation.clearCache() }
            val list = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                if (android.os.Build.VERSION.SDK_INT < 33) node.refresh()
                list += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            automation.windows.mapNotNull { it.root }.forEach(::visit)
            return list.filter { it.packageName?.toString() == context.packageName }
        }
        suspend fun find(label: String): AccessibilityNodeInfo = withTimeoutOrNull(6000) {
            var node: AccessibilityNodeInfo? = null
            while (node == null) {
                val visible = nodes().filter { it.isVisibleToUser }
                // Prefer the button label over descriptions such as “生成一张长图…”.
                node = visible.firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
                    ?: visible.firstOrNull { it.text?.toString()?.startsWith(label) == true }
                if (node == null) delay(50)
            }
            node
        } ?: run {
            automation.takeScreenshot()?.let { bitmap ->
                File(activity.cacheDir, "share-ui-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            error("Missing '$label': ${nodes().map { it.text ?: it.contentDescription }}")
        }
        fun send(action: Int, x: Float, y: Float, start: Long) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true)); event.recycle()
        }
        suspend fun touch(label: String, long: Boolean = false) {
            val rect = Rect().also { find(label).getBoundsInScreen(it) }
            val x = rect.exactCenterX(); val y = rect.top + minOf(25f, rect.height() / 2f)
            val start = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, x, y, start)
            delay(if (long) ViewConfiguration.getLongPressTimeout().toLong() + 100 else 70)
            send(MotionEvent.ACTION_UP, x, y, start)
            delay(250)
        }
        suspend fun tapResource(id: Int) = touch(context.getString(id))
        suspend fun count(n: Int) { find(context.getString(R.string.share_selected_count, n)) }
        suspend fun screenshot(name: String) {
            val image = checkNotNull(automation.takeScreenshot())
            File(context.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        try {
            val cid = repo.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "model", title = "多选分享 · 回归测试")
            for (index in 1..4) {
                val question = repo.appendUserMessage(cid, "问题 $index：一起整理这段对话。")
                val answer = repo.beginAssistantMessage(cid, question.id)
                repo.finishMessage(answer, "回答 $index：保留完整正文，按 **一问一答** 分享。\n\n这段文字用于验证滚动与多选不会触发编辑。", "PRIVATE_REASONING", MessageStatus.DONE)
            }
            withContext(Dispatchers.Main) { vm.openConversation(cid) }
            withTimeout(10000) { while (withContext(Dispatchers.Main) { vm.switching || vm.messages.size != 8 }) delay(30) }
            instrumentation.runOnMainSync {
                // Match MainActivity's edge-to-edge host: the production top bars own their insets.
                activity.setContent { SimpleChatTheme(themeMode = ThemeMode.DARK) { ChatScreen(vm, {}, {}, Modifier.fillMaxSize()) } }
            }
            find("回答 4")
            // Keep a real draft through entering and leaving share mode.
            val editor = nodes().first { it.isEditable }
            check(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "未发送草稿")
            }))
            touch("回答 4", long = true)
            tapResource(R.string.action_share)
            count(1)
            @Suppress("DEPRECATION")
            val statusBottom = activity.window.decorView.rootWindowInsets.systemWindowInsetTop
            check(statusBottom > 0) { "Status bar must be visible for the inset regression" }
            for (label in listOf(R.string.share_select_all, R.string.share_exit)) {
                val bounds = Rect().also { find(context.getString(label)).getBoundsInScreen(it) }
                check(bounds.top >= statusBottom) { "Share top control overlaps status bar: $bounds, inset=$statusBottom" }
            }
            status("edge-to-edge share header keeps Select all and Close below the visible status bar")
            check(nodes().none { it.isEditable })
            touch("问题 4")
            count(0)
            touch("回答 4")
            count(1)
            check(nodes().none { it.text?.toString() == context.getString(R.string.msg_edit_input) })
            tapResource(R.string.share_select_all)
            count(4)
            tapResource(R.string.share_select_all)
            count(0)
            touch("回答 4")
            count(1)
            val swipeStart = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, 1100f, 1600f, swipeStart)
            repeat(20) { step ->
                send(MotionEvent.ACTION_MOVE, 1100f, 1600f - step * 12, swipeStart)
                delay(12)
            }
            send(MotionEvent.ACTION_UP, 1100f, 1360f, swipeStart)
            delay(450)
            count(1)
            screenshot("share-selection-dark.png")
            status("production ChatScreen: long-press Share, group toggle from either message, all/none, draft hidden, no edit click-through")

            tapResource(R.string.share_copy_text)
            find(context.getString(R.string.share_copied))
            val copied = withContext(Dispatchers.Main) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString() }
            check(copied.contains("问题 4") && copied.contains("回答 4") && !copied.contains("问题 3") && !copied.contains("PRIVATE_REASONING"))
            tapResource(R.string.share_markdown)
            withTimeout(10000) { while (!savedMarkdown.readText().contains("回答 4")) delay(40) }
            check(savedMarkdown.readText().contains("**一问一答**") && !savedMarkdown.readText().contains("PRIVATE_REASONING"))
            tapResource(R.string.share_more)
            withTimeout(6000) { while (intents.none { it.action == Intent.ACTION_CHOOSER }) delay(40) }
            @Suppress("DEPRECATION")
            val sendIntent = intents.last { it.action == Intent.ACTION_CHOOSER }.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            check(sendIntent.type == "text/markdown" && sendIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            check(sendIntent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0)
            @Suppress("DEPRECATION")
            val sharedUri = sendIntent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
            check(sharedUri.scheme == "content" && context.contentResolver.openInputStream(sharedUri)!!.bufferedReader().use { it.readText() }.contains("回答 4"))
            status("copy and Markdown export contain exactly the selected exchange; system share has readable content URI and only read permission")

            tapResource(R.string.share_image)
            find(context.getString(R.string.share_image_long))
            tapResource(R.string.share_image_hide_branding)
            screenshot("share-image-options.png")
            tapResource(R.string.share_image_pages)
            tapResource(R.string.share_image_generate)
            find(context.getString(R.string.share_image_preview, 1))
            delay(600)
            screenshot("share-image-preview.png")
            tapResource(R.string.action_close)
            tapResource(R.string.share_image)
            check(nodes().any { it.isCheckable && it.isChecked }) { "Hide-branding option was not retained" }
            tapResource(R.string.share_image_long)
            tapResource(R.string.share_image_generate)
            find(context.getString(R.string.share_image_preview, 1))
            tapResource(R.string.action_close)
            tapResource(R.string.share_exit)
            find("未发送草稿")
            touch("回答 3", long = true)
            find(context.getString(R.string.action_select_text))
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            status("generated image preview opens; exit restores draft and normal long-press menu")

            val body = "# 图片分享\n\n支持 **加粗**、`行内代码` 与中文 😀。\n\n```kotlin\nval example = 42\n```\n\n" +
                (1..110).joinToString("\n\n") { "第 $it 段：长内容分页输出，所有已选文字都保留。" }
            val document = MessageShareDocument("完整分享示例", messageShareGroups(listOf(
                UiMessage("q", MessageRole.USER, "请整理一下这份资料。"), UiMessage("a", MessageRole.ASSISTANT, body))))
            val pages = exportSharedImages(activity, document)
            check(pages.size > 1)
            pages.forEach { file ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                check(bounds.outWidth == 1080 && bounds.outHeight in 300..2400)
            }
            pages.first().copyTo(File(context.cacheDir, "share-export-first.png"), overwrite = true)
            pages.last().copyTo(File(context.cacheDir, "share-export-last.png"), overwrite = true)
            val multi = messageShareIntent(context, pages, "image/png", document.title)
            check(multi.action == Intent.ACTION_SEND_MULTIPLE && multi.clipData!!.itemCount == pages.size)
            status("long formatted text generates ${pages.size} bounded PNG pages; multi-image intent grants every page")
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
            storeScope.cancel()
            db.close()
        }
    }
}
