package com.simplechat.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class AutoReadAloudTest {
    private val reply = UiMessage("a", MessageRole.ASSISTANT, "new reply", status = MessageStatus.DONE)
    private val event = CompletedReply("c", "a")
    @Test fun `only completed current replies are automatically read`() {
        assertEquals(reply, autoReadReply(event, "c", true, true, listOf(reply)))
        assertNull(autoReadReply(event, "other", true, true, listOf(reply)))
        assertNull(autoReadReply(event, "c", false, true, listOf(reply)))
        assertNull(autoReadReply(event, "c", true, false, listOf(reply)))
        for (status in listOf(MessageStatus.STREAMING, MessageStatus.STOPPED, MessageStatus.ERROR)) {
            assertNull(autoReadReply(event, "c", true, true, listOf(reply.copy(status = status))))
        }
        assertNull(autoReadReply(event, "c", true, true, listOf(reply.copy(role = MessageRole.USER))))
        assertNull(autoReadReply(event, "c", true, true, listOf(reply.copy(content = " "))))
        assertNull(autoReadReply(event, "c", true, true, emptyList()))
    }
    @Test fun `long speech chunks preserve every character and emoji within engine limit`() {
        val text = "a".repeat(3999) + "😀\n中文".repeat(2000)
        val chunks = speechChunks(text, 4000)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 4000 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        assertTrue(speechChunks("", 4000).isEmpty())
    }
}
