package com.simplechat.app.ui.chat

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.TokenEstimate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep six messages when practical, but allow recovery even when a few messages fill the window. */
internal fun compactionCutIndex(messages: List<UiMessage>, recentTokenBudget: Int): Int {
    var cut = (messages.size - 6).coerceAtLeast(0)
    while (cut > 0 && messages[cut].role != MessageRole.USER) cut--
    val costs = messages.map { message ->
        TokenEstimate.of(message.content).toLong() + 8L +
            message.attachments.sumOf {
                if (it.isImage) 1024L else TokenEstimate.of(it.data).toLong()
            }
    }
    var recentTokens = costs.drop(cut).sum()
    while (recentTokens > recentTokenBudget) {
        val next = (cut + 1 until messages.size).firstOrNull { messages[it].role == MessageRole.USER } ?: break
        for (index in cut until next) recentTokens -= costs[index]
        cut = next
    }
    return cut
}

internal data class CompactionPrompts(
    val system: String,
    val existing: String,
    val newMessages: String,
    val user: String,
    val assistant: String,
    val image: String,
)

/** Yield original text in order; never concatenate the whole history or a huge attachment. */
internal fun compactionSegments(
    messages: List<UiMessage>,
    previousSummary: String?,
    prompts: CompactionPrompts,
): Sequence<String> = sequence {
    if (!previousSummary.isNullOrBlank()) {
        yield(prompts.existing)
        yield(previousSummary)
        yield(prompts.newMessages)
    }
    for (message in messages) {
        yield(if (message.role == MessageRole.USER) prompts.user else prompts.assistant)
        yield(message.content)
        for (attachment in message.attachments) {
            if (attachment.kind == Attachment.Kind.TEXT) {
                yield("\n<<<附件开始：${attachment.name}>>>\n")
                yield(attachment.data)
                yield("\n<<<附件结束：${attachment.name}>>>\n")
            }
        }
        if (message.attachments.any { it.isImage }) yield(prompts.image)
        yield("\n\n")
    }
}

/** Bounded chunks also split single enormous messages, without dropping text or splitting emoji. */
internal fun boundedCompactionChunks(segments: Sequence<String>, maxChars: Int): Sequence<String> = sequence {
    require(maxChars >= 2)
    val buffer = StringBuilder()
    for (segment in segments) {
        var start = 0
        while (start < segment.length) {
            var end = start + minOf(maxChars - buffer.length, segment.length - start)
            if (end < segment.length && end > start &&
                Character.isHighSurrogate(segment[end - 1]) && Character.isLowSurrogate(segment[end])) end--
            if (end == start) {
                yield(buffer.toString())
                buffer.setLength(0)
                continue
            }
            buffer.append(segment, start, end)
            start = end
            if (buffer.length == maxChars) {
                yield(buffer.toString())
                buffer.setLength(0)
            }
        }
    }
    if (buffer.isNotEmpty()) yield(buffer.toString())
}

/** Return only a complete rolling summary. Errors/cancellation leave the saved summary untouched. */
internal suspend fun compactInBatches(
    segments: Sequence<String>,
    prompts: CompactionPrompts,
    contextWindow: Int,
    summarize: suspend (transcript: String, maxTokens: Int, batch: Int) -> String,
): String {
    val outputTokens = minOf(4096, contextWindow / 8).coerceAtLeast(1)
    // Leave room for the rolling summary, system prompt and reply; cap gateway payload sizes too.
    val overhead = TokenEstimate.ofAll(prompts.system, prompts.existing, prompts.newMessages) + 64
    val chunkChars = minOf(48_000, contextWindow / 3, contextWindow - outputTokens * 2 - overhead)
    if (chunkChars < 2) throw AppTextException(R.string.compact_summary_too_large)
    var summary = ""
    var batch = 0
    for (chunk in boundedCompactionChunks(segments, chunkChars)) {
        currentCoroutineContext().ensureActive()
        val transcript = buildString {
            if (summary.isNotBlank()) {
                append(prompts.existing).append(summary).append(prompts.newMessages)
            }
            append(chunk)
        }
        summary = summarize(transcript, outputTokens, ++batch).trim()
        currentCoroutineContext().ensureActive()
        if (summary.isEmpty()) throw AppTextException(R.string.error_no_content)
        if (TokenEstimate.of(summary) > contextWindow / 3) {
            throw AppTextException(R.string.compact_summary_too_large)
        }
    }
    if (summary.isEmpty()) throw AppTextException(R.string.error_no_content)
    return summary
}
