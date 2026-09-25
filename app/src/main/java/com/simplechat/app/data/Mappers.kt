package com.simplechat.app.data

import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.db.MessageEntity
import com.simplechat.app.db.PresetEntity
import com.simplechat.app.ui.chat.ConversationMode
import com.simplechat.app.ui.chat.MessageRole
import com.simplechat.app.ui.chat.MessageStatus
import com.simplechat.app.ui.chat.Preset
import com.simplechat.app.ui.chat.ReasoningLevel
import com.simplechat.app.ui.chat.UiMessage

/**
 * 实体 ↔ 领域模型映射。
 *
 * 放在一处，避免散落各处导致字段语义漂移。
 */

fun ConversationEntity.toUiTitle(): String = title

fun ConversationMode.toStorage(): String = name

fun String.toConversationMode(): ConversationMode =
    runCatching { ConversationMode.valueOf(this) }.getOrDefault(ConversationMode.CREATIVE)

fun ReasoningLevel.toStorage(): String = name

fun String.toReasoningLevel(): ReasoningLevel =
    runCatching { ReasoningLevel.valueOf(this) }.getOrDefault(ReasoningLevel.OFF)

fun PresetEntity.toUi(): Preset = Preset(id = id, name = name, content = content)

fun Preset.toEntity(now: Long, order: Int): PresetEntity = PresetEntity(
    id = id,
    name = name,
    content = content,
    sortOrder = order,
    createdAt = now,
    updatedAt = now,
)

fun MessageEntity.toUi(): UiMessage = UiMessage(
    id = id,
    role = if (role == "user") MessageRole.USER else MessageRole.ASSISTANT,
    content = content,
    attachments = Attachments.decode(attachments),
    reasoning = reasoning,
    reasoningSeconds = thinkingMs?.let { it / 1000 },
    status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.DONE),
    errorMessage = errorMessage,
    model = model,
    parentId = parentId,
    variantIndex = variantIndex,
    ttftMs = ttftMs,
    tps = tps,
)

fun MessageStatus.toStorage(): String = name

/** 便捷构造：一条新消息实体。 */
fun newMessageEntity(
    id: String,
    conversationId: String,
    parentId: String,
    variantIndex: Int,
    role: MessageRole,
    content: String,
    attachments: List<Attachment> = emptyList(),
    reasoning: String? = null,
    status: MessageStatus = MessageStatus.DONE,
    active: Boolean = true,
    model: String? = null,
    now: Long = System.currentTimeMillis(),
): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    parentId = parentId,
    variantIndex = variantIndex,
    active = active,
    role = if (role == MessageRole.USER) "user" else "assistant",
    content = content,
    attachments = Attachments.encode(attachments),
    reasoning = reasoning,
    status = status.toStorage(),
    model = model,
    createdAt = now,
)
