package com.simplechat.app.ui.chat

import com.simplechat.app.data.Attachment
import com.simplechat.app.data.AppTextException
import com.simplechat.app.net.ChatRequestDto
import com.simplechat.app.net.MessageContent
import com.simplechat.app.net.OutMessageDto
import com.simplechat.app.net.estimatedInputTokens
import com.simplechat.app.net.validateRequestBudget
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ContextCompactionTest {
    private val prompts = CompactionPrompts("Summarize", "[Summary]\n", "\n[New]\n", "User: ", "AI: ", "[Image]")
    private fun messages(count: Int, content: String = "short") = (0 until count).map {
        UiMessage("$it", if (it % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT, content)
    }

    @Test fun `normal history retains six messages and a user boundary`() {
        assertEquals(4, compactionCutIndex(messages(10), 1000))
        assertEquals(2, compactionCutIndex(messages(9), 1000))
        assertEquals(0, compactionCutIndex(messages(6), 1000))
    }

    @Test fun `few huge messages can be compacted but latest user turn is always retained`() {
        val long = messages(4, "中".repeat(12_000))
        assertEquals(2, compactionCutIndex(long, 10_000))
        assertEquals(0, compactionCutIndex(long.take(2), 10_000))
        assertEquals(2, compactionCutIndex(long.take(3), 10_000))
    }

    @Test fun `attachment only messages participate in compaction and budget`() {
        val file = Attachment(Attachment.Kind.TEXT, "setting.txt", data = "中".repeat(12_000))
        val path = messages(4).toMutableList()
        path[0] = path[0].copy(content = "", attachments = listOf(file))
        assertEquals(2, compactionCutIndex(path, 1000))
        val text = compactionSegments(path.take(2), "old-summary", prompts).joinToString("")
        assertTrue(text.contains("old-summary"))
        assertTrue(text.contains(file.data))
        assertTrue(text.contains(file.name))
    }

    @Test fun `chunking is lossless for giant messages attachments and unicode`() {
        val source = listOf("a".repeat(2047), "😀".repeat(8000), "中\n\u0000".repeat(10_000), "tail")
        val chunks = boundedCompactionChunks(source.asSequence(), 2048).toList()
        assertEquals(source.joinToString(""), chunks.joinToString(""))
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= 2048 && !Character.isHighSurrogate(it.last()) })
        assertEquals(listOf("a", "😀", "z"), boundedCompactionChunks(sequenceOf("a😀z"), 2).toList())
    }

    @Test fun `every rolling request fits and carries previous batch summary`() = runBlocking {
        val transcript = "中".repeat(150_000) + "END"
        var previous = ""
        var consumed = 0
        var batches = 0
        val result = compactInBatches(sequenceOf(transcript), prompts, 8192) { input, maxTokens, batch ->
            val prefix = if (previous.isEmpty()) "" else prompts.existing + previous + prompts.newMessages
            assertTrue(input.startsWith(prefix))
            val chunk = input.removePrefix(prefix)
            assertEquals(transcript.substring(consumed, consumed + chunk.length), chunk)
            consumed += chunk.length
            val request = ChatRequestDto("model", listOf(
                OutMessageDto("system", MessageContent.text(prompts.system)),
                OutMessageDto("user", MessageContent.text(input)),
            ), true, maxTokens = maxTokens)
            validateRequestBudget(estimatedInputTokens(request), maxTokens, 8192)
            batches = batch
            "summary-$batch".also { previous = it }
        }
        assertTrue(batches > 1)
        assertEquals(transcript.length, consumed)
        assertEquals(previous, result)
    }

    @Test fun `failure never returns a partial summary`() = runBlocking {
        var calls = 0
        try {
            compactInBatches(sequenceOf("x".repeat(40_000)), prompts, 8192) { _, _, _ ->
                if (++calls == 2) throw IOException("interrupted")
                "first-summary"
            }
            fail("A failed batch must not yield a replacement summary")
        } catch (_: IOException) { assertEquals(2, calls) }
    }

    @Test fun `cancellation stops before requesting another batch`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val job = launch {
            compactInBatches(sequenceOf("x".repeat(40_000)), prompts, 8192) { _, _, _ ->
                calls++
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        job.cancelAndJoin()
        assertEquals(1, calls)
    }

    @Test fun `empty and oversized summaries do not silently discard history`() = runBlocking {
        for (summary in listOf("", "中".repeat(20_000))) {
            try {
                compactInBatches(sequenceOf("source"), prompts, 8192) { _, _, _ -> summary }
                fail("Expected invalid summary error")
            } catch (_: AppTextException) { }
        }
    }
}
