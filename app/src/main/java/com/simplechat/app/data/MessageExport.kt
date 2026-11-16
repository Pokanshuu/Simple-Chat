package com.simplechat.app.data

import com.simplechat.app.db.MessageEntity
import com.simplechat.app.db.MessageTextChunk
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Read BLOB slices to preserve embedded NULs and UTF-8 characters split across SQL chunks. */
internal suspend fun restoreExportMessage(
    header: MessageEntity,
    chunkBytes: Int,
    readChunk: suspend (start: Int) -> MessageTextChunk,
): MessageEntity {
    require(chunkBytes > 0)
    val content = ByteArrayOutputStream()
    var attachments: ByteArrayOutputStream? = null
    var reasoning: ByteArrayOutputStream? = null
    var timeline: ByteArrayOutputStream? = null
    var error: ByteArrayOutputStream? = null
    var start = 1
    do {
        currentCoroutineContext().ensureActive()
        val chunk = readChunk(start)
        if (start == 1) {
            attachments = if (chunk.attachments != null || chunk.attachmentsPresent) ByteArrayOutputStream() else null
            reasoning = if (chunk.reasoning != null || chunk.reasoningPresent) ByteArrayOutputStream() else null
            timeline = if (chunk.timeline != null || chunk.timelinePresent) ByteArrayOutputStream() else null
            error = if (chunk.errorMessage != null || chunk.errorPresent) ByteArrayOutputStream() else null
        }
        chunk.content?.let { content.write(it) }
        chunk.attachments?.let { attachments?.write(it) }
        chunk.reasoning?.let { reasoning?.write(it) }
        chunk.timeline?.let { timeline?.write(it) }
        chunk.errorMessage?.let { error?.write(it) }
        val more = listOf(chunk.content, chunk.attachments, chunk.reasoning, chunk.timeline, chunk.errorMessage)
            .any { it != null && it.size == chunkBytes }
        start += chunkBytes
    } while (more)
    return header.copy(
        content = content.toString(Charsets.UTF_8.name()),
        attachments = attachments?.toString(Charsets.UTF_8.name()),
        reasoning = reasoning?.toString(Charsets.UTF_8.name()),
        timeline = timeline?.toString(Charsets.UTF_8.name()),
        errorMessage = error?.toString(Charsets.UTF_8.name()),
    )
}
