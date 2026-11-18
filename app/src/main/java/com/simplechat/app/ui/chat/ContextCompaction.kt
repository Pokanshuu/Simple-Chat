package com.simplechat.app.ui.chat

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.CompactionCheckpoint
import com.simplechat.app.data.TokenEstimate
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

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
    val unreadableFiles = messages.flatMap { it.attachments }
        .filter { it.kind == Attachment.Kind.TEXT && it.data.isBlank() }
        .map { it.name }.distinct()
    if (unreadableFiles.isNotEmpty()) throw AppTextException(
        R.string.compact_attachment_unavailable,
        unreadableFiles.take(5).joinToString("、") + if (unreadableFiles.size > 5) "… (${unreadableFiles.size})" else "",
    )
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
                // A remote file reference is not its contents. Never mark an unread file as summarized.
                if (attachment.data.isBlank()) throw AppTextException(R.string.compact_attachment_unavailable, attachment.name)
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

internal data class CompactionProgress(
    val level: Int,
    val batch: Int,
    val processedChars: Long,
    val totalChars: Long,
    val recovery: CompactionFailureKind? = null,
)

/**
 * Summarize independent chunks, then merge summaries in chronological order.
 * Only accepted responses advance the cursor. Rejections shrink the current chunk, while
 * transport failures retry that chunk. A checkpoint never becomes an active chat summary.
 */
internal suspend fun compactInBatches(
    segments: Sequence<String>,
    prompts: CompactionPrompts,
    contextWindow: Int,
    checkpoint: CompactionCheckpoint = CompactionCheckpoint(),
    onCheckpoint: suspend (CompactionCheckpoint) -> Unit = {},
    onProgress: suspend (CompactionProgress) -> Unit = {},
    pause: suspend (Long) -> Unit = { delay(it) },
    attemptTimeoutMillis: (Int) -> Long = { if (it <= 4_000) 45_000L else 90_000L },
    summarize: suspend (transcript: String, maxTokens: Int, batch: Int) -> String,
): String {
    require(contextWindow > 0)
    var state = checkpoint.copy(requestWindow = minOf(contextWindow, checkpoint.requestWindow))
    val overhead = conservativeCompactionTokens(prompts.system) + 128L
    while (state.level <= 12) {
        currentCoroutineContext().ensureActive()
        val source = if (state.level == 0) segments else state.sources.asSequence().flatMap { sequenceOf(it, "\n\n") }
        val totalChars = source.sumOf { it.length.toLong() }
        require(state.offset <= totalChars) { "Invalid compaction checkpoint offset" }
        val chunks = boundedCompactionChunks(skipCompactionChars(source, state.offset), state.chunkChars).iterator()
        var pending = ""
        while (pending.isNotEmpty() || chunks.hasNext()) {
            // Refill across original chunk boundaries after a provider reduces the batch size.
            if (pending.length < state.chunkChars && chunks.hasNext()) pending += chunks.next()
            run {
                var transientRetries = 0
                var sizeRetries = 0
                val outputCap = if (state.level == 0) 2048 else 4096
                var outputTokens = minOf(outputCap, conservativeCompactionTokens(pending).coerceAtLeast(256).toInt(),
                    state.requestWindow / 8).coerceAtLeast(1)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val inputBudget = state.requestWindow * 3L / 4 - overhead - outputTokens
                    val end = fittingCompactionPrefix(pending, state.chunkChars, inputBudget)
                    if (end <= 0) throw CompactionRequestFailure(CompactionFailureKind.INPUT_LIMIT)
                    val input = pending.substring(0, end)
                    val progress = CompactionProgress(state.level, state.completedBatches + 1, state.offset, totalChars)
                    onProgress(progress)
                    val summary = try {
                        val candidate = withTimeoutOrNull(attemptTimeoutMillis(end)) {
                            summarize(input, outputTokens, progress.batch)
                        }?.trim() ?: throw CompactionRequestFailure(CompactionFailureKind.TIMEOUT)
                        currentCoroutineContext().ensureActive()
                        if (candidate.isEmpty()) throw CompactionRequestFailure(CompactionFailureKind.EMPTY)
                        if (candidate.length > 64_000 || conservativeCompactionTokens(candidate) > state.requestWindow / 3) {
                            throw CompactionRequestFailure(CompactionFailureKind.TRUNCATED)
                        }
                        candidate
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val failure = when (error) {
                            is CompactionRequestFailure -> error
                            is IOException -> CompactionRequestFailure(CompactionFailureKind.TRANSIENT)
                            else -> throw error
                        }
                        onProgress(progress.copy(recovery = failure.kind))
                        when (failure.kind) {
                            CompactionFailureKind.INPUT_LIMIT -> {
                                if (++sizeRetries > 8 || end <= 2) throw failure
                                state = state.copy(
                                    chunkChars = minOf(state.chunkChars, end / 2).coerceAtLeast(2),
                                    requestWindow = minOf(state.requestWindow, failure.inputLimit ?: state.requestWindow),
                                )
                                outputTokens = minOf(outputTokens, state.requestWindow / 8).coerceAtLeast(1)
                                onCheckpoint(state)
                            }
                            CompactionFailureKind.TRUNCATED -> {
                                if (++sizeRetries > 4) throw failure
                                val larger = minOf(outputTokens * 2, 8192, state.requestWindow / 4)
                                if (larger > outputTokens) {
                                    outputTokens = larger
                                } else {
                                    if (end <= 2) throw failure
                                    state = state.copy(chunkChars = minOf(state.chunkChars, end / 2).coerceAtLeast(2))
                                    onCheckpoint(state)
                                }
                            }
                            CompactionFailureKind.TRANSIENT, CompactionFailureKind.EMPTY -> {
                                if (++transientRetries > 2) throw failure
                                pause(600L * transientRetries * transientRetries)
                            }
                            CompactionFailureKind.TIMEOUT, CompactionFailureKind.PERMANENT -> throw failure
                        }
                        continue
                    }
                    state = state.copy(
                        offset = state.offset + end,
                        summaries = state.summaries + summary,
                        completedBatches = state.completedBatches + 1,
                    )
                    onCheckpoint(state)
                    pending = pending.substring(end)
                    break
                }
            }
        }
        if (state.summaries.isEmpty()) throw AppTextException(R.string.error_no_content)
        // Refuse an expanding merge instead of looping forever or truncating source text.
        if ((state.summaries.size > 1 || totalChars > 1024) &&
            state.summaries.sumOf { it.length.toLong() + 2 } >= totalChars) {
            // Keep earlier levels, but regenerate this unusable level on a user retry.
            onCheckpoint(state.copy(offset = 0, summaries = emptyList()))
            throw AppTextException(R.string.compact_no_progress)
        }
        if (state.summaries.size == 1) return state.summaries.single()
        state = state.copy(level = state.level + 1, offset = 0, sources = state.summaries, summaries = emptyList())
        onCheckpoint(state)
    }
    throw AppTextException(R.string.compact_no_progress)
}

/** Planning margin; provider feedback remains authoritative when tokenizers disagree. */
private fun conservativeCompactionTokens(text: String): Long = (TokenEstimate.of(text).toLong() * 3 + 1) / 2

private fun fittingCompactionPrefix(text: String, maxChars: Int, tokenBudget: Long): Int {
    var low = 0
    var high = minOf(text.length, maxChars)
    if (high == text.length && conservativeCompactionTokens(text) <= tokenBudget) return high
    while (low < high) {
        val mid = low + (high - low + 1) / 2
        if (conservativeCompactionTokens(text.substring(0, mid)) <= tokenBudget) low = mid else high = mid - 1
    }
    if (low < text.length && low > 0 && Character.isHighSurrogate(text[low - 1]) && Character.isLowSurrogate(text[low])) low--
    return low
}

private fun skipCompactionChars(segments: Sequence<String>, count: Long): Sequence<String> = sequence {
    var remaining = count
    for (segment in segments) {
        if (remaining >= segment.length) remaining -= segment.length else {
            yield(if (remaining == 0L) segment else segment.substring(remaining.toInt()))
            remaining = 0
        }
    }
}
