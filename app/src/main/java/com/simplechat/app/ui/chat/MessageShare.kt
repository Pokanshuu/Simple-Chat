package com.simplechat.app.ui.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.simplechat.app.data.Attachment
import java.io.Writer

/** The current visible branch, in conversation order. An orphan answer is its own group. */
data class MessageShareGroup(val id: String, val messages: List<UiMessage>) {
    val available: Boolean get() = messages.none { it.streaming } &&
        messages.any { it.content.isNotBlank() || it.attachments.isNotEmpty() }
}

fun messageShareGroups(messages: List<UiMessage>): List<MessageShareGroup> {
    val groups = mutableListOf<MutableList<UiMessage>>()
    messages.forEach { message ->
        if (message.role == MessageRole.USER || groups.isEmpty()) groups += mutableListOf<UiMessage>()
        groups.last() += message
    }
    return groups.map { MessageShareGroup(it.first().id, it.toList()) }
}

@Stable
class MessageShareSelection {
    var active by mutableStateOf(false)
        private set
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    fun begin(messageId: String, groups: List<MessageShareGroup>) {
        val group = groups.firstOrNull { it.available && it.messages.any { message -> message.id == messageId } } ?: return
        selectedIds = setOf(group.id)
        active = true
    }

    fun toggle(group: MessageShareGroup) {
        if (!group.available) return
        selectedIds = if (group.id in selectedIds) selectedIds - group.id else selectedIds + group.id
    }

    fun toggleAll(groups: List<MessageShareGroup>) {
        val available = groups.filter { it.available }.map { it.id }.toSet()
        selectedIds = if (selectedIds.containsAll(available)) emptySet() else available
    }

    fun reconcile(groups: List<MessageShareGroup>) {
        selectedIds = selectedIds.intersect(groups.filter { it.available }.map { it.id }.toSet())
    }

    fun selected(groups: List<MessageShareGroup>): List<MessageShareGroup> =
        groups.filter { it.available && it.id in selectedIds }

    fun close() { active = false; selectedIds = emptySet() }
}

/** A fixed snapshot taken when an export action starts; streaming/branch changes cannot alter it. */
data class MessageShareDocument(
    val title: String,
    val groups: List<MessageShareGroup>,
    val excerptFooter: List<String> = emptyList(),
) {
    val messages: List<UiMessage> get() = groups.flatMap { it.messages }
}

internal enum class ShareImageMode { LONG, PAGES }

/** Long screenshots use a bounded memory budget and fall back to more pages at this height. */
internal fun shareImagePageHeight(mode: ShareImageMode, memoryClassMb: Int): Int =
    if (mode == ShareImageMode.PAGES) 2400
    else (memoryClassMb.toLong() * 1024 * 1024 / 8 / (1080 * 4)).toInt().coerceIn(2400, 12_000)

internal fun writeSharedMarkdown(document: MessageShareDocument, writer: Writer, me: String, assistant: String, image: String) {
    writer.append("# ").append(document.title.replace('\n', ' ').replace('\r', ' ')).append("\n\n")
    document.groups.forEachIndexed { index, group ->
        if (index > 0) writer.append("---\n\n")
        group.messages.forEach { message ->
            writer.append("## ").append(if (message.role == MessageRole.USER) me else assistant).append("\n\n")
            writer.append(message.content).append("\n\n")
            message.attachments.forEach { attachment ->
                val name = attachment.name.replace('\n', ' ').replace('\r', ' ')
                if (attachment.isImage) writer.append("> ").append(image).append(": ").append(name).append("\n\n")
                else {
                    val fence = "`".repeat(maxOf(3, Regex("`+").findAll(attachment.data).maxOfOrNull { it.value.length + 1 } ?: 3))
                    writer.append("### ").append(name).append("\n\n").append(fence).append("text\n")
                        .append(attachment.data).append('\n').append(fence).append("\n\n")
                }
            }
        }
    }
}

internal fun sharedPlainText(document: MessageShareDocument, me: String, assistant: String, image: String): String = buildString {
    appendLine(document.title)
    document.messages.forEach { message ->
        appendLine().appendLine(if (message.role == MessageRole.USER) me else assistant)
        appendLine(message.content)
        message.attachments.forEach { attachment ->
            appendLine(if (attachment.kind == Attachment.Kind.IMAGE) "$image: ${attachment.name}" else "${attachment.name}\n${attachment.data}")
        }
    }
}
