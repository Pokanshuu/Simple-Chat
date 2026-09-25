package com.simplechat.app.data

import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.db.MessageEntity
import com.simplechat.app.db.PresetEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 备份专用的 Json 实例。
 *
 * 与网络层的 `ChatApi.defaultJson()` 刻意分开：
 * 网络层要"紧凑 + 未知字段容忍"，备份要"**缩进好读** + 默认值补齐"——
 * 导出的文件是给人看的，一行 JSON 没法读也没法 diff。
 */
internal val backupJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * 备份文件格式。
 *
 * 刻意**扁平**：会话下面直接挂消息数组，而不是两张平行表。
 * 导出文件是给人看、给别的工具读的，嵌套结构比「外键 + 表连接」直观得多。
 * 导入时再把嵌套拆回两张表。
 *
 * 不带 `notification`/`UI` 之类的派生字段 —— 只存数据，不存状态。
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: Long,
    val conversations: List<BackupConversation> = emptyList(),
    val presets: List<BackupPreset> = emptyList(),
) {
    companion object {
        const val FORMAT = "simplechat-backup"

        /**
         * v1：槽位模型（`slotIndex`）。
         * v2：**树**（`parentId`）—— 语义变了，老文件不再兼容。
         */
        const val VERSION = 2
    }
}

@Serializable
data class BackupConversation(
    val id: String,
    val title: String,
    /** CREATIVE | ROLEPLAY */
    val mode: String,
    val provider: String = "DEEPSEEK",
    val model: String = "",
    val systemPrompt: String? = null,
    val pinned: Boolean = false,
    val pinnedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** 挂载的预设 id，按拼接顺序。 */
    val enabledPresetIds: List<String> = emptyList(),
    val messages: List<BackupMessage> = emptyList(),
)

@Serializable
data class BackupMessage(
    val id: String,
    /** 父亲消息 id；空串为根。 */
    val parentId: String = "",
    val variantIndex: Int = 0,
    val active: Boolean = true,
    /** user | assistant */
    val role: String,
    val content: String,
    /**
     * 附件（图片 / 纯文本文件）。
     *
     * 直接复用 [Attachment]：它是 `@Serializable` 的，而备份与数据库里存的是
     * 同一个东西 —— 再定义一套"备份专用"的镜像类，只会多一处要同步的字段。
     *
     * 旧版本的备份没有这个字段，读进来是空列表；新备份被旧版本读到也只是忽略
     * （`ignoreUnknownKeys = true`），所以**不需要**升版本号。
     */
    val attachments: List<Attachment> = emptyList(),
    val reasoning: String? = null,
    val status: String = "DONE",
    val errorMessage: String? = null,
    /** 这条由哪个模型产生；用户消息与老备份为 null。 */
    val model: String? = null,
    val thinkingMs: Long? = null,
    val ttftMs: Long? = null,
    val tps: Float? = null,
    val createdAt: Long,
)

@Serializable
data class BackupPreset(
    val id: String,
    val name: String,
    val content: String,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long = 0L,
)

// ── 实体 ↔ 备份模型 ──────────────────────────────────────

internal fun ConversationEntity.toBackup(
    messages: List<MessageEntity>,
    presetIds: List<String>,
): BackupConversation = BackupConversation(
    id = id,
    title = title,
    mode = mode,
    provider = provider,
    model = model,
    systemPrompt = systemPrompt,
    pinned = pinned,
    pinnedAt = pinnedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    enabledPresetIds = presetIds,
    messages = messages.map { it.toBackup() },
)

internal fun PresetEntity.toBackup(): BackupPreset = BackupPreset(
    id = id,
    name = name,
    content = content,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun MessageEntity.toBackup(): BackupMessage = BackupMessage(
    id = id,
    parentId = parentId,
    variantIndex = variantIndex,
    active = active,
    role = role,
    content = content,
    attachments = Attachments.decode(attachments),
    reasoning = reasoning,
    status = status,
    errorMessage = errorMessage,
    model = model,
    thinkingMs = thinkingMs,
    ttftMs = ttftMs,
    tps = tps,
    createdAt = createdAt,
)

internal fun BackupMessage.toEntity(conversationId: String): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    parentId = parentId,
    variantIndex = variantIndex,
    active = active,
    role = role,
    content = content,
    attachments = Attachments.encode(attachments),
    reasoning = reasoning,
    status = status,
    errorMessage = errorMessage,
    model = model,
    thinkingMs = thinkingMs,
    ttftMs = ttftMs,
    tps = tps,
    createdAt = createdAt,
)

/** 导入策略。 */
enum class ImportMode {
    /** 合并：已存在的 id 跳过，只补新数据。适合多设备汇总。 */
    MERGE,

    /** 覆盖：先清空本机会话，再整体导入。适合换机恢复。 */
    OVERWRITE,
}

/** 导入结果，用于给出「到底导入了什么」的明确反馈。 */
data class ImportResult(
    val conversations: Int,
    val messages: Int,
    val presets: Int,
    val skipped: Int,
)
