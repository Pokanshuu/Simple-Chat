package com.simplechat.app.ui.chat

import com.simplechat.app.data.Attachment
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.net.ReplyBlock

/**
 * 上下文进度条的语义分段。
 *
 * 前五项是实际占用；后三项是展示用的预留 / 可用空间。
 * [ContextStats.usedTokens] 永远只累加实际占用项，因此预留段不会污染圆环或使用率。
 */
enum class ContextSegmentKind {
    PRESETS,
    COMPACTED_HISTORY,
    MESSAGES,
    ATTACHMENTS,
    TOOL_CALLS,
    MEMORY_RESERVE,
    AUTO_COMPACT_RESERVE,
    FREE_SPACE,
}

data class ContextSegment(
    val kind: ContextSegmentKind,
    val tokens: Int,
) {
    val isUsed: Boolean
        get() = kind in UsedContextSegmentKinds
}

internal val UsedContextSegmentKinds = setOf(
    ContextSegmentKind.PRESETS,
    ContextSegmentKind.COMPACTED_HISTORY,
    ContextSegmentKind.MESSAGES,
    ContextSegmentKind.ATTACHMENTS,
    ContextSegmentKind.TOOL_CALLS,
)

/** 上下文进入警告 / 危险态的已用比例。警告提前到 60%，给自动压缩留出反应时间。 */
const val ContextWarningRatio = 0.6f
const val ContextDangerRatio = 0.9f

/** 按实际请求顺序组装已占用类别；零值类别不占图例和进度条。 */
internal fun usedContextSegments(
    presetTokens: Int,
    compactedTokens: Int,
    messageTokens: Int,
    attachmentTokens: Int,
    toolTokens: Int,
): List<ContextSegment> = listOf(
    ContextSegmentKind.PRESETS to presetTokens,
    ContextSegmentKind.COMPACTED_HISTORY to compactedTokens,
    ContextSegmentKind.MESSAGES to messageTokens,
    ContextSegmentKind.ATTACHMENTS to attachmentTokens,
    ContextSegmentKind.TOOL_CALLS to toolTokens,
).mapNotNull { (kind, tokens) ->
    ContextSegment(kind, tokens).takeIf { tokens > 0 }
}

/**
 * 只估算真正会作为历史回传的工具块。
 *
 * 服务端搜索过程与搜索结果不会回传，只有带签名的 `read_link` 调用 / 结果会进入下一轮
 * 请求（见 `AnthropicRequestBuilder.assistantContent`）。把不可回传的块算进去会让用户
 * 看到一个无论怎么压缩都降不下来的“工具调用”分段。
 */
internal fun outgoingToolHistoryTokens(
    messages: List<UiMessage>,
    allowedModel: String?,
): Int = messages.sumOf { message ->
    if (message.role != MessageRole.ASSISTANT || message.timeline.isEmpty()) return@sumOf 0
    if (allowedModel != null && message.model != null && message.model != allowedModel) return@sumOf 0

    val unsigned = message.timeline
        .filterIsInstance<ReplyBlock.FetchResult>()
        .filter {
            it.kind == ReadLinkKind &&
                !it.failed &&
                it.signature.isNullOrBlank()
        }
        .mapTo(mutableSetOf()) { it.toolUseId }

    message.timeline.sumOf { block ->
        when (block) {
            is ReplyBlock.ToolUse -> {
                if (block.kind != ReadLinkKind || block.id in unsigned) 0 else {
                    16 + TokenEstimate.of(
                        buildString {
                            append(block.name).append('\n')
                            append(block.kind).append('\n')
                            append(block.action).append('\n')
                            append(block.url)
                        },
                    )
                }
            }

            is ReplyBlock.FetchResult -> {
                val sent = block.kind == ReadLinkKind &&
                    block.toolUseId !in unsigned &&
                    !block.failed &&
                    !block.signature.isNullOrBlank()
                if (!sent) 0 else {
                    32 + TokenEstimate.of(
                        buildString {
                            append(block.toolUseId).append('\n')
                            append(block.kind).append('\n')
                            append(block.url).append('\n')
                            append(block.title.orEmpty()).append('\n')
                            append(block.publishedAt.orEmpty()).append('\n')
                            append(block.snippet.orEmpty()).append('\n')
                            append(block.siteName.orEmpty())
                        },
                    )
                }
            }

            else -> 0
        }
    }
}

/** Attachments retained on this device. Remote-only file references are context, not local content. */
internal fun locallyStoredAttachmentTokens(messages: List<UiMessage>): Int =
    attachmentTokenSummary(
        messages.flatMap { message ->
            message.attachments.filter { attachment ->
                attachment.isAvailableLocally
            }
        },
    ).tokens

private val Attachment.isAvailableLocally: Boolean
    get() = fileId.isNullOrBlank() || data.isNotBlank()

internal fun autoCompactThresholdTokens(windowTokens: Int, reserveTokens: Int): Int =
    (windowTokens - reserveTokens).coerceAtLeast(0)

internal fun autoCompactRemainingTokens(usedTokens: Int, thresholdTokens: Int): Int =
    (thresholdTokens - usedTokens).coerceAtLeast(0)

