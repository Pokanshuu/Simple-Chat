package com.simplechat.app.ui.chat

/** Each row owns its message so an older lazy item provider stays safe after a path change. */
data class MessageListEntry(
    val key: String,
    val message: UiMessage,
    val messageIndex: Int,
    val lastOnPath: Boolean,
    val footer: Boolean = false,
    val separateActions: Boolean = false,
)

internal fun messageBranchKey(message: UiMessage): String = "branch-footer:${message.parentId}"

internal fun messageListEntries(messages: List<UiMessage>, selecting: Boolean): List<MessageListEntry> = buildList {
    messages.forEachIndexed { index, message ->
        val lastOnPath = index == messages.lastIndex
        val hasFooter = !selecting && !message.streaming && message.variantCount > 1
        if (message.deletedNavigation) {
            if (hasFooter) add(MessageListEntry(messageBranchKey(message), message, index, lastOnPath, footer = true))
        } else {
            add(MessageListEntry(message.id, message, index, lastOnPath, separateActions = hasFooter))
            if (hasFooter) add(MessageListEntry(messageBranchKey(message), message, index, lastOnPath, footer = true))
        }
    }
}
