package com.simplechat.app.ui.chat

import com.simplechat.app.data.Attachment
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.CompactionCheckpoint
import com.simplechat.app.net.StreamEvent
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
    @Test fun `remote file with local original contributes complete text to compaction`() {
        val original = "首段" + "完整原文".repeat(20_000) + "末段"
        val remote = Attachment(Attachment.Kind.TEXT, "source.txt", data = original, fileId = "file-api-1")
        val input = UiMessage("file", MessageRole.USER, "question", attachments = listOf(remote))
        val material = compactionSegments(listOf(input), null, prompts).joinToString("")
        assertTrue(material.contains(original))
        assertFalse(material.contains("file-api-1"))
    }

    @Test fun `unreadable attachments do not block compaction of the remaining history`() = runBlocking {
        val available = UiMessage("text", MessageRole.USER, "Earlier conversation")
        val missing = UiMessage("files", MessageRole.USER, "read attachments", attachments = listOf(
            Attachment(Attachment.Kind.TEXT, "report.pdf", data = "", fileId = "file-api-1"),
            Attachment(Attachment.Kind.TEXT, "local.txt", data = "Complete local text"),
            Attachment(Attachment.Kind.TEXT, "slides.pptx", data = " \n", fileId = "file-api-2")))
        val later = UiMessage("later", MessageRole.ASSISTANT, "Later answer")
        val source = compactionSegments(listOf(available, missing, later), "old summary", prompts)
        val result = compactInBatches(source, prompts, 128_000) { input, _, _ ->
            listOf("old summary", "Earlier conversation", "read attachments", "Complete local text",
                prompts.unreadableAttachment, "Later answer").forEach { assertTrue(input.contains(it)) }
            assertFalse(input.contains("file-api-"))
            assertFalse(input.contains("report.pdf"))
            assertFalse(input.contains("slides.pptx"))
            "new summary"
        }
        assertEquals("new summary", result)
        assertEquals("file-api-1", missing.attachments.first().fileId)
    }
    private val prompts = CompactionPrompts("Summarize", "[Summary]\n", "\n[New]\n", "User: ", "AI: ", "[Image]", "[Unreadable attachment omitted]")
    private fun messages(count: Int, content: String = "short") = (0 until count).map {
        UiMessage("$it", if (it % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT, content)
    }

    @Test fun `creative context selects continuity focused summary`() {
        val conversation = listOf(
            UiMessage("u1", MessageRole.USER, "我们写一段小说，描写林澈和沈遥从猜疑到相互信任。"),
            UiMessage("a1", MessageRole.ASSISTANT, "好的。"),
            UiMessage("u2", MessageRole.USER, "保留雨夜的约定和那封没有寄出的信，作为后文伏笔。"),
        )
        assertEquals(CompactionStyle.CREATIVE, classifyCompaction(conversation))
    }

    @Test fun `technical context selects task focused summary`() {
        val conversation = listOf(
            UiMessage("u1", MessageRole.USER, "帮我修复 Kotlin API 请求报错，先复现 bug。"),
            UiMessage("u2", MessageRole.USER, "提交前运行 test 和 build，记录当前环境与下一步。"),
        )
        assertEquals(CompactionStyle.TECHNICAL, classifyCompaction(conversation))
    }

    @Test fun `generic context stays on generic summary`() {
        assertEquals(CompactionStyle.GENERAL, classifyCompaction(messages(2, "今天天气不错。")))
    }

    @Test fun `quoted and fenced cues do not classify the conversation`() {
        val conversation = listOf(UiMessage("u", MessageRole.USER,
            "代码示例：\n```\nval prompt = \"角色扮演小说\"\n```\n> 帮我写故事和人物"))
        assertEquals(CompactionStyle.GENERAL, classifyCompaction(conversation))
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

    @Test fun `attachment only messages retain a marker without pretending the document was read`() {
        val message = UiMessage("file", MessageRole.USER, "",
            attachments = listOf(Attachment(Attachment.Kind.TEXT, "remote.pdf", data = "")))
        val input = compactionSegments(listOf(message), null, prompts).joinToString("")
        assertTrue(input.contains(prompts.unreadableAttachment))
        assertFalse(input.contains("remote.pdf"))
    }

    @Test fun `fresh history that fits is summarized in one request`() = runBlocking {
        val source = "开场" + "中".repeat(60_000) + "结尾"
        var calls = 0
        val result = compactInBatches(sequenceOf(source), prompts, 1_000_000) { input, budget, _ ->
            calls++
            assertEquals(source, input)
            assertEquals(4096, budget)
            "complete recap"
        }
        assertEquals("complete recap", result)
        assertEquals(1, calls)
    }

    @Test fun `whole request rejection splits immediately without losing any accepted source`() = runBlocking {
        val source = "中文😀".repeat(18_000)
        var checkpoint = CompactionCheckpoint()
        var calls = 0
        val accepted = StringBuilder()
        val result = compactInBatches(sequenceOf(source), prompts, 1_000_000,
            onCheckpoint = { checkpoint = it }) { input, _, _ ->
            if (++calls == 1) {
                assertEquals(source, input)
                throw CompactionRequestFailure(CompactionFailureKind.INPUT_LIMIT, inputLimit = 32_768)
            }
            accepted.append(input)
            "complete recap"
        }
        assertTrue(calls > 1)
        assertTrue(result.startsWith("complete recap"))
        assertEquals(32_768, checkpoint.requestWindow)
        assertEquals(source, accepted.toString())
    }

    @Test fun `every request fits and hierarchical merge includes all leaf summaries in order`() = runBlocking {
        val transcript = "中".repeat(52_000) + "END"
        var consumed = 0
        var level = 0
        val leaves = mutableListOf<String>()
        val result = compactInBatches(sequenceOf(transcript), prompts, 8192,
            onProgress = { level = it.level }) { input, maxTokens, batch ->
            val request = ChatRequestDto("model", listOf(
                OutMessageDto("system", MessageContent.text(prompts.system)),
                OutMessageDto("user", MessageContent.text(input)),
            ), true, maxTokens = maxTokens)
            validateRequestBudget(estimatedInputTokens(request), maxTokens, 8192)
            if (level == 0) {
                assertEquals(transcript.substring(consumed, consumed + input.length), input)
                consumed += input.length
                ("summary-$batch" + "中".repeat(650)).also { leaves += it }
            } else {
                assertEquals(leaves.joinToString("") { "$it\n\n" }, input)
                "complete-summary"
            }
        }
        assertTrue(leaves.size > 1)
        assertEquals(transcript.length, consumed)
        assertEquals("complete-summary", result)
    }

    @Test fun `failure saves completed batches and restart resumes without skipping or repeating input`() = runBlocking {
        val source = "中".repeat(40_000)
        var checkpoint = CompactionCheckpoint()
        var calls = 0
        val accepted = StringBuilder()
        try {
            compactInBatches(sequenceOf(source), prompts, 8192,
                onCheckpoint = { checkpoint = it }) { input, _, _ ->
                if (++calls >= 2) throw IOException("interrupted")
                accepted.append(input)
                "first-summary"
            }
            fail("A failed batch must not yield a replacement summary")
        } catch (error: CompactionRequestFailure) {
            assertEquals(CompactionFailureKind.TRANSIENT, error.kind)
            assertEquals(2, calls)
        }
        assertEquals(1, checkpoint.summaries.size)
        assertEquals(accepted.length.toLong(), checkpoint.offset)
        var level = 0
        compactInBatches(sequenceOf(source), prompts, 8192, checkpoint,
            onProgress = { level = it.level }) { input, _, _ ->
            if (level == 0) accepted.append(input)
            "summary"
        }
        assertEquals(source, accepted.toString())
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

    @Test fun `timed out batch stops without retrying or advancing checkpoint`() = runBlocking {
        var checkpoint = CompactionCheckpoint()
        var calls = 0
        try {
            compactInBatches(sequenceOf("中".repeat(30_000)), prompts, 8192,
                onCheckpoint = { checkpoint = it }, attemptTimeoutMillis = { 100 }) { _, _, _ ->
                if (++calls > 1) awaitCancellation()
                "completed batch"
            }
            fail("Timed out batch must not replace the summary")
        } catch (error: CompactionRequestFailure) {
            assertEquals(CompactionFailureKind.TIMEOUT, error.kind)
        }
        assertEquals(2, calls)
        assertEquals(listOf("completed batch"), checkpoint.summaries)
        assertTrue(checkpoint.offset > 0)
    }

    @Test fun `empty and oversized summaries do not silently discard history`() = runBlocking {
        for (summary in listOf("", "中".repeat(20_000))) {
            try {
                compactInBatches(sequenceOf("source"), prompts, 8192) { _, _, _ -> summary }
                fail("Expected invalid summary error")
            } catch (_: CompactionRequestFailure) { }
        }
    }

    @Test fun `short 270k conversation can compact and preserve the exact pending prompt`() = runBlocking {
        val source = "中".repeat(405_000)
        val pending = UiMessage("u2", MessageRole.USER, "保持原句，继续第八章。")
        val history = listOf(UiMessage("u1", MessageRole.USER, "开始写作"),
            UiMessage("a1", MessageRole.ASSISTANT, source), pending)
        assertEquals(270_000, com.simplechat.app.data.TokenEstimate.of(source))
        assertEquals(0, compactionCutIndex(history, 500_000))
        val window = 150_000
        val cut = compactionCutIndex(history, compactionRecentTokenBudget(1_000_000, window))
        val material = compactionSegments(history.take(cut), null, prompts).joinToString("")
        var requests = 0
        val accepted = StringBuilder()
        val rejection = compactionFailure(StreamEvent.Error("localized", 400,
            rawMessage = "user input too long, 270000 tokens exceeds max 150000 tokens"))
        assertEquals(window, rejection.inputLimit)
        val result = compactInBatches(sequenceOf(material), prompts, 1_000_000,
            CompactionCheckpoint(requestWindow = checkNotNull(rejection.inputLimit))) { input, output, _ ->
            requests++
            assertTrue(com.simplechat.app.data.TokenEstimate.of(input) + output < window)
            accepted.append(input)
            "summary"
        }
        assertTrue(requests > 1)
        assertEquals(material, accepted.toString())
        assertFalse(pending.content in material)
        val summary = com.simplechat.app.db.CompactionEntity("id", "chat", history[cut - 1].id, result, 0, 0)
        val outgoing = orderedOutgoingMessages(history, summary)
        assertEquals(listOf(pending), outgoing)
        val request = buildAnthropicRequest("deepseek-v4-pro", summary.summary, outgoing, false, false, false, null)
        assertEquals(pending.content, (request.messages.single().content as kotlinx.serialization.json.JsonPrimitive).content)
        validateRequestBudget(estimatedInputTokens(request), request.maxTokens, window)
    }

    @Test fun `unknown request limits shrink adaptively and remain bounded`() = runBlocking {
        var calls = 0
        try {
            compactInBatches(sequenceOf("x".repeat(100_000)), prompts, 8192) { _, _, _ ->
                calls++
                throw compactionFailure(StreamEvent.Error("payload too large", 413))
            }
            fail("The operation must stop after bounded recovery")
        } catch (error: CompactionRequestFailure) {
            assertEquals(CompactionFailureKind.INPUT_LIMIT, error.kind)
            assertEquals(3, calls)
        }
    }

    @Test fun `authentication errors stop immediately without resizing or retrying`() = runBlocking {
        var calls = 0
        try {
            compactInBatches(sequenceOf("source"), prompts, 8192) { _, _, _ ->
                calls++
                throw compactionFailure(StreamEvent.Error("invalid api key", 401))
            }
            fail("Authentication failure must be surfaced")
        } catch (error: CompactionRequestFailure) {
            assertEquals(CompactionFailureKind.PERMANENT, error.kind)
            assertEquals(1, calls)
        }
    }

    @Test fun `non shrinking summaries stop instead of looping or discarding source`() = runBlocking {
        var checkpoint = CompactionCheckpoint()
        try {
            compactInBatches(sequenceOf("x".repeat(60_000)), prompts, 1_000_000,
                onCheckpoint = { checkpoint = it }) { input, _, _ -> input }
            fail("An expanding merge cannot converge")
        } catch (error: AppTextException) {
            assertEquals(com.simplechat.app.R.string.compact_no_progress, error.text.resId)
        }
        assertEquals(0L, checkpoint.offset)
        assertTrue(checkpoint.summaries.isEmpty())
        var calls = 0
        val retried = compactInBatches(sequenceOf("x".repeat(60_000)), prompts, 1_000_000, checkpoint) { _, _, _ ->
            calls++
            "small summary"
        }
        assertEquals("small summary", retried)
        assertTrue(calls > 0)
    }

    @Test fun `checkpoint write failure cannot retry or double count an accepted request`() = runBlocking {
        var calls = 0
        try {
            compactInBatches(sequenceOf("source"), prompts, 8192,
                onCheckpoint = { throw IOException("disk full") }) { _, _, _ ->
                calls++
                "summary"
            }
            fail("Checkpoint failure should reach the caller")
        } catch (_: IOException) { assertEquals(1, calls) }
    }
}
