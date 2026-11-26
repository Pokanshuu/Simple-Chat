package com.simplechat.app.ui.chat

import com.simplechat.app.data.Attachment
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.net.ImageInput
import com.simplechat.app.net.MessageContent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Request-only attachment, so the same generated image is not resent on unrelated turns. */
internal fun UiMessage.withVisualFollowUp(latestUserId: String?, image: Attachment?): UiMessage =
    if (image != null && role == MessageRole.USER && id == latestUserId && attachments.none { it.isImage }) {
        copy(attachments = attachments + image)
    } else this

/** Only explicit image references may look past the immediately preceding message. */
internal fun recentGeneratedImage(path: List<UiMessage>, latestUserId: String, explicitReference: Boolean): UiMessage? {
    val userIndex = path.indexOfLast { it.id == latestUserId && it.role == MessageRole.USER }
    if (userIndex <= 0) return null
    val count = if (explicitReference) 6 else 1
    return path.subList((userIndex - count).coerceAtLeast(0), userIndex).asReversed().firstOrNull {
        it.role == MessageRole.ASSISTANT && it.status == MessageStatus.DONE &&
            it.imageGeneration && it.attachments.any { attachment -> attachment.isImage }
    }
}

/**
 * 组装一条 **user** 消息的 `content`。
 *
 * 三种形态，按"能简单就简单"排：
 *
 * | 附件 | content |
 * |---|---|
 * | 无 | 纯字符串 |
 * | 只有文本文件 | 纯字符串（附件内容按定界块拼进去） |
 * | 有图片 | content block 数组 |
 *
 * ### 为什么图片必须走数组
 *
 * 官方 Vision 指南明确：图片只能出现在 **user** 消息里，且必须写成
 * `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}`。
 * 放进 system / assistant 会直接 400。
 *
 * ### 图片会随每一轮请求重发
 *
 * 接口是无状态的，历史里的图片每次都要重新带上（这也是把它存进数据库、
 * 而不是用完即弃的原因）。token 账要按"每轮都算"来看。
 *
 * 抽成纯函数是为了能单测：这里错一处，表现是"模型对图片视而不见"
 * 或者"请求直接 400"，从界面上完全看不出是哪一环。
 */
fun userContent(text: String, attachments: List<Attachment>): JsonElement {
    if (attachments.isEmpty()) return MessageContent.text(text)

    val textFiles = attachments.filter { it.kind == Attachment.Kind.TEXT && it.fileId == null }
    val files = attachments.filter { !it.fileId.isNullOrBlank() }
    // 只发有内容的图片：落盘附件的 data 要靠 `prepareRequestAttachments` 读回，
    // 万一漏了那一步，空 base64 发上去只会 400（见 AnthropicRequestBuilder 的同一条注释）
    val images = attachments.filter { it.isImage && it.data.isNotBlank() }

    val body = buildString {
        append(text)
        textFiles.forEach { file ->
            if (isNotEmpty()) appendLine()
            appendLine()
            append(file.toPromptBlock())
        }
    }.trim()

    if (files.isNotEmpty()) return JsonArray(buildList {
        if (body.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", body) })
        files.forEach { file -> add(buildJsonObject { put("type", "file"); put("file_id", file.fileId) }) }
        if (images.isNotEmpty()) addAll(MessageContent.withImages("", images.map { ImageInput(it.toDataUrl(), ImageDetail) }) as JsonArray)
    })
    if (images.isEmpty()) return MessageContent.text(body)

    return MessageContent.withImages(
        text = body,
        images = images.map { ImageInput(it.toDataUrl(), detail = ImageDetail) },
    )
}

/**
 * 图片精度。
 *
 * 官方给了四档，这里固定用 `high`（保留原图，服务端再统一缩到约 1300×1300 等效）。
 *
 * 不用 `low` 的原因很实际：`low` 会把图**缩到 512×512**，
 * 而本项目最常见的图片用法就是"截图里有一段文字，帮我看看" ——
 * 512×512 下小字直接糊成一团。
 * 代价可控：每张图上限 1024 token，且只有带图的会话才会付这个成本。
 */
const val ImageDetail = "high"

internal data class AttachmentTokenSummary(
    val tokens: Int,
    val unknownCount: Int,
)

/**
 * 附件 token 的**唯一估算入口**。上下文圆环、附件分类、消息标签和压缩切点都必须走这里。
 *
 * 兜底顺序：本地正文 → 原文字符数 → 纯文本字节数 → 未知。
 * 二进制文档的字节数与服务端解析结果没有可靠换算，因此只增加 [unknownCount]，
 * 不编造 token 数；有原文字符数时仍可按字符量级估算。
 */
internal fun attachmentTokenSummary(attachments: List<Attachment>): AttachmentTokenSummary {
    var tokens = 0L
    var unknown = 0
    attachments.forEach { attachment ->
        val estimate = when {
            attachment.isImage || attachment.remoteImage -> ImageTokenUpperBound.toLong()
            attachment.data.isNotBlank() -> TokenEstimate.of(attachment.data).toLong()
            attachment.originalTextChars > 0 -> estimateTextChars(attachment.originalTextChars)
            attachment.originalBytes > 0 && attachment.isPlainTextLike() -> estimateTextBytes(attachment.originalBytes)
            attachment.fileId != null -> null
            else -> 0L
        }
        if (estimate == null) unknown++ else tokens += estimate
    }
    return AttachmentTokenSummary(
        tokens = tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        unknownCount = unknown,
    )
}

fun attachmentTokenEstimate(attachments: List<Attachment>): Int =
    attachmentTokenSummary(attachments).tokens

/** File blocks carry only the ID; the referenced content still consumes input tokens. */
internal fun referencedAttachmentTokenEstimate(attachments: List<Attachment>): Int =
    attachmentTokenSummary(attachments.filter { it.fileId != null }).tokens

/**
 * 这条历史消息值不值得发出去（OpenAI 兼容通道）。
 *
 * 历史里确实可能出现"什么都没有"的消息：回复出错 / 被中断后正文与思考都为空、
 * 或者一条既没正文也没附件的空 user。DeepSeek 官方对 `content: ""` 比较宽松，
 * 但不少兼容网关会直接 400（`content must not be empty`）——
 * 一条空消息让整段历史发不出去，代价远大于少发一条。
 *
 * 纯函数，有单测（`RequestContentTest`）。
 */
internal fun isSendableOutgoing(message: UiMessage): Boolean =
    message.content.isNotBlank() ||
        message.attachments.any { attachment ->
            when {
                !attachment.fileId.isNullOrBlank() -> true
                attachment.kind == Attachment.Kind.TEXT -> attachment.data.isNotBlank()
                attachment.isImage -> attachment.data.isNotBlank()
                else -> false
            }
        }

/** 官方 Vision 指南给出的单图 token 上限。 */
const val ImageTokenUpperBound = 1024

private fun estimateTextChars(chars: Int): Long =
    ((chars + 3L) / 4L).coerceAtLeast(1L)

private fun estimateTextBytes(bytes: Long): Long =
    ((bytes + 3L) / 4L).coerceAtLeast(1L)

private val PlainTextMimes = setOf(
    "application/json",
    "application/xml",
    "application/yaml",
    "application/x-yaml",
    "application/javascript",
    "application/x-javascript",
    "application/sql",
)

private val PlainTextExtensions = setOf(
    "txt", "text", "md", "markdown", "json", "jsonl", "ndjson", "csv", "tsv",
    "log", "xml", "yaml", "yml", "toml", "ini", "properties", "conf",
    "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
    "html", "htm", "css", "scss", "less", "sh", "bash", "zsh", "ps1",
    "sql", "c", "cc", "cpp", "h", "hpp", "rs", "go", "rb", "php",
    "swift", "r", "lua", "tex", "ipynb",
)

private fun Attachment.isPlainTextLike(): Boolean {
    val normalizedMime = mime.substringBefore(';').trim().lowercase()
    if (normalizedMime.startsWith("text/") || normalizedMime in PlainTextMimes) return true
    return name.substringAfterLast('.', "").lowercase() in PlainTextExtensions
}
