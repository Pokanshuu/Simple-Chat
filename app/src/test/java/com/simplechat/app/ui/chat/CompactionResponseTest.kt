package com.simplechat.app.ui.chat

import com.simplechat.app.data.CompactionCheckpoint
import com.simplechat.app.net.StreamEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompactionResponseTest {
    @Test fun `raw provider diagnostics classify input limit without translated text`() {
        val failure = compactionFailure(StreamEvent.Error("translated and abbreviated", 400,
            rawMessage = "user input too long, 32942 tokens exceeds max 32768 tokens"))
        assertEquals(CompactionFailureKind.INPUT_LIMIT, failure.kind)
        assertEquals(32768, failure.inputLimit)
        assertEquals(32_768, compactionFailure(StreamEvent.Error(
            "maximum context length is 32,768 tokens")).inputLimit)
        assertNull(compactionFailure(StreamEvent.Error("context length exceeded")).inputLimit)
    }

    @Test fun `disconnect after partial text stops and discards partial output`() = runBlocking {
        var calls = 0
        var checkpoint = CompactionCheckpoint()
        try {
            compactInBatches(sequenceOf("source"),
                CompactionPrompts("summarize", "old", "new", "user", "assistant", "image", "unreadable attachment"),
                8192, onCheckpoint = { checkpoint = it }) { _, _, _ ->
                calls++
                collectCompactionSummary(flowOf(StreamEvent.Content("partial text"),
                    StreamEvent.Error("connection interrupted", retryable = true)))
            }
            fail("A broken stream must stop the operation")
        } catch (failure: CompactionRequestFailure) { assertEquals(CompactionFailureKind.TRANSIENT, failure.kind) }
        assertEquals(1, calls)
        assertTrue(checkpoint.summaries.isEmpty())
    }

    @Test fun `both protocols require completed untruncated text`() = runBlocking {
        assertEquals("OpenAI", collectCompactionSummary(flowOf(StreamEvent.Content("OpenAI"), StreamEvent.Done)))
        assertEquals("Anthropic", collectCompactionSummary(flowOf(StreamEvent.TextDelta(0, "Anthropic"), StreamEvent.Stopped("end_turn"))))
        val cases = listOf(
            flowOf(StreamEvent.Content("partial")) to CompactionFailureKind.TRANSIENT,
            flowOf(StreamEvent.Content("cut"), StreamEvent.Stopped("length"), StreamEvent.Done) to CompactionFailureKind.TRUNCATED,
            flowOf(StreamEvent.TextDelta(0, "cut"), StreamEvent.Stopped("max_tokens")) to CompactionFailureKind.TRUNCATED,
            flowOf(StreamEvent.Done) to CompactionFailureKind.EMPTY,
            flowOf(StreamEvent.Content("refusal"), StreamEvent.Stopped("content_filter")) to CompactionFailureKind.PERMANENT,
            flowOf(StreamEvent.Stopped("tool_use"), StreamEvent.Done) to CompactionFailureKind.PERMANENT,
        )
        for ((stream, kind) in cases) {
            try {
                collectCompactionSummary(stream)
                fail("Incomplete response was accepted: $kind")
            } catch (failure: CompactionRequestFailure) { assertEquals(kind, failure.kind) }
        }
    }

    @Test fun `terminal signal does not wait for an idle open stream`() = runBlocking {
        for (stop in listOf(StreamEvent.Stopped("stop"), StreamEvent.Stopped("end_turn"), StreamEvent.Done)) {
            val upstream = flow {
                emit(StreamEvent.Content("finished"))
                emit(stop)
                awaitCancellation()
            }
            assertEquals("finished", withTimeout(2_000) { collectCompactionSummary(upstream) })
        }
    }

    @Test fun `oversized output is rejected before unbounded buffering`() = runBlocking {
        try {
            collectCompactionSummary(flowOf(StreamEvent.Content("x".repeat(101)), StreamEvent.Done), maxChars = 100)
            fail("Oversized output was accepted")
        } catch (failure: CompactionRequestFailure) {
            assertEquals(CompactionFailureKind.TRUNCATED, failure.kind)
        }
    }

    @Test fun `rate limiting overload and permanent failures remain distinct`() {
        for (event in listOf(StreamEvent.Error("busy", 503), StreamEvent.Error("rate_limit_error"),
            StreamEvent.Error("overloaded_error"), StreamEvent.Error("disconnected", retryable = true))) {
            assertEquals(CompactionFailureKind.TRANSIENT, compactionFailure(event).kind)
        }
        for (event in listOf(StreamEvent.Error("forbidden", 403), StreamEvent.Error("invalid api key"),
            StreamEvent.Error("unknown model", 404))) {
            assertEquals(CompactionFailureKind.PERMANENT, compactionFailure(event).kind)
        }
    }
}
