package com.simplechat.app.data

import com.simplechat.app.db.MessageEntity
import com.simplechat.app.db.MessageTextChunk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MessageExportTest {
    @Test
    fun `Android cursor null blobs for empty slices preserve empty text without crashing`() = runBlocking {
        val header = entity("")
        val restored = restoreExportMessage(header, 16) {
            MessageTextChunk(null, null, null, null, null,
                attachmentsPresent = true, reasoningPresent = true)
        }
        assertEquals("", restored.content)
        assertEquals("", restored.attachments)
        assertEquals("", restored.reasoning)
        assertNull(restored.timeline)
        assertNull(restored.errorMessage)
    }

    private fun entity(content: String, attachments: String? = null, reasoning: String? = null, timeline: String? = null, error: String? = null) =
        MessageEntity(id = "m", conversationId = "c", parentId = "", variantIndex = 0, active = true,
            role = "user", content = content, attachments = attachments, reasoning = reasoning,
            timeline = timeline, errorMessage = error, createdAt = 123)

    /** Simulates substr(CAST(text AS BLOB)), including chunks inside a UTF-8 character. */
    private fun String?.sqlSubstr(start: Int, count: Int): ByteArray? {
        val bytes = this?.toByteArray(Charsets.UTF_8) ?: return null
        return bytes.copyOfRange((start - 1).coerceAtMost(bytes.size), (start - 1 + count).coerceAtMost(bytes.size))
    }

    @Test
    fun `all fields preserve split UTF8 characters embedded NUL and different lengths`() = runBlocking {
        val original = entity("中文\u0000😀".repeat(15), "[]".repeat(9), "思考🧠".repeat(20), "timeline".repeat(11), "error".repeat(6))
        val size = 5
        fun chunk(start: Int) = MessageTextChunk(original.content.sqlSubstr(start, size)!!,
            original.attachments.sqlSubstr(start, size), original.reasoning.sqlSubstr(start, size),
            original.timeline.sqlSubstr(start, size), original.errorMessage.sqlSubstr(start, size))
        val restored = restoreExportMessage(original.copy(content = "", attachments = null,
            reasoning = null, timeline = null, errorMessage = null), size) { chunk(it) }
        assertEquals(original, restored)
    }

    @Test
    fun `small nullable fields require only one bounded query`() = runBlocking {
        val original = entity("Hi", reasoning = "")
        var queries = 0
        val restored = restoreExportMessage(original.copy(content = "", reasoning = null), 16) { start ->
            assertEquals(1, start)
            queries++
            MessageTextChunk("Hi".toByteArray(), null, ByteArray(0), null, null)
        }
        assertEquals(original, restored)
        assertEquals(1, queries)
    }

    @Test
    fun `exact chunk boundary preserves null versus empty strings`() = runBlocking {
        val original = entity("😀", attachments = "", reasoning = null)
        var queries = 0
        val restored = restoreExportMessage(original, 4) { start ->
            queries++
            MessageTextChunk(original.content.sqlSubstr(start, 4)!!, ByteArray(0), null, null, null)
        }
        assertEquals(original, restored)
        assertEquals(2, queries)
    }
}
