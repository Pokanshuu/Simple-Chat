package com.simplechat.app.ui.chat

/** A separate, measurable footer keeps branch controls anchored below variable-height content. */
data class MessageListEntry(val key: String, val messageIndex: Int, val footer: Boolean = false, val separateActions: Boolean = false)

internal fun messageBranchKey(message: UiMessage): String = "branch-footer:${message.parentId}"

internal fun messageListEntries(messages: List<UiMessage>, selecting: Boolean): List<MessageListEntry> = buildList {
    messages.forEachIndexed { index, message ->
        val hasFooter = !selecting && !message.streaming && message.variantCount > 1
        if (message.deletedNavigation) {
            if (hasFooter) add(MessageListEntry(messageBranchKey(message), index, footer = true))
        } else {
            add(MessageListEntry(message.id, index, separateActions = hasFooter))
            if (hasFooter) add(MessageListEntry(messageBranchKey(message), index, footer = true))
        }
    }
}
