package com.simplechat.app

import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.ChatScreen
import com.simplechat.app.ui.chat.ChatViewModel
import com.simplechat.app.ui.chat.ContextStats
import com.simplechat.app.ui.chat.ConversationMode
import com.simplechat.app.ui.chat.MessageStatus
import com.simplechat.app.ui.chat.InputBar
import com.simplechat.app.ui.theme.SimpleChatTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal object ComposerRegression {
    suspend fun run(instrumentation: Instrumentation, passed: (String) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        val automation = instrumentation.uiAutomation
        val draft = (1..30).joinToString("\n") { "Draft line $it" }
        val field = TextFieldState(draft)
        var expanded by mutableStateOf(false)
        var focusCount = 0
        var barBounds = androidx.compose.ui.geometry.Rect.Zero
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    SimpleChatTheme {
                        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
                            InputBar(
                                fieldState = field,
                                expanded = expanded,
                                onToggleExpanded = { expanded = !expanded },
                                thinkingEnabled = false,
                                thinkingPanelOpen = false,
                                onOpenThinkingPanel = {},
                                settingsPanelOpen = false,
                                onOpenSettingsPanel = {},
                                streaming = false,
                                onSend = {},
                                onStop = {},
                                onAttachClick = {},
                                contextStats = ContextStats(0, 0, 0, 0, 0, null, null, null, false),
                                statsPanelOpen = false,
                                onOpenStatsPanel = {},
                                onTextFieldFocused = { focusCount++ },
                                modifier = Modifier.onGloballyPositioned { barBounds = it.boundsInWindow() },
                            )
                        }
                    }
                }
            }
            fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
                fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (predicate(node)) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let { find(it)?.let { hit -> return hit } }
                    return null
                }
                return automation.rootInActiveWindow?.let(::find)
            }
            suspend fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo = withTimeout(5_000) {
                var node = findNode(predicate)
                while (node == null) {
                    delay(50)
                    node = findNode(predicate)
                }
                node
            }
            fun AccessibilityNodeInfo.bounds() = Rect().also(::getBoundsInScreen)
            fun tap(x: Int, y: Int) {
                val down = SystemClock.uptimeMillis()
                for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
            }
            fun imeVisible(): Boolean {
                var visible = false
                instrumentation.runOnMainSync {
                    visible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                return visible
            }
            suspend fun pressToggle(labelId: Int) {
                val label = activity.getString(labelId)
                val bounds = awaitNode { it.contentDescription?.toString() == label }.bounds()
                tap(bounds.centerX(), bounds.centerY())
            }
            fun assertDuringResize(expectedIme: Boolean) {
                instrumentation.runOnMainSync {
                    check(field.selection == TextRange(draft.length - 2)) { "Caret moved to ${field.selection}" }
                    check(field.text.toString() == draft)
                    check(focusCount == 1) { "Text input refocused $focusCount times" }
                }
                check(imeVisible() == expectedIme) { "IME visibility changed during resize" }
            }

            // Opening a large viewport on an unfocused draft must not bring up the IME.
            val narrowUnfocused = awaitNode { it.isEditable }.bounds()
            pressToggle(R.string.composer_expand)
            delay(350)
            val tallUnfocused = awaitNode { it.isEditable }.bounds()
            check(tallUnfocused.height() > narrowUnfocused.height())
            check(tallUnfocused.width() == narrowUnfocused.width())
            check(!imeVisible())
            pressToggle(R.string.composer_collapse)
            delay(350)

            val initial = awaitNode { it.isEditable }.bounds()
            tap(initial.centerX(), initial.centerY())
            withTimeout(5_000) { while (!imeVisible()) delay(50) }
            instrumentation.runOnMainSync { field.edit { selection = TextRange(draft.length - 2) } }
            delay(700)
            val compact = withContext(Dispatchers.Main) { barBounds }
            check(awaitNode { it.isEditable && it.isFocused }.bounds().height() > 0)
            pressToggle(R.string.composer_expand)
            repeat(7) { delay(50); assertDuringResize(expectedIme = true) }
            val tall = withContext(Dispatchers.Main) { barBounds }
            // Some IMEs adjust their inset as the selection UI updates. Allow small inset
            // changes while still checking that the card grows from its bottom edge.
            val insetTolerance = 16 * activity.resources.displayMetrics.density
            check(tall.top < compact.top && kotlin.math.abs(tall.bottom - compact.bottom) <= insetTolerance &&
                tall.width == compact.width) {
                "Composer should grow up in place. Compact $compact, expanded $tall"
            }
            pressToggle(R.string.composer_collapse)
            repeat(7) { delay(50); assertDuringResize(expectedIme = true) }
            val collapsed = withContext(Dispatchers.Main) { barBounds }
            check(collapsed.height == compact.height && collapsed.width == compact.width &&
                kotlin.math.abs(collapsed.bottom - compact.bottom) <= insetTolerance) {
                "Composer did not return to its compact size. Initial $compact, collapsed $collapsed"
            }
            passed("composer grows upward at the same width and returns to size, preserving cursor, focus and IME")
            editAndResend(instrumentation, activity, ::awaitNode, ::tap, passed)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private suspend fun editAndResend(
        instrumentation: Instrumentation,
        activity: MainActivity,
        awaitNode: suspend ((AccessibilityNodeInfo) -> Boolean) -> AccessibilityNodeInfo,
        tap: (Int, Int) -> Unit,
        passed: (String) -> Unit,
    ) {
        val original = "Original prompt"
        val edited = "Edited prompt"
        val draft = "Separate unsent draft"
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "composer-edit-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getApplicationContext() = this
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(ctx, PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") })
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val repo = ChatRepository(db, ctx)
        val request = AtomicReference<String>()
        val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body!!.writeTo(it) }
            request.set(body.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data: {\"choices\":[{\"delta\":{\"content\":\"Edit reply\"}}]}\n\ndata: [DONE]\n\n"
                    .toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val json = ChatApi.defaultJson()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        try {
            store.setProvider("OPENCODE_GO", "https://composer-test.invalid", "deepseek-flash")
            store.setApiStyle("OPENAI")
            val id = repo.createConversation(ConversationMode.CREATIVE, "OPENCODE_GO", "deepseek-flash")
            val otherId = repo.createConversation(ConversationMode.CREATIVE, "OPENCODE_GO", "deepseek-flash")
            val target = repo.appendUserMessage(id, original)
            val oldReply = repo.beginAssistantMessage(id, target.id)
            repo.finishMessage(oldReply, "Previous reply", null, MessageStatus.DONE)
            repo.saveDraft(id, draft)
            val vm = withContext(Dispatchers.Main) {
                ChatViewModel(api, store, repo, "composer-edit").apply { openConversation(id) }
            }
            instrumentation.runOnMainSync {
                activity.setContent { SimpleChatTheme { ChatScreen(vm, {}, {}) } }
            }
            suspend fun find(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo =
                try {
                    awaitNode { it.isVisibleToUser && predicate(it) }
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                    throw AssertionError("Could not find $label", error)
                }
            suspend fun editor(text: String) = find("composer containing '$text'") {
                it.isEditable && it.text?.toString() == text
            }
            suspend fun tapNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
                val bounds = Rect().also(find(label, predicate)::getBoundsInScreen)
                tap(bounds.centerX(), bounds.centerY())
            }
            suspend fun openOriginalBubble() {
                val bubble = find("original message bubble") { node ->
                    node.isClickable && (0 until node.childCount).any {
                        node.getChild(it)?.text?.toString() == original
                    }
                }
                check(bubble.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            editor(draft)
            openOriginalBubble()
            check(editor(original).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edited)
            }))
            editor(edited)
            openOriginalBubble()
            instrumentation.waitForIdleSync()
            editor(edited)

            withContext(Dispatchers.Main) { vm.openConversation(otherId) }
            find("other conversation composer") { it.isEditable && it.text?.toString() != edited }
            withContext(Dispatchers.Main) { vm.openConversation(id) }
            editor(edited)
            withTimeout(5_000) { while (withContext(Dispatchers.Main) { vm.switching }) delay(50) }
            tapNode("send button") { it.contentDescription?.toString() == activity.getString(R.string.input_send) }

            withTimeout(10_000) {
                while (withContext(Dispatchers.Main) { vm.messages.lastOrNull()?.status != MessageStatus.DONE ||
                    vm.messages.lastOrNull()?.content != "Edit reply" }) delay(50)
            }
            val path = repo.loadActivePath(id)
            check(path.first().content == edited && path.first().id != target.id && path.first().variantCount == 2)
            val submitted = checkNotNull(request.get())
            check(edited in submitted && original !in submitted && draft !in submitted)
            editor(draft)
            check(repo.findDraft(id) == draft)
            passed("edited prompt survives reopening the bubble and switching conversations, then reaches the API and database")
        } finally {
            instrumentation.runOnMainSync { activity.setContent {} }
            scope.cancel()
            db.close()
            root.deleteRecursively()
        }
    }
}
