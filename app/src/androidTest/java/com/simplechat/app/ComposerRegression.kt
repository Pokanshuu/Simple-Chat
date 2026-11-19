package com.simplechat.app

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
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
import com.simplechat.app.ui.chat.ContextStats
import com.simplechat.app.ui.chat.InputBar
import com.simplechat.app.ui.theme.SimpleChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
