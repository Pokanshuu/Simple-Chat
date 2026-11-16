package com.simplechat.app.ui.chat

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** Emitted by live generation only; loading history and switching branches never creates one. */
data class CompletedReply(val conversationId: String, val messageId: String)

internal fun autoReadReply(event: CompletedReply, currentConversationId: String?, enabled: Boolean,
    available: Boolean, messages: List<UiMessage>): UiMessage? =
    if (!enabled || !available || event.conversationId != currentConversationId) null
    else messages.firstOrNull { it.id == event.messageId && it.role == MessageRole.ASSISTANT &&
        it.status == MessageStatus.DONE && it.content.isNotBlank() }

/** Lifecycle-bound playback policy, shared by the real screen and device regression harness. */
@Composable
internal fun AutoReadAloudEffect(vm: ChatViewModel, available: Boolean,
    read: (String, String) -> Unit, stop: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val readNow by rememberUpdatedState(read)
    val stopNow by rememberUpdatedState(stop)
    val availableNow by rememberUpdatedState(available)
    LaunchedEffect(vm, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.completedReplies.collect { event ->
                autoReadReply(event, vm.currentConversationId, vm.autoReadAloud, availableNow, vm.messages)
                    ?.let { readNow(it.id, it.content) }
            }
        }
    }
    LaunchedEffect(vm.autoReadAloud) { if (!vm.autoReadAloud) stopNow() }
    LaunchedEffect(vm.currentConversationId, available) { stopNow() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) stopNow() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stopNow() }
    }
}
