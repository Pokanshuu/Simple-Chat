package com.simplechat.app

import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.chat.*
import com.simplechat.app.ui.common.dismissKeyboardOnTouch
import com.simplechat.app.ui.theme.SimpleChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Uses real Android touch dispatch, native selection and the production keyboard-dismiss wrapper. */
internal object TextSelectionRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val holdMillis = ViewConfiguration.getLongPressTimeout().toLong() + 100L
        val selection = MessageTextSelection()
        val listState = LazyListState()
        var edits = 0
        var outsideClicks = 0
        var excerpt: String? = null
        val richText = "Anchor original selection stays here.\n\n" +
            "Second paragraph for dragging across separate text nodes.\n\n" +
            "```kotlin\nval selectedCode = 123\n```\n\n" +
            "| Name | Value |\n| --- | --- |\n| TableCell | 456 |\n\n" +
            "Before formula.\n\n\$\$x^2\$\$\n\nAfter formula."
        val messages = mutableStateOf(emptyList<UiMessage>())

        suspend fun nodes(): List<AccessibilityNodeInfo> {
            runCatching { automation.clearCache() }
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                node.refresh()
                result += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            val roots = automation.windows.mapNotNull { it.root }
            if (roots.isEmpty()) automation.rootInActiveWindow?.let(::visit)
            else roots.forEach(::visit)
            return result
        }
        suspend fun findText(text: String): AccessibilityNodeInfo = withTimeoutOrNull(5000) {
            var found: AccessibilityNodeInfo? = null
            while (found == null) {
                found = nodes().firstOrNull {
                    it.packageName?.toString() == instrumentation.targetContext.packageName &&
                        it.isVisibleToUser && it.text?.startsWith(text) == true
                }
                if (found == null) delay(50)
            }
            found
        } ?: error("Missing visible text '$text'; nodes=${nodes().map { it.text ?: it.contentDescription }}")
        fun send(action: Int, x: Float, y: Float, downTime: Long) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true))
            event.recycle()
        }
        suspend fun press(x: Float, y: Float, duration: Long = 60L) {
            val start = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, x, y, start)
            delay(duration)
            send(MotionEvent.ACTION_UP, x, y, start)
            delay(180)
        }
        suspend fun tapText(text: String) {
            val rect = Rect().also { findText(text).getBoundsInScreen(it) }
            press(rect.exactCenterX(), rect.exactCenterY())
        }
        suspend fun selected(): List<String> = withContext(Dispatchers.Main) {
            selection.nativeState?.selectedTexts.orEmpty().map { it.text }
        }
        suspend fun anchors(): Any? = withContext(Dispatchers.Main) {
            selection.nativeState?.let { state ->
                with(SelectionState.Saver) {
                    object : SaverScope { override fun canBeSaved(value: Any) = true }.save(state)
                }
            }
        }
        suspend fun enter(text: String = "Anchor") {
            val rect = Rect().also { findText(text).getBoundsInScreen(it) }
            // Hit the first word, including in a multi-line user bubble.
            press(rect.left + 35f, rect.top + 25f, holdMillis)
            tapText(instrumentation.targetContext.getString(R.string.action_select_text))
            check(withTimeoutOrNull(5000) { while (selected().isEmpty()) delay(30); true } == true) {
                "Initial selection missing: active=${withContext(Dispatchers.Main) { selection.isActive }}, nodes=${nodes().map { it.text ?: it.contentDescription }}"
            }
            // Wait for the synthetic pointer's UP, not just the long-press highlight.
            delay(holdMillis + 150L)
            check(selected().isNotEmpty()) { "Initial selection was dismissed by its own pointer UP" }
        }
        suspend fun reset(role: MessageRole, text: String) {
            withContext(Dispatchers.Main) {
                selection.dismiss()
                messages.value = listOf(UiMessage("target", role, text, status = MessageStatus.DONE),
                    UiMessage("tap", MessageRole.USER, "Tap target", status = MessageStatus.DONE)) +
                    (1..55).map { UiMessage("filler-$it", MessageRole.ASSISTANT,
                        "Filler $it\n\nEnough content to move the selected row completely outside the lazy viewport.",
                        status = MessageStatus.DONE) }
                listState.scrollToItem(0)
            }
            delay(400)
        }
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    SimpleChatTheme {
                        Column(Modifier.fillMaxSize().systemBarsPadding().background(MaterialTheme.colorScheme.background)
                            .dismissMessageSelectionOnTap(selection)) {
                            TextButton(onClick = { outsideClicks++ },
                                modifier = Modifier.dismissKeyboardOnTouch(clearFocus = { !selection.isActive })) {
                                Text("Outside selection")
                            }
                            Box(Modifier.weight(1f)
                                .dismissKeyboardOnTouch(clearFocus = { !selection.isActive })) {
                                MessageList(messages.value, Modifier.fillMaxSize(), listState = listState,
                                    textSelection = selection, onEdit = { edits++ },
                                    onShareExcerpt = { _, text -> excerpt = text; selection.dismiss() })
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            findText("Outside selection")
            reset(MessageRole.ASSISTANT, richText)
            val doubleRect = Rect().also { findText("Anchor").getBoundsInScreen(it) }
            val tappedAt = SystemClock.uptimeMillis()
            val dx = doubleRect.left + 35f
            val dy = doubleRect.top + 25f
            send(MotionEvent.ACTION_DOWN, dx, dy, tappedAt)
            delay(40); send(MotionEvent.ACTION_UP, dx, dy, tappedAt)
            delay(65)
            val secondDown = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, dx, dy, secondDown)
            delay(40); send(MotionEvent.ACTION_UP, dx, dy, secondDown)
            check(withTimeoutOrNull(3000) { while (selected().isEmpty()) delay(15); true } == true) { "Double-tap did not enter selection" }
            check(selected().joinToString("").contains("Anchor"))
            val doubleElapsed = SystemClock.uptimeMillis() - secondDown - 40
            check(doubleElapsed < 1000) { "Double-tap startup took $doubleElapsed ms" }
            status("double-tap selects the touched word using measured layout: $doubleElapsed ms after second tap")
            tapText(instrumentation.targetContext.getString(R.string.share_excerpt))
            check(withContext(Dispatchers.Main) { excerpt == "Anchor" && !selection.isActive }) {
                "Excerpt action did not deliver exactly the selected word"
            }
            status("excerpt share delivers the highlighted word and closes selection")
            withContext(Dispatchers.Main) { selection.dismiss() }
            delay(200)
            enter()
            check(selected().joinToString("").contains("Anchor")) { "Selection did not start at the original press" }
            val original = anchors()
            val dragRect = Rect().also { findText("Second paragraph").getBoundsInScreen(it) }
            val start = SystemClock.uptimeMillis()
            val x = dragRect.exactCenterX()
            val y = dragRect.exactCenterY()
            send(MotionEvent.ACTION_DOWN, x, y, start)
            delay(40)
            check(anchors() == original) { "Keyboard dismissal cleared selection on DOWN" }
            repeat(24) { step ->
                send(MotionEvent.ACTION_MOVE, x, y - (step + 1) * 5f, start)
                delay(8)
            }
            send(MotionEvent.ACTION_UP, x, y - 120f, start)
            delay(700)
            check(anchors() == original) { "Small per-frame moves were mistaken for a tap" }
            check(withContext(Dispatchers.Main) { listState.firstVisibleItemScrollOffset > 0 }) { "Drag did not scroll" }
            status("selection survives pointer DOWN, slow cumulative drag and fling through production keyboard wrapper")

            withContext(Dispatchers.Main) { listState.scrollToItem(50) }
            delay(250)
            check(withContext(Dispatchers.Main) { listState.layoutInfo.visibleItemsInfo.none { it.key == "target" } })
            check(anchors() == original) { "Offscreen selected lazy item lost its anchors" }
            withContext(Dispatchers.Main) { listState.scrollToItem(0) }
            delay(250)
            check(anchors() == original) { "Returning to selected message changed the range" }
            status("selected row scrolled completely out and back; native text, anchor IDs and offsets unchanged")

            // Streaming another reply must not rebuild the selected row or its native anchors.
            repeat(30) { token ->
                withContext(Dispatchers.Main) {
                    messages.value = messages.value.dropLast(1) + messages.value.last().copy(
                        content = "Streaming tail " + "token ".repeat(token + 1), status = MessageStatus.STREAMING)
                }
                delay(25)
            }
            check(anchors() == original) { "Streaming another message changed the selection" }

            val first = Rect().also { findText("Anchor").getBoundsInScreen(it) }
            val second = Rect().also { findText("Second paragraph").getBoundsInScreen(it) }
            val longStart = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, first.left + 35f, first.exactCenterY(), longStart)
            delay(holdMillis)
            check(withContext(Dispatchers.Main) { selection.isActive }) { "Long press exited selection" }
            repeat(20) { step ->
                val fraction = (step + 1) / 20f
                send(MotionEvent.ACTION_MOVE,
                    first.left + 35f + (second.right - first.left - 55f) * fraction,
                    first.exactCenterY() + (second.exactCenterY() - first.exactCenterY()) * fraction, longStart)
                delay(16)
            }
            send(MotionEvent.ACTION_UP, second.right - 20f, second.exactCenterY(), longStart)
            delay(250)
            check(selected().size >= 2 && withContext(Dispatchers.Main) { selection.isActive }) {
                "Native long-press drag did not extend across paragraphs: ${selected()}"
            }
            status("30 streaming updates preserve anchors; native long-press drag extends across paragraphs")

            // Native all-selection spans paragraphs, code and table, excluding code UI labels.
            withContext(Dispatchers.Main) { selection.nativeState!!.selectAll() }
            delay(250)
            val all = selected()
            check(all.any { it.contains("selectedCode") } && all.any { it.contains("TableCell") } &&
                all.any { it.contains("After formula") }) { "Cross-node selection missing content: $all" }
            check(all.none { it == "kotlin" }) { "Code header leaked into copied selection" }
            val copyLabel = instrumentation.targetContext.getString(android.R.string.copy)
            tapText(copyLabel)
            val copied = withContext(Dispatchers.Main) {
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            }
            check(copied.contains("selectedCode") && copied.contains("TableCell") && copied.contains("After formula"))
            withTimeout(3000) { while (withContext(Dispatchers.Main) { selection.isActive }) delay(30) }
            status("native select-all/copy crosses Markdown, code, table and formula; Copy leaves no ghost mode")

            enter()
            tapText("Tap target")
            check(!withContext(Dispatchers.Main) { selection.isActive } && edits == 0)
            enter()
            tapText("Outside selection")
            check(!withContext(Dispatchers.Main) { selection.isActive } && outsideClicks == 0)
            tapText("Outside selection")
            check(outsideClicks == 1)
            enter()
            val blankBesideMessage = Rect().also { findText("Tap target").getBoundsInScreen(it) }
            press(8f, blankBesideMessage.exactCenterY())
            check(!withContext(Dispatchers.Main) { selection.isActive })
            status("tap another message or top control exits without click-through; next normal click works")

            for ((name, role, body) in listOf(
                Triple("user", MessageRole.USER, "Anchor user bubble has original selectable text."),
                Triple("expanded-user", MessageRole.USER, "Anchor expanded user bubble.\n" + "User chunk text stays in order.\n".repeat(100)),
                Triple("long-reply", MessageRole.ASSISTANT,
                    "Anchor continuous reply.\n\n" + ("Continuous paragraph. ".repeat(10) + "\n\n").repeat(180)),
            )) {
                reset(role, body)
                if (name == "expanded-user") {
                    val description = instrumentation.targetContext.getString(R.string.msg_expand_all)
                    val button = nodes().first { it.contentDescription?.toString() == description && it.isVisibleToUser }
                    val bounds = Rect().also { button.getBoundsInScreen(it) }
                    press(bounds.exactCenterX(), bounds.exactCenterY())
                    delay(300)
                }
                enter()
                if (name == "expanded-user") {
                    withContext(Dispatchers.Main) { selection.nativeState!!.selectAll() }
                    check(selected().size > 1 && selected().joinToString("") == body) {
                        "Expanded user chunks overlapped or copied out of order"
                    }
                }
                val before = anchors()
                withContext(Dispatchers.Main) { listState.scrollToItem(50) }
                delay(200)
                withContext(Dispatchers.Main) { listState.scrollToItem(0) }
                delay(200)
                check(anchors() == before) { "$name selection changed offscreen" }
                tapText("Outside selection")
                check(!withContext(Dispatchers.Main) { selection.isActive })
                enter() // Immediate menu re-entry after exit, including a pinned long reply.
                status("$name: original-point selection, offscreen return, tap exit and immediate menu re-entry")
            }
        } finally {
            instrumentation.runOnMainSync { selection.dismiss(); activity.finish() }
        }
    }
}
