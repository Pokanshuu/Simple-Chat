package com.simplechat.app.ui.chat

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.CompactionCheckpoint
import com.simplechat.app.data.TokenEstimate
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Reserve room for the summary while keeping recent turns verbatim. */
internal fun compactionRecentTokenBudget(modelWindow: Int, requestWindow: Int?): Int =
    minOf(modelWindow, requestWindow ?: modelWindow).div(8).coerceIn(1, 32_000)

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
    val unreadableAttachment: String,
)

internal enum class CompactionStyle { GENERAL, CREATIVE, TECHNICAL }

private val creativeCues = listOf(
    "角色扮演", "跑团", "小说", "故事", "剧情", "角色", "人物", "世界观", "伏笔", "创作", "续写角色",
    "roleplay", "role-play", "fiction", "character", "worldbuilding", "plot thread", "write a novel", "continue the story",
)
private val technicalCues = listOf(
    "代码", "编程", "程序", "报错", "异常", "接口", "api", "bug", "debug", "stack trace", "compile", "build", "test",
    "repository", "commit", "pull request", "数据库", "架构", "函数", "class ", "import ", "gradle", "kotlin", "python",
)

/** Classify from user-authored context, ignoring code fences and quoted assistant output. */
internal fun classifyCompaction(messages: List<UiMessage>, previousSummary: String? = null): CompactionStyle {
    val source = buildString {
        messages.asSequence().filter { it.role == MessageRole.USER }.forEach { appendLine(it.content) }
        if (!previousSummary.isNullOrBlank()) appendLine(previousSummary)
    }.let { text ->
        text.replace(Regex("```[\\s\\S]*?```|~~~[\\s\\S]*?~~~"), " ")
            .replace(Regex("(?m)^>.*$"), " ").lowercase()
    }
    val creative = creativeCues.count(source::contains)
    val technical = technicalCues.count(source::contains)
    return when {
        creative >= 2 && creative > technical -> CompactionStyle.CREATIVE
        technical >= 2 && technical > creative -> CompactionStyle.TECHNICAL
        else -> CompactionStyle.GENERAL
    }
}

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
        var skippedAttachment = false
        for (attachment in message.attachments) {
            if (attachment.kind == Attachment.Kind.TEXT) {
                // A remote file ID is not its contents; keep the message without inventing the document.
                if (attachment.data.isBlank()) {
                    skippedAttachment = true
                    continue
                }
                yield("\n<<<附件开始：${attachment.name}>>>\n")
                yield(attachment.data)
                yield("\n<<<附件结束：${attachment.name}>>>\n")
            }
        }
        if (skippedAttachment) yield("\n${prompts.unreadableAttachment}\n")
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
    val attempt: Int,
    val inputChars: Int,
    val effectiveWindow: Int,
    val outputTokens: Int,
    val isWholeSource: Boolean = level == 0 && processedChars == 0L && inputChars.toLong() == totalChars,
    val recovery: CompactionFailureKind? = null,
)

internal const val CompactionRequestLimit = 8
private const val CompactionDeadlineMillis = 180_000L

/** Each explicit invocation gets one budget shared by source, merge and rejected requests. */
internal suspend fun compactInBatches(
    segments: Sequence<String>,
    prompts: CompactionPrompts,
    contextWindow: Int,
    checkpoint: CompactionCheckpoint = CompactionCheckpoint(),
    onCheckpoint: suspend (CompactionCheckpoint) -> Unit = {},
    onProgress: suspend (CompactionProgress) -> Unit = {},
    attemptTimeoutMillis: (Int) -> Long = { if (it <= 4_000) 45_000L else 90_000L },
    harnessBudget: Boolean = false,
    deadlineMillis: Long = CompactionDeadlineMillis,
    summarize: suspend (transcript: String, maxTokens: Int, batch: Int) -> String,
): String = withTimeoutOrNull(deadlineMillis) {
    require(contextWindow > 0)
    if (checkpoint.outputExhausted && !(harnessBudget && checkpoint.requestWindow >= 524_288 && !checkpoint.triedHarnessBudget)) throw AppTextException(R.string.compact_output_exhausted)
    // Older checkpoints also halved the chunk after a timeout or truncated response. Keep
    // their accepted summaries, but only carry forward a size limit backed by a rejection.
    val legacySize = !checkpoint.inputSizeRejected && checkpoint.requestWindow == contextWindow
    var state = checkpoint.copy(
        requestWindow = minOf(contextWindow, checkpoint.requestWindow),
        chunkChars = if (legacySize) com.simplechat.app.data.MaxCompactionChunkChars else checkpoint.chunkChars,
    )
    val overhead = TokenEstimate.of(prompts.system) + 256L
    var attempts = 0
    while (state.level <= 12) {
        currentCoroutineContext().ensureActive()
        val source = if (state.level == 0) segments else state.sources.asSequence().flatMap { sequenceOf(it, "\n\n") }
        val totalChars = source.sumOf { it.length.toLong() }
        require(state.offset <= totalChars) { "Invalid compaction checkpoint offset" }
        // Buffer only enough characters for one window even when the model is small.
        val bufferSize = minOf(state.chunkChars.toLong(), (state.requestWindow * 4L).coerceAtLeast(2)).toInt()
        val chunks = boundedCompactionChunks(skipCompactionChars(source, state.offset), bufferSize).iterator()
        var pending = ""
        var sizeRejections = 0
        while (pending.isNotEmpty() || chunks.hasNext()) {
            currentCoroutineContext().ensureActive()
            if (attempts >= CompactionRequestLimit) throw AppTextException(R.string.compact_request_cap, CompactionRequestLimit)
            if (pending.length < minOf(bufferSize, state.chunkChars) && chunks.hasNext()) pending += chunks.next()
            val outputTokens = if (harnessBudget && state.requestWindow >= 524_288) {
                // DeepSeek Harness compaction-basic reserves 65,536 output tokens on its large route.
                65_536
            } else if (state.moreOutput) minOf(8192, state.requestWindow / 8).coerceAtLeast(1)
                else minOf(4096, TokenEstimate.of(pending).coerceAtLeast(256), state.requestWindow / 8).coerceAtLeast(1)
            val inputBudget = state.requestWindow * 95L / 100 - overhead - outputTokens
            val end = fittingCompactionPrefix(pending, state.chunkChars, inputBudget)
            if (end <= 0) throw CompactionRequestFailure(CompactionFailureKind.INPUT_LIMIT)
            val input = pending.substring(0, end)
            val progress = CompactionProgress(state.level, state.summaries.size + 1, state.offset, totalChars, ++attempts, end, state.requestWindow, outputTokens)
            onProgress(progress)
            val summary = try {
                val candidate = withTimeoutOrNull(if (outputTokens == 65_536) deadlineMillis else attemptTimeoutMillis(end)) {
                    summarize(input, outputTokens, progress.batch)
                }?.trim() ?: throw CompactionRequestFailure(CompactionFailureKind.TIMEOUT)
                currentCoroutineContext().ensureActive()
                if (candidate.isEmpty()) throw CompactionRequestFailure(CompactionFailureKind.EMPTY)
                if (candidate.length > com.simplechat.app.data.MaxCompactionSummaryChars || TokenEstimate.of(candidate) > state.requestWindow / 3) {
                    throw CompactionRequestFailure(CompactionFailureKind.TRUNCATED)
                }
                candidate
            } catch (failure: CompactionRequestFailure) {
                // A timeout or a partial response may already be billed. Only an explicit input
                // rejection justifies using smaller requests.
                if (failure.kind == CompactionFailureKind.INPUT_LIMIT && end > 2) {
                    state = state.copy(inputSizeRejected = true, chunkChars = minOf(state.chunkChars, end / 2).coerceAtLeast(2),
                        requestWindow = minOf(state.requestWindow, failure.inputLimit ?: state.requestWindow))
                    onCheckpoint(state)
                }
                if (failure.kind == CompactionFailureKind.TRUNCATED) {
                    val canIncrease = outputTokens < minOf(8192, state.requestWindow / 8)
                    state = state.copy(moreOutput = canIncrease, outputExhausted = !canIncrease, triedHarnessBudget = outputTokens == 65_536)
                    onCheckpoint(state)
                }
                onProgress(progress.copy(recovery = failure.kind))
                if (state.outputExhausted) throw AppTextException(R.string.compact_output_exhausted)
                if (failure.kind != CompactionFailureKind.INPUT_LIMIT || end <= 2 || ++sizeRejections > 2) throw failure
                continue
            } catch (_: IOException) {
                throw CompactionRequestFailure(CompactionFailureKind.TRANSIENT)
            }
            sizeRejections = 0
            state = state.copy(outputExhausted = false, moreOutput = false, offset = state.offset + end, summaries = state.summaries + summary,
                completedBatches = state.completedBatches + 1)
            onCheckpoint(state)
            pending = pending.substring(end)
        }
        if (state.summaries.isEmpty()) throw AppTextException(R.string.error_no_content)
        // Refuse an expanding merge instead of looping forever or truncating source text.
        if ((state.summaries.size > 1 || totalChars > 1024) &&
            state.summaries.sumOf { it.length.toLong() + 2 } >= totalChars) {
            // Keep earlier levels, but regenerate this unusable level on a user retry.
            onCheckpoint(state.copy(offset = 0, summaries = emptyList()))
            throw AppTextException(R.string.compact_no_progress)
        }
        if (state.summaries.size == 1) return@withTimeoutOrNull state.summaries.single()
        // Short, complete recaps already fit the final summary budget. Keep their chronological
        // order locally rather than paying for another generation just to join them.
        if (state.summaries.sumOf { it.length.toLong() + 2 } <= 64_000) {
            val combined = state.summaries.joinToString("\n\n")
            if (TokenEstimate.of(combined) <= minOf(4096, state.requestWindow / 8)) return@withTimeoutOrNull combined
        }
        state = state.copy(level = state.level + 1, offset = 0, sources = state.summaries, summaries = emptyList())
        onCheckpoint(state)
    }
    throw AppTextException(R.string.compact_no_progress)
} ?: throw AppTextException(R.string.compact_deadline, deadlineMillis / 1000)

private fun fittingCompactionPrefix(text: String, maxChars: Int, tokenBudget: Long): Int {
    var low = 0
    var high = minOf(text.length, maxChars)
    if (high == text.length && TokenEstimate.of(text) <= tokenBudget) return high
    while (low < high) {
        val mid = low + (high - low + 1) / 2
        if (TokenEstimate.of(text.substring(0, mid)) <= tokenBudget) low = mid else high = mid - 1
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
