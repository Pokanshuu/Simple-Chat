package com.simplechat.app.data.archive

import com.simplechat.app.data.*
import com.simplechat.app.db.MessageLight
import com.simplechat.app.net.ReplyBlock
import java.io.InputStream
import java.io.OutputStream
import java.text.Normalizer
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Writes only the official v3 archive structure. */
internal object DeepSeekArchive {
    data class Session(val header: BackupConversation, val tree: List<MessageLight>)
    private data class Node(val number: Int, val parent: Int?, val role: String, val original: String?, val createdAt: Long)
    private data class NumberedTree(val nodes: List<Node>, val current: Int)
    private data class ImageRef(val path: String, val messageId: String, val attachmentIndex: Int, val stored: Attachment?)

    suspend fun writeTo(output: OutputStream, sessions: List<Session>,
        attachmentSource: (Attachment) -> InputStream,
        readMessage: suspend (String) -> BackupMessage,
        onMessage: () -> Unit = {},
        storedImageSize: (Attachment) -> Long? = { null },
    ) {
        val index = mutableListOf<JsonObject>()
        val images = mutableListOf<ImageRef>()
        ZipOutputStream(output).use { zip ->
            fun entry(path: String, value: JsonElement) {
                zip.putNextEntry(ZipEntry(path)); val writer = zip.writer(Charsets.UTF_8).buffered()
                writeJsonElement(writer, value); writer.flush(); zip.closeEntry()
            }
            entry("manifest.json", buildJsonObject {
                put("export_format_version", 3)
            })
            for (session in sessions) {
                currentCoroutineContext().ensureActive()
                val header = session.header
                val sessionId = UUID.nameUUIDFromBytes(header.id.toByteArray()).toString()
                val (nodes, current) = number(session.tree)
                val fields = buildJsonObject {
                    put("id", sessionId); put("title", header.title.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull); put("pinned", header.pinned)
                    put("current_message_id", current); put("inserted_at", iso(header.createdAt)); put("updated_at", iso(header.updatedAt))
                }
                zip.putNextEntry(ZipEntry("chat_sessions/$sessionId.json"))
                val writer = zip.writer(Charsets.UTF_8).buffered()
                writer.write(fields.toString().dropLast(1)); writer.write(",\"messages\":[")
                nodes.forEachIndexed { position, node ->
                    currentCoroutineContext().ensureActive()
                    if (position > 0) writer.write(",")
                    val message = node.original?.let { readMessage(it) }
                        ?: BackupMessage("bridge-${node.number}", role = node.role, content = "", createdAt = node.createdAt)
                    val fileEntries = message.attachments.mapIndexedNotNull { attachmentIndex, attachment ->
                        if (!attachment.isImage) return@mapIndexedNotNull null
                        val fileId = UUID.nameUUIDFromBytes("${message.id}:$attachmentIndex".toByteArray()).toString()
                        val path = "chat_sessions/${sessionId}_files/${fileId}_${imageName(attachment.name)}"
                        val size = if (attachment.isStored) storedImageSize(attachment) else inlineSize(attachment.data)
                        val hasOriginal = size != 0L
                        if (hasOriginal) images += ImageRef(path, message.id, attachmentIndex, attachment.takeIf { it.isStored })
                        buildJsonObject {
                            put("id", fileId); put("file_name", attachment.name)
                            put("file_size", size ?: 0L)
                            put("token_usage", JsonNull); put("inserted_at", iso(message.createdAt))
                            put("export_path", if (hasOriginal) JsonPrimitive(path) else JsonNull); put("is_image", true)
                            put("width", attachment.width.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
                            put("height", attachment.height.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    }
                    writeJsonElement(writer, message(message, node.number, node.parent, fileEntries))
                    if (node.original != null) onMessage()
                }
                writer.write("]}"); writer.flush(); zip.closeEntry()
                index += JsonObject(fields.filterKeys { it != "current_message_id" } + ("message_count" to JsonPrimitive(nodes.size)))
            }
            entry("chat_session_index.json", JsonArray(index))
            for ((messageId, refs) in images.groupBy { it.messageId }) {
                val message = if (refs.any { it.stored == null }) readMessage(messageId) else null
                for (ref in refs) {
                    currentCoroutineContext().ensureActive()
                    val attachment = ref.stored ?: checkNotNull(message).attachments[ref.attachmentIndex]
                    zip.putNextEntry(ZipEntry(ref.path))
                    if (attachment.isStored) attachmentSource(attachment).use { it.copyTo(zip, 64 * 1024) }
                    else java.util.Base64.getDecoder().wrap(attachment.data.byteInputStream(Charsets.US_ASCII))
                        .use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
        }
    }

    /** Iterative parent-first traversal; empty bridge nodes preserve role alternation in the official view. */
    private fun number(tree: List<MessageLight>): NumberedTree {
        val children = tree.groupBy { it.parentId }
        val out = mutableListOf<Node>()
        val ids = HashMap<String, Node>()
        val pending = ArrayDeque<MessageLight>()
        children[""].orEmpty().reversed().forEach { pending.addLast(it) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            check(node.id !in ids) { "Invalid message tree" }
            val parentNode = if (node.parentId.isEmpty()) null else checkNotNull(ids[node.parentId])
            var parent = parentNode?.number
            val parentRole = parentNode?.role
            if (node.role == parentRole || parent == null && node.role == "assistant") {
                out += Node(out.size + 1, parent, if (node.role == "user") "assistant" else "user", null, node.createdAt)
                parent = out.last().number
            }
            out += Node(out.size + 1, parent, node.role, node.id, node.createdAt)
            ids[node.id] = out.last()
            children[node.id].orEmpty().reversed().forEach { pending.addLast(it) }
        }
        check(ids.size == tree.size) { "Invalid message tree" }
        var activeId = ""
        while (true) activeId = children[activeId]?.firstOrNull { it.active }?.id ?: break
        return NumberedTree(out, ids[activeId]?.number ?: out.lastOrNull()?.number ?: 0)
    }

    private fun matchesAnswer(message: BackupMessage): Boolean {
        if (message.content.isEmpty() || message.timeline.isEmpty()) return false
        var offset = 0
        for (block in message.timeline) {
            if (block is ReplyBlock.Text) {
                if (!message.content.regionMatches(offset, block.text, 0, block.text.length)) return false
                offset += block.text.length
            }
        }
        return offset == message.content.length
    }

    private fun inlineSize(data: String): Long {
        val padding = if (data.endsWith("==")) 2 else if (data.endsWith('=')) 1 else 0
        return (data.length.toLong() * 3 / 4 - padding).coerceAtLeast(0)
    }

    private fun imageName(name: String): String = Normalizer.normalize(name.take(160)
        .trimEnd { it.isHighSurrogate() }
        .map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }
        .joinToString("").trim('.', ' ').ifBlank { "image" }, Normalizer.Form.NFC)

    private fun iso(value: Long) = Instant.ofEpochMilli(value).toString()
    private fun date(value: String?): JsonElement = runCatching { value?.let { JsonPrimitive(Instant.parse(it).toString()) } }
        .getOrNull() ?: JsonNull

    internal fun message(message: BackupMessage, number: Int, parent: Int?, files: List<JsonObject> = emptyList()): JsonObject = buildJsonObject {
        put("id", number); put("parent_id", parent?.let(::JsonPrimitive) ?: JsonNull)
        put("role", if (message.role == "user") "USER" else "ASSISTANT")
        put("status", when {
            message.role == "user" -> "FINISHED"
            message.stopReason == "refusal" -> "CONTENT_FILTER"
            message.stopReason == "timeout" -> "TIMEOUT"
            message.stopReason == "context_length_exceeded" -> "CONTEXT_LENGTH_EXCEEDED"
            message.status == "ERROR" -> "FAILED"
            message.status == "STOPPED" || message.stopReason == "max_tokens" -> "INCOMPLETE"
            message.status == "STREAMING" -> "WIP"
            else -> "FINISHED"
        })
        put("thinking_enabled", !message.reasoning.isNullOrBlank() || message.timeline.any { it is ReplyBlock.Thinking })
        put("search_enabled", message.timeline.any { it is ReplyBlock.SearchResult })
        put("accumulated_token_usage", 0); put("inserted_at", iso(message.createdAt))
        put("fragments", buildJsonArray {
            fun text(type: String, value: String) { add(buildJsonObject { put("type", type); put("content", value) }) }
            if (files.isNotEmpty()) add(buildJsonObject { put("type", "FILE"); put("files", JsonArray(files)) })
            if (message.role == "user") text("REQUEST", message.content) else {
                // Use ordered text fragments only when they match the saved answer.
                val orderedText = matchesAnswer(message)
                if (!message.reasoning.isNullOrBlank()) add(buildJsonObject {
                    put("type", "THINK"); put("content", message.reasoning)
                    put("elapsed_secs", message.thinkingMs?.div(1000.0)?.let(::JsonPrimitive) ?: JsonNull)
                })
                // Allocate tool metadata only for messages that have tool calls.
                var calls: MutableMap<String, ReplyBlock.ToolUse>? = null
                message.timeline.forEach { block -> when (block) {
                    is ReplyBlock.Text -> if (orderedText && block.text.isNotEmpty()) text("RESPONSE", block.text)
                    is ReplyBlock.ToolUse -> {
                        val pending = calls ?: HashMap<String, ReplyBlock.ToolUse>().also { calls = it }
                        pending[block.id] = block
                    }
                    is ReplyBlock.SearchResult -> {
                        val call = calls?.remove(block.toolUseId)
                        val imageSearch = block.kind == "image_search" || block.kind == "deep_image_search"
                        add(buildJsonObject {
                            put("type", if (imageSearch) "TOOL_IMAGE_SEARCH" else "TOOL_SEARCH")
                            if (!imageSearch) put("queries", buildJsonArray { call?.queries?.forEach { add(JsonPrimitive(it)) } })
                            put("status", if (block.failed) "FAILED" else "FINISHED")
                            put("results", buildJsonArray { block.results.forEach { source -> add(buildJsonObject {
                                put(if (imageSearch) "source_page_url" else "url", source.url)
                                put("title", source.title.orEmpty()); put("snippet", source.snippet.orEmpty())
                                put("published_at", date(source.pageAge)); put("site_name", source.siteName?.let(::JsonPrimitive) ?: JsonNull)
                                if (!imageSearch) source.siteIcon?.let { put("site_icon", it) }
                            }) } })
                        })
                    }
                    is ReplyBlock.FetchResult -> {
                        val call = calls?.remove(block.toolUseId)
                        when {
                            !block.signature.isNullOrBlank() && !block.failed -> add(buildJsonObject {
                                put("type", "READ_LINK"); put("results", buildJsonArray { add(buildJsonObject {
                                    put("signature", block.signature)
                                    if (block.url.isNotBlank()) put("url", block.url)
                                    block.title?.let { put("title", it) }
                                }) })
                            })
                            call?.action == "open_page" -> add(buildJsonObject {
                                put("type", "TOOL_OPEN"); put("status", if (block.failed) "FAILED" else "FINISHED")
                                put("url", block.url.ifBlank { call.url }.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
                                put("title", block.title?.let(::JsonPrimitive) ?: JsonNull)
                                put("published_at", date(block.publishedAt))
                                block.snippet?.let { put("snippet", it) }
                                block.siteName?.let { put("site_name", it) }
                            })
                            call?.action == "find_in_page" -> add(buildJsonObject {
                                put("type", "TOOL_FIND"); put("status", if (block.failed) "FAILED" else "FINISHED")
                                if (call.pattern.isNotEmpty()) put("pattern", call.pattern)
                            })
                        }
                    }
                    is ReplyBlock.Thinking -> Unit
                } }
                if (!orderedText) text("RESPONSE", message.content)
                if (!message.errorMessage.isNullOrBlank()) text("TEMPLATE_RESPONSE", message.errorMessage)
            }
        })
    }
}
