package com.simplechat.app.ui.chat

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

/** Screen-level mode; selection geometry and handles remain owned by Compose. */
@Stable
class MessageTextSelection {
    var messageId: String? by mutableStateOf(null)
        private set

    val isActive: Boolean get() = messageId != null

    internal var nativeState: SelectionState? = null
        private set

    internal fun begin(messageId: String, state: SelectionState) {
        dismiss()
        nativeState = state
        this.messageId = messageId
    }

    fun dismiss() {
        nativeState?.clear()
        nativeState = null
        messageId = null
    }

    internal fun dismiss(messageId: String) {
        if (this.messageId == messageId) dismiss()
    }
}

/**
 * Observe before children, but only consume the UP of a completed short tap. DOWN and
 * movement must reach native selection/scroll handlers. Distance is measured from DOWN,
 * not from the previous frame: many small moves are still a drag. Handle/toolbar popups
 * have their own pointer stream and are deliberately left to Compose.
 */
internal fun Modifier.dismissMessageSelectionOnTap(selection: MessageTextSelection): Modifier =
    pointerInput(selection) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val selectedId = selection.messageId ?: return@awaitEachGesture
            var dragged = false
            var multiplePointers = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) multiplePointers = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    dragged = true
                }
                if (change.changedToUpIgnoreConsumed()) {
                    if (!dragged && !multiplePointers &&
                        change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                    ) {
                        change.consume()
                        selection.dismiss(selectedId)
                    }
                    break
                }
            }
        }
    }

internal val LocalShareExcerpt = staticCompositionLocalOf<((String) -> Unit)?> { null }
