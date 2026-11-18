package com.simplechat.app.ui.chat

import com.simplechat.app.R
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.providerHintRes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile

internal enum class CompactionFailureKind { INPUT_LIMIT, TRANSIENT, TIMEOUT, TRUNCATED, EMPTY, PERMANENT }

internal class CompactionRequestFailure(
    val kind: CompactionFailureKind,
    val event: StreamEvent.Error? = null,
    val inputLimit: Int? = null,
) : Exception(kind.name)

/** A rejection can describe a per-request limit; it must never rewrite model capabilities. */
internal fun compactionFailure(event: StreamEvent.Error): CompactionRequestFailure {
    val raw = event.rawMessage ?: event.message
    val hint = providerHintRes(raw)
    val kind = when {
        event.code in listOf(401, 402, 403, 404) || hint in listOf(
            R.string.error_provider_auth, R.string.error_provider_quota,
            R.string.error_provider_model_not_found, R.string.error_provider_no_channel,
        ) -> CompactionFailureKind.PERMANENT
        hint in listOf(R.string.error_provider_context_length, R.string.error_http_too_large) ||
            event.code == 413 -> CompactionFailureKind.INPUT_LIMIT
        event.retryable || event.code == 429 || event.code in 500..599 ||
            hint == R.string.error_provider_rate_limit ||
            raw.contains("overloaded", ignoreCase = true) -> CompactionFailureKind.TRANSIENT
        else -> CompactionFailureKind.PERMANENT
    }
    val limit = if (kind == CompactionFailureKind.INPUT_LIMIT) {
        sequenceOf(
            Regex("(?:max(?:imum)?(?: context)?(?: length)?(?: is)?|limit(?: is)?)[: =]*([0-9][0-9,]*)\\s*tokens", RegexOption.IGNORE_CASE),
            Regex("([0-9][0-9,]*)\\s*(?:token)?\\s*maximum", RegexOption.IGNORE_CASE),
        ).mapNotNull { it.find(raw)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
            .firstOrNull { it > 0 }
    } else null
    return CompactionRequestFailure(kind, event, limit)
}

/** Accept only complete text. Partial output from failed attempts is never reusable. */
internal suspend fun collectCompactionSummary(
    stream: Flow<StreamEvent>,
    maxChars: Int = 64_000,
    onFinish: (reason: String, textChars: Int, thinkingChars: Int) -> Unit = { _, _, _ -> },
): String {
    val text = StringBuilder()
    var completed = false
    var thinkingChars = 0
    // A finish reason is sufficient for summaries; usage trailers are irrelevant.
    // Returning early also cancels the upstream SSE read if the peer keeps it open.
    stream.takeWhile { event ->
        thinkingChars += when (event) {
            is StreamEvent.Reasoning -> event.text.length
            is StreamEvent.ThinkingDelta -> event.text.length
            else -> 0
        }
        val piece = when (event) {
            is StreamEvent.Content -> event.text
            is StreamEvent.TextDelta -> event.text
            is StreamEvent.BlockStart -> (event.block as? ReplyBlock.Text)?.text
            else -> null
        }
        if (piece != null) {
            if (piece.length > maxChars - text.length) {
                onFinish("client_character_limit", text.length, thinkingChars)
                throw CompactionRequestFailure(CompactionFailureKind.TRUNCATED)
            }
            text.append(piece)
        }
        when (event) {
            is StreamEvent.Error -> throw compactionFailure(event)
            is StreamEvent.Stopped -> {
                val reason = event.reason
                if (reason != null) onFinish(if (reason in setOf("length", "max_tokens", "stop", "end_turn", "stop_sequence")) reason else "other", text.length, thinkingChars)
                when (reason) {
                    "length", "max_tokens" -> throw CompactionRequestFailure(CompactionFailureKind.TRUNCATED)
                    "stop", "end_turn", "stop_sequence" -> completed = true
                    null -> Unit
                    else -> throw CompactionRequestFailure(CompactionFailureKind.PERMANENT)
                }
            }
            StreamEvent.Done -> {
                onFinish("done", text.length, thinkingChars)
                completed = true
            }
            else -> Unit
        }
        !completed
    }.collect {}
    if (!completed) throw CompactionRequestFailure(CompactionFailureKind.TRANSIENT)
    return text.toString().trim().ifEmpty { throw CompactionRequestFailure(CompactionFailureKind.EMPTY) }
}
