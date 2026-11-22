package com.simplechat.app.ui.chat

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.CompactionCheckpoint
import com.simplechat.app.data.TokenEstimate
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompactionCostTest {
    private val prompts = CompactionPrompts("summarize", "old", "new", "user", "assistant", "image", "unreadable attachment")
    private val history = "abcd".repeat(228_000)

    @Test fun `228k tokens and 228k characters each fit a single large window request`() = runBlocking {
        assertEquals(228_000, TokenEstimate.of(history))
        for (source in listOf(history, "中".repeat(228_000))) {
            var calls = 0
            compactInBatches(sequenceOf(source), prompts, 256_000) { input, _, _ ->
                calls++
                assertEquals(source, input)
                "summary"
            }
            assertEquals(1, calls)
        }
    }

    @Test fun `ambiguous failures never replay a 228k token request`() = runBlocking {
        for (kind in listOf(CompactionFailureKind.TIMEOUT, CompactionFailureKind.TRANSIENT,
            CompactionFailureKind.EMPTY, CompactionFailureKind.TRUNCATED)) {
            var calls = 0
            try {
                compactInBatches(sequenceOf(history), prompts, 1_000_000) { input, _, _ ->
                    calls++
                    assertEquals(history, input)
                    throw CompactionRequestFailure(kind)
                }
                fail("Expected a stop")
            } catch (e: CompactionRequestFailure) { assertEquals(kind, e.kind) }
            assertEquals(1, calls)
        }
    }

    @Test fun `truncation on a resumed batch stops repeated paid attempts at the ceiling`() = runBlocking {
        val offset = 198_358L
        val source = "x".repeat(396_717)
        var saved = CompactionCheckpoint(offset = offset, summaries = listOf("saved"), requestWindow = 1_000_000)
        val budgets = mutableListOf<Int>()
        repeat(3) { run ->
            try {
                compactInBatches(sequenceOf(source), prompts, 1_000_000, saved, onCheckpoint = { saved = it }) { input, output, _ ->
                    budgets += output
                    assertEquals(source.substring(offset.toInt()), input)
                    throw CompactionRequestFailure(CompactionFailureKind.TRUNCATED)
                }
                fail("Expected a stop")
            } catch (e: CompactionRequestFailure) { assertEquals(0, run) }
              catch (e: AppTextException) { assertEquals(R.string.compact_output_exhausted, e.text.resId) }
            assertEquals(offset, saved.offset)
            assertEquals(listOf("saved"), saved.summaries)
        }
        assertEquals(listOf(4096, 8192), budgets)
    }

    @Test fun `official 1M route sends 228k tokens once with Harness output budget`() = runBlocking {
        var calls = 0
        val summary = compactInBatches(sequenceOf(history), prompts, 1_000_000, harnessBudget = true) { input, maxTokens, _ ->
            calls++
            assertEquals(history, input)
            assertEquals(65_536, maxTokens)
            "complete"
        }
        assertEquals(1, calls)
        assertEquals("complete", summary)
    }

    @Test fun `request cap includes a rejected batch and resume never replays accepted text`() = runBlocking {
        var checkpoint = CompactionCheckpoint()
        var calls = 0
        val accepted = StringBuilder()
        val inputs = sequenceOf(history)
        try {
            compactInBatches(inputs, prompts, 128_000, onCheckpoint = { checkpoint = it }) { input, _, _ ->
                if (++calls == 1) throw CompactionRequestFailure(CompactionFailureKind.INPUT_LIMIT, inputLimit = 32_768)
                accepted.append(input)
                "summary-$calls"
            }
            fail("Expected a pause")
        } catch (e: AppTextException) { assertEquals(R.string.compact_request_cap, e.text.resId) }
        assertEquals(8, calls)
        assertEquals(accepted.length.toLong(), checkpoint.offset)
        val before = calls
        val result = compactInBatches(inputs, prompts, 128_000, checkpoint) { input, _, _ ->
            accepted.append(input)
            "summary-${++calls}"
        }
        assertTrue(calls > before)
        assertEquals(history, accepted.toString())
        assertEquals((2..calls).joinToString("\n\n") { "summary-$it" }, result)
    }

    @Test fun `total deadline cancels an in-flight request and preserves completed work`() = runBlocking {
        var calls = 0
        var cancelled = false
        var checkpoint = CompactionCheckpoint(chunkChars = 1000)
        try {
            compactInBatches(sequenceOf(history), prompts, 1_000_000, checkpoint,
                onCheckpoint = { checkpoint = it }, deadlineMillis = 200) { _, _, _ ->
                if (++calls == 1) "saved" else try { awaitCancellation() } finally { cancelled = true }
            }
            fail("Expected deadline")
        } catch (e: AppTextException) { assertEquals(R.string.compact_deadline, e.text.resId) }
        assertTrue(cancelled)
        assertEquals(2, calls)
        assertEquals(1000L, checkpoint.offset)
        assertEquals(listOf("saved"), checkpoint.summaries)
    }
}
