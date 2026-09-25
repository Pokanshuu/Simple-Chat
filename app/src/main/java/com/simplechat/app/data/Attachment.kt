package com.simplechat.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一条消息携带的附件。
 *
 * 只有两种：
 * - [Kind.IMAGE]：图片。`data` 是 **base64**（不含 `data:` 前缀），发送时拼成
 *   官方要求的 data URL；本地存的是**已压过的副本**（见 `ImageImport`）。
 * - [Kind.TEXT]：纯文本文件（md / txt 等）。`data` 就是文件原文。
 *
 * ### 为什么图片存 base64 而不是文件路径
 *
 * 路径方案要么在备份里丢图，要么就得把备份改成 zip —— 为一个附件把
 * 「一份 JSON = 一份完整备份」这个约定打破，代价太大。
 * 压到最长边 1600px、JPEG 之后单图通常 100~400KB，
 * 转成 base64 再多三分之一，是可接受的数量级。
 *
 * 图片进 context 前会被服务端缩到约 1300×1300 等效，
 * 所以本地留超过 1600px 的副本没有任何意义（详见官方 Vision 指南）。
 */
@Serializable
data class Attachment(
    val kind: Kind,
    val name: String,
    /** 图片的原始 MIME；文本文件可为空。 */
    val mime: String = "",
    /** 图片：base64；文本：原文。 */
    val data: String,
    /** 仅图片：压缩后的像素尺寸，用于按比例占位（避免加载时高度跳变）。 */
    val width: Int = 0,
    val height: Int = 0,
) {
    enum class Kind { IMAGE, TEXT }

    val isImage: Boolean get() = kind == Kind.IMAGE

    /** 官方要求的形式：`data:image/jpeg;base64,...`。 */
    fun toDataUrl(): String = "data:${mime.ifBlank { "image/jpeg" }};base64,$data"

    /**
     * 拼进**请求文本**的形式（仅文本附件用）。
     *
     * 用显式的定界行而不是直接把内容粘进用户的话里：
     * 模型能分清"这是附件内容、那是我的要求"，否则长文会把它自己的
     * 指令淹没掉。也方便用户事后核对发出去的是什么。
     */
    fun toPromptBlock(): String = buildString {
        appendLine("<<<附件开始：$name>>>")
        append(data)
        if (!data.endsWith("\n")) appendLine()
        appendLine("<<<附件结束：$name>>>")
    }

    /** 后台解析出来的图片字节数，用于"这张图多大"的说明。 */
    val sizeBytes: Int get() = if (isImage) data.length * 3 / 4 else data.toByteArray().size
}

/**
 * 附件列表 ↔ 数据库里那一列 JSON 文本。
 *
 * 存 JSON 而不是再开一张表：附件的生命周期**完全跟随消息**，
 * 没有独立查询需求，单独一张表只会多一次 join、多一处级联要维护。
 */
object Attachments {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(list: List<Attachment>): String? =
        if (list.isEmpty()) null else json.encodeToString(list)

    /** 解析失败一律当"没有附件" —— 一条坏数据不该让整条消息打不开。 */
    fun decode(raw: String?): List<Attachment> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Attachment>>(raw) }.getOrDefault(emptyList())
    }

    /** 人类可读的大小，用于附件 chip 上的副标题。 */
    fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
