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
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.*
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode
import java.io.File
import java.util.Collections
import kotlinx.coroutines.*

internal object MessageShareRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit, deletionOnly: Boolean = false) {
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
        suspend fun optionChecked(id: Int): Boolean {
            val label = context.getString(id)
            val checkbox = generateSequence(find(label)) { it.parent }.firstOrNull { it.isCheckable }
                ?: error("Image option is not exposed as a checkbox: $label")
            return checkbox.isChecked
        }
        suspend fun count(n: Int) { find(context.getString(R.string.share_selected_count, n)) }
        suspend fun screenshot(name: String) {
            val image = checkNotNull(automation.takeScreenshot())
            File(context.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        suspend fun runDeletion() {
                val cid = repo.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "model", title = "Version deletion regression")
                val question = repo.appendUserMessage(cid, (1..28).joinToString("\n") { "Earlier context $it" })
                val oldest = repo.addActiveVariant(cid, question.id, MessageRole.ASSISTANT, "Oldest version")
                val shorter = repo.addActiveVariant(cid, question.id, MessageRole.ASSISTANT, "Short version")
                val alternateTail = repo.appendUserMessage(cid, (1..9).joinToString("\n") { "Alternate continuation $it" })
                val longer = repo.addActiveVariant(cid, question.id, MessageRole.ASSISTANT,
                    (1..22).joinToString("\n\n") { "Long version paragraph $it" })
                val activeTail = repo.appendUserMessage(cid, (1..9).joinToString("\n") { "Current continuation $it" })
                withContext(Dispatchers.Main) { vm.openConversation(cid) }
                withTimeout(10_000) { while (withContext(Dispatchers.Main) { vm.switching || vm.messages.size != 3 }) delay(30) }
                instrumentation.runOnMainSync {
                    activity.setContent { SimpleChatTheme(themeMode = ThemeMode.DARK) { ChatScreen(vm, {}, {}, Modifier.fillMaxSize()) } }
                }
                delay(650)
                val width = activity.resources.displayMetrics.widthPixels
                val height = activity.resources.displayMetrics.heightPixels
                repeat(3) {
                    if (nodes().none { it.isVisibleToUser && it.text?.toString() == "3/3" }) {
                        val start = SystemClock.uptimeMillis()
                        val x = width * 0.5f
                        send(MotionEvent.ACTION_DOWN, x, height * 0.29f, start)
                        for (step in 1..16) {
                            delay(20)
                            send(MotionEvent.ACTION_MOVE, x, height * (0.29f + 0.021f * step), start)
                        }
                        send(MotionEvent.ACTION_UP, x, height * 0.626f, start)
                        delay(360)
                    }
                }
                find("3/3")
                suspend fun versionControlY(count: String): Int {
                    val control = find(count)
                    return Rect().also(control::getBoundsInScreen).centerY()
                }
                val startY = versionControlY("3/3")
                val tolerance = (16 * activity.resources.displayMetrics.density).toInt()
                suspend fun checkAnchor(count: String) {
                    find(count)
                    delay(400)
                    val actualY = versionControlY(count)
                    check(kotlin.math.abs(actualY - startY) <= tolerance) { "Version $count moved from $startY to $actualY px" }
                }
                tapResource(R.string.msg_variant_prev)
                find("Short version")
                checkAnchor("2/3")
                tapResource(R.string.msg_variant_next)
                checkAnchor("3/3")
                tapResource(R.string.msg_variant_prev)
                find("2/3")
                tapResource(R.string.msg_variant_prev)
                find("Oldest version")
                val terminalFooter = Rect().also(find("1/3")::getBoundsInScreen)
                val input = Rect().also(find(context.getString(R.string.input_hint))::getBoundsInScreen)
                val maxGap = (80 * activity.resources.displayMetrics.density).toInt()
                check(input.top - terminalFooter.bottom in 0..maxGap) {
                    "Switching to a terminal version left the footer at ${terminalFooter.bottom} and input at ${input.top}"
                }
                tapResource(R.string.msg_variant_next)
                find("2/3")
                tapResource(R.string.msg_variant_next)
                find("3/3")
                touch("Long version paragraph 22", long = true)
                tapResource(R.string.action_delete)
                find(context.getString(R.string.msg_delete_reply_body, context.getString(R.string.msg_delete_body)))
                tapResource(R.string.action_cancel)
                check(db.messageDao().getLightFor(cid).single { it.id == longer.id }.visible)
                touch("Long version paragraph 22", long = true)
                tapResource(R.string.action_delete)
                tapResource(R.string.msg_delete_all_versions)
                tapResource(R.string.msg_variant_prev)
                find("Alternate continuation 1")
                tapResource(R.string.msg_variant_next)
                find("Current continuation 1")
                check(repo.loadActivePath(cid).filterNot { it.deletedNavigation }.map { it.id } == listOf(question.id, activeTail.id))
                check(db.messageDao().getLightFor(cid).filter { it.id in setOf(oldest.id, shorter.id, longer.id) }.all { it.trashedAt != null })
                status("long and short versions keep their controls aligned; all versions move to the Recycle Bin with continuations intact")
        }
        try {
            if (deletionOnly) {
                runDeletion()
                return
            }
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
            val rowBounds = Rect().also { find("回答 4").getBoundsInScreen(it) }
            val x = rowBounds.exactCenterX()
            val y = rowBounds.exactCenterY()
            val distance = minOf(activity.window.decorView.height * 0.15f, (y - statusBottom) * 0.7f)
            val swipeStart = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, x, y, swipeStart)
            repeat(20) { step ->
                send(MotionEvent.ACTION_MOVE, x, y - distance * (step + 1) / 20, swipeStart)
                delay(12)
            }
            send(MotionEvent.ACTION_UP, x, y - distance, swipeStart)
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
            check(optionChecked(R.string.share_image_hide_reasoning)) { "Reasoning should be hidden by default" }
            tapResource(R.string.share_image_hide_reasoning)
            check(!optionChecked(R.string.share_image_hide_reasoning)) { "Reasoning checkbox did not toggle" }
            tapResource(R.string.action_cancel)
            tapResource(R.string.share_image)
            check(optionChecked(R.string.share_image_hide_reasoning)) { "Cancelled opt-in was retained" }
            tapResource(R.string.share_image_hide_branding)
            screenshot("share-image-options.png")
            tapResource(R.string.share_image_pages)
            tapResource(R.string.share_image_generate)
            find(context.getString(R.string.share_image_preview, 1))
            delay(600)
            screenshot("share-image-preview.png")
            tapResource(R.string.action_close)
            tapResource(R.string.share_image)
            check(optionChecked(R.string.share_image_hide_branding)) { "Hide-branding option was not retained" }
            check(optionChecked(R.string.share_image_hide_reasoning))
            tapResource(R.string.share_image_hide_reasoning)
            tapResource(R.string.share_image_long)
            tapResource(R.string.share_image_generate)
            find(context.getString(R.string.share_image_preview, 1))
            tapResource(R.string.action_close)
            tapResource(R.string.share_exit)
            find("未发送草稿")
            touch("回答 3", long = true)
            find(context.getString(R.string.action_select_text))
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            status("image reasoning is hidden on each open, the checkbox can opt in, preview opens, and exit restores the draft")

            val thought = "First, compare the two available layouts. Then choose the best fit for the content."
            val reply = UiMessage("reasoning", MessageRole.ASSISTANT, "The answer is ready.")
            val legacyReply = reply.copy(reasoning = thought)
            val timelineReply = legacyReply.copy(timeline = listOf(
                ReplyBlock.Thinking(thought, signature = "PRIVATE_SIGNATURE"), ReplyBlock.Text(reply.content)))
            suspend fun reasoningImage(message: UiMessage, hideReasoning: Boolean = true) = exportSharedImages(
                activity, MessageShareDocument("Reasoning export", messageShareGroups(listOf(message))),
                hideReasoning = hideReasoning,
            ).single()
            val plain = reasoningImage(reply)
            val hidden = reasoningImage(timelineReply)
            val legacyShown = reasoningImage(legacyReply, hideReasoning = false)
            val timelineShown = reasoningImage(timelineReply, hideReasoning = false)
            check(plain.readBytes().contentEquals(hidden.readBytes())) { "Default image changed when reasoning was present" }
            check(legacyShown.readBytes().contentEquals(timelineShown.readBytes())) { "Timeline image duplicated legacy reasoning" }
            fun imageHeight(file: File): Int {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                return bounds.outHeight
            }
            check(imageHeight(timelineShown) > imageHeight(hidden)) { "Opt-in image did not expand to show reasoning" }
            fun reasoningRuleHeight(file: File): Int {
                val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path))
                try {
                    val rail = 0xFFD8DADE.toInt()
                    var run = 0
                    var longest = 0
                    for (y in 0 until bitmap.height) {
                        run = if (bitmap.getPixel(65, y) == rail && bitmap.getPixel(66, y) == rail) run + 1 else 0
                        longest = maxOf(longest, run)
                    }
                    return longest
                } finally { bitmap.recycle() }
            }
            check(reasoningRuleHeight(hidden) == 0) { "Hidden reasoning left a vertical rule" }
            check(reasoningRuleHeight(timelineShown) == 0) { "Opt-in reasoning added a vertical rule" }
            val multiPageThought = legacyReply.copy(reasoning = (1..100).joinToString("\n") { "Reasoning line $it." })
            val thoughtPages = exportSharedImages(activity,
                MessageShareDocument("Paged reasoning", messageShareGroups(listOf(multiPageThought))),
                hideReasoning = false,
            )
            check(thoughtPages.size > 1 && thoughtPages.all { reasoningRuleHeight(it) == 0 }) {
                "Paged reasoning should span pages without a vertical rule"
            }
            status("PNG output hides reasoning by default and renders opt-in reasoning across pages without a rule")

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

            touch("回答 4", long = true)
            tapResource(R.string.share_select_delete)
            count(1)
            tapResource(R.string.action_delete)
            find(context.getString(R.string.share_delete_warning, 1))
            tapResource(R.string.action_cancel)
            count(1)
            tapResource(R.string.action_delete)
            tapResource(R.string.share_delete_confirm)
            find(context.getString(R.string.share_delete_done, 1))
            withTimeout(5000) { while (nodes().any { it.text?.toString()?.contains("回答 4") == true }) delay(40) }
            check(nodes().any { it.text?.toString()?.contains("回答 3") == true })
            status("multi-select deletion warns before removing selected versions and exits without deleting later messages")
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
            storeScope.cancel()
            db.close()
        }
    }
}
