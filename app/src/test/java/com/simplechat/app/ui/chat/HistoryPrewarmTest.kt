package com.simplechat.app.ui.chat

import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.ui.markdown.MarkdownPrewarmMaxChars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPrewarmTest {
    private fun reply(body: String) = UiMessage(id = "a", role = MessageRole.ASSISTANT, content = body)

    @Test fun `choose nearest older replies within four rows of a deep viewport`() {
        val visited = mutableListOf<Int>()
        val history = object : AbstractList<UiMessage>() {
            override val size = 100_000
            override fun get(index: Int): UiMessage {
                visited += index
                return if (index % 2 == 0) reply("$index") else reply("prompt").copy(role = MessageRole.USER)
            }
        }
        assertEquals(listOf("89998", "89996"), historyPrewarmBodies(history, before = 90_000))
        assertEquals(listOf(89_999, 89_998, 89_997, 89_996), visited)
        visited.clear()
        assertTrue(historyPrewarmBodies(history, before = 0).isEmpty())
        assertTrue(historyPrewarmBodies(history, before = Int.MAX_VALUE).isEmpty())
        assertTrue(visited.isEmpty())
    }

    @Test fun `skip replies whose displayed Markdown differs or is still growing`() {
        val entries = listOf(
            reply("ready"),
            reply("partial").copy(status = MessageStatus.STREAMING),
            reply("raw").copy(timeline = listOf(ReplyBlock.Text("rendered"))),
            reply("x".repeat(LARGE_MESSAGE_CHARS + 1)),
        )
        assertEquals(listOf("ready"), historyPrewarmBodies(entries, before = entries.size))
    }

    @Test fun `per-window character and reply bounds prefer closer eligible rows`() {
        val large = "X".repeat(MarkdownPrewarmMaxChars - 2)
        val entries = listOf(reply("ok"), reply("longer"), reply(large), reply("x".repeat(MarkdownPrewarmMaxChars + 1)))
        assertEquals(listOf(large, "ok"), historyPrewarmBodies(entries, before = entries.size))
        val short = listOf(reply("a"), reply("b"), reply("c"), reply("d"))
        assertEquals(listOf("d", "c"), historyPrewarmBodies(short, before = short.size))
    }
}
