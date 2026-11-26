package com.simplechat.app.ui.chat

import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.data.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextUsageTest {

    @Test
    fun `used segments preserve request order and skip empty categories`() {
        val segments = usedContextSegments(
            presetTokens = 120,
            compactedTokens = 0,
            messageTokens = 900,
            attachmentTokens = 200,
            toolTokens = 40,
        )

        assertEquals(
            listOf(
                ContextSegmentKind.PRESETS,
                ContextSegmentKind.MESSAGES,
                ContextSegmentKind.ATTACHMENTS,
                ContextSegmentKind.TOOL_CALLS,
            ),
            segments.map { it.kind },
        )
        assertTrue(segments.all { it.isUsed })
    }

    @Test
    fun `reserve and free space do not affect used percentage`() {
        val stats = ContextStats(
            usedTokens = 60,
            windowTokens = 100,
            messageCount = 3,
            totalTokens = 60,
            compactedTokenSaving = 0,
            avgTtftMs = null,
            avgTps = null,
            totalThinkingSeconds = null,
            hasCompaction = false,
            breakdown = listOf(ContextSegment(ContextSegmentKind.MESSAGES, 60)),
            autoCompactReserveTokens = 20,
        )

        assertEquals(0.6f, stats.ratio)
        assertEquals(60, stats.percent)
        assertEquals(20, stats.freeTokens)
        assertEquals(
            listOf(
                ContextSegmentKind.MESSAGES,
                ContextSegmentKind.AUTO_COMPACT_RESERVE,
                ContextSegmentKind.FREE_SPACE,
            ),
            stats.segments.map { it.kind },
        )
        assertEquals(60, stats.segments.filter { it.isUsed }.sumOf { it.tokens })
    }

    @Test
    fun `auto compact remaining clamps at zero`() {
        assertEquals(32, autoCompactRemainingTokens(usedTokens = 68, thresholdTokens = 100))
        assertEquals(0, autoCompactRemainingTokens(usedTokens = 110, thresholdTokens = 100))
        assertEquals(968_000, autoCompactThresholdTokens(windowTokens = 1_000_000, reserveTokens = 32_000))
    }

    @Test
    fun `tool breakdown counts only signed read-link history that is sent back`() {
        val message = UiMessage(
            id = "assistant",
            role = MessageRole.ASSISTANT,
            content = "answer",
            model = "deepseek-flash",
            timeline = listOf(
                ReplyBlock.SearchResult("search", "search", results = emptyList()),
                ReplyBlock.ToolUse("unsigned", "fetch", ReadLinkKind, "read_link", url = "https://unsigned.example"),
                ReplyBlock.FetchResult(
                    toolUseId = "unsigned",
                    kind = ReadLinkKind,
                    url = "https://unsigned.example",
                ),
                ReplyBlock.ToolUse("signed", "fetch", ReadLinkKind, "read_link", url = "https://signed.example"),
                ReplyBlock.FetchResult(
                    toolUseId = "signed",
                    kind = ReadLinkKind,
                    url = "https://signed.example",
                    title = "Signed page",
                    signature = "signature",
                ),
            ),
        )

        val tokens = outgoingToolHistoryTokens(listOf(message), allowedModel = "deepseek-flash")

        assertTrue(tokens > 0)
        assertEquals(0, outgoingToolHistoryTokens(listOf(message), allowedModel = "other-model"))
    }

    @Test
    fun `compaction candidates start after the active summary boundary`() {
        val messages = listOf(
            UiMessage("u1", MessageRole.USER, "one"),
            UiMessage("a1", MessageRole.ASSISTANT, "two"),
            UiMessage("u2", MessageRole.USER, "three"),
        )
        val compaction = com.simplechat.app.db.CompactionEntity(
            id = "summary",
            conversationId = "conversation",
            throughMessageId = "a1",
            summary = "summary",
            createdAt = 1,
            updatedAt = 1,
        )

        assertEquals(listOf("u2"), compactionCandidates(messages, compaction).map { it.id })
        assertFalse(hasCompactableHistory(messages, compaction, modelWindow = 1_000_000))
    }

    @Test
    fun `local totals include inline and locally copied attachments but not remote-only files`() {
        val messages = listOf(
            UiMessage(
                "local",
                MessageRole.USER,
                "",
                attachments = listOf(
                    Attachment(Attachment.Kind.TEXT, "copy.txt", data = "local copy"),
                    Attachment(Attachment.Kind.IMAGE, "inline.png", mime = "image/png", data = "base64"),
                ),
            ),
            UiMessage(
                "remote",
                MessageRole.USER,
                "",
                attachments = listOf(
                    Attachment(Attachment.Kind.TEXT, "remote.json", data = "", fileId = "file-1"),
                ),
            ),
        )

        assertEquals(
            com.simplechat.app.data.TokenEstimate.of("local copy") + ImageTokenUpperBound,
            locallyStoredAttachmentTokens(messages),
        )
    }
}
