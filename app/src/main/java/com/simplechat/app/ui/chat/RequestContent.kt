package com.simplechat.app.ui.chat

import com.simplechat.app.data.Attachment
import com.simplechat.app.net.ImageInput
import com.simplechat.app.net.MessageContent
import kotlinx.serialization.json.JsonElement

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

    val textFiles = attachments.filter { it.kind == Attachment.Kind.TEXT }
    val images = attachments.filter { it.isImage }

    val body = buildString {
        append(text)
        textFiles.forEach { file ->
            if (isNotEmpty()) appendLine()
            appendLine()
            append(file.toPromptBlock())
        }
    }.trim()

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

/**
 * 附件折算的 token 估算，喂给上下文占用圆环。
 *
 * - 文本附件：按正文一样的规则估
 * - 图片：按官方给的**上限 1024/张** 记。实际通常低于这个数，
 *   但占用圆环宁可高估 —— 它要提示的是"快满了"，低估会让人措手不及。
 */
fun attachmentTokenEstimate(attachments: List<Attachment>): Int {
    if (attachments.isEmpty()) return 0
    return attachments.sumOf { attachment ->
        if (attachment.isImage) ImageTokenUpperBound
        else com.simplechat.app.data.TokenEstimate.of(attachment.data)
    }
}

/** 官方 Vision 指南给出的单图 token 上限。 */
const val ImageTokenUpperBound = 1024
