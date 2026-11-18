package com.simplechat.app.data.archive

import com.simplechat.app.data.*
import com.simplechat.app.db.MessageLight
import com.simplechat.app.db.MessageTree
import com.simplechat.app.net.ReplyBlock
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Official v3 archive plus a full-fidelity native extension in the optional user.json slot.
 * The official importer explicitly skips user.json. It contains no account information.
 * Each message and attachment is streamed; no whole-history JSON or ZIP byte array is built.
 */
internal object DeepSeekArchive {
    const val EXTENSION = "user.json"
    data class Session(val header: BackupConversation, val tree: List<MessageLight>)
    private data class Node(val number: Int, val parent: Int?, val role: String, val original: String?, val createdAt: Long)
    private data class ImageRef(val path: String, val messageId: String, val attachmentIndex: Int)

    suspend fun writeTo(output: OutputStream, sessions: List<Session>,
        attachmentSource: (Attachment) -> InputStream,
        readMessage: suspend (String) -> BackupMessage,
        writeNative: suspend (OutputStream) -> Unit,
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
                put("simplechat_extension_version", 1)
                put("simplechat_backup", EXTENSION)
            })
            zip.putNextEntry(ZipEntry(EXTENSION)); writeNative(zip); zip.closeEntry()
            for (session in sessions) {
                currentCoroutineContext().ensureActive()
                val header = session.header
                val sessionId = UUID.nameUUIDFromBytes(header.id.toByteArray()).toString()
                val nodes = number(session.tree)
                val currentId = MessageTree.activePathIds(session.tree).lastOrNull()
                val current = nodes.lastOrNull { it.original == currentId }?.number ?: 0
                val fields = buildJsonObject {
                    put("id", sessionId); put("title", header.title); put("pinned", header.pinned)
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
                    val fileEntries = message.attachments.mapIndexed { attachmentIndex, attachment ->
                        val fileId = UUID.nameUUIDFromBytes("${message.id}:$attachmentIndex".toByteArray()).toString()
                        val path = if (attachment.isImage) "chat_sessions/${sessionId}_files/${fileId}_image" else null
                        if (path != null) images += ImageRef(path, message.id, attachmentIndex)
                        buildJsonObject {
                            put("id", fileId); put("file_name", attachment.name)
                            put("file_size", if (attachment.isStored) 0 else attachment.sizeBytes)
                            put("token_usage", JsonNull); put("inserted_at", iso(message.createdAt))
                            put("export_path", path?.let(::JsonPrimitive) ?: JsonNull); put("is_image", attachment.isImage)
                            put("width", attachment.width.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
                            put("height", attachment.height.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    }
                    writeJsonElement(writer, message(message, node.number, node.parent, fileEntries))
                }
                writer.write("]}"); writer.flush(); zip.closeEntry()
                index += JsonObject(fields.filterKeys { it != "current_message_id" } + ("message_count" to JsonPrimitive(nodes.size)))
            }
            entry("chat_session_index.json", JsonArray(index))
            for ((messageId, refs) in images.groupBy { it.messageId }) {
                val message = readMessage(messageId)
                for (ref in refs) {
                    currentCoroutineContext().ensureActive()
                    val attachment = message.attachments[ref.attachmentIndex]
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
    private fun number(tree: List<MessageLight>): List<Node> {
        val children = tree.groupBy { it.parentId }
        val byId = tree.associateBy { it.id }
        check(byId.size == tree.size)
        val out = mutableListOf<Node>()
        val ids = mutableMapOf<String, Int>()
        val pending = ArrayDeque<MessageLight>()
        children[""].orEmpty().reversed().forEach { pending.addLast(it) }
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            check(node.id !in ids) { "Invalid message tree" }
            var parent = if (node.parentId.isBlank()) null else checkNotNull(ids[node.parentId])
            val parentRole = byId[node.parentId]?.role
            if (node.role == parentRole || parent == null && node.role == "assistant") {
                out += Node(out.size + 1, parent, if (node.role == "user") "assistant" else "user", null, node.createdAt)
                parent = out.last().number
            }
            out += Node(out.size + 1, parent, node.role, node.id, node.createdAt)
            ids[node.id] = out.last().number
            children[node.id].orEmpty().reversed().forEach { pending.addLast(it) }
        }
        check(ids.size == tree.size) { "Invalid message tree" }
        return out
    }

    private fun iso(value: Long) = Instant.ofEpochMilli(value).toString()
    private fun date(value: String?): JsonElement = runCatching { value?.let { JsonPrimitive(Instant.parse(it).toString()) } }
        .getOrNull() ?: JsonNull

    internal fun message(message: BackupMessage, number: Int, parent: Int?, files: List<JsonObject> = emptyList()): JsonObject = buildJsonObject {
        put("id", number); put("parent_id", parent?.let(::JsonPrimitive) ?: JsonNull)
        put("role", if (message.role == "user") "USER" else "ASSISTANT")
        put("status", when (message.status) { "ERROR" -> "FAILED"; "STOPPED" -> "INCOMPLETE"; "STREAMING" -> "WIP"
            else -> if (message.stopReason == "max_tokens") "INCOMPLETE" else "FINISHED" })
        put("thinking_enabled", !message.reasoning.isNullOrBlank() || message.timeline.any { it is ReplyBlock.Thinking })
        put("search_enabled", message.timeline.any { it is ReplyBlock.SearchResult })
        put("accumulated_token_usage", 0); put("inserted_at", iso(message.createdAt))
        put("fragments", buildJsonArray {
            fun text(type: String, value: String) { add(buildJsonObject { put("type", type); put("content", value) }) }
            if (files.isNotEmpty()) add(buildJsonObject { put("type", "FILE"); put("files", JsonArray(files)) })
            if (message.role == "user") text("REQUEST", message.content) else {
                // Keep all original content; the native extension additionally preserves citations and signatures.
                if (!message.reasoning.isNullOrBlank()) add(buildJsonObject {
                    put("type", "THINK"); put("content", message.reasoning)
                    put("elapsed_secs", message.thinkingMs?.div(1000.0)?.let(::JsonPrimitive) ?: JsonNull)
                })
                message.timeline.forEach { block -> when (block) {
                    is ReplyBlock.SearchResult -> add(buildJsonObject {
                        put("type", "TOOL_SEARCH"); put("queries", buildJsonArray { })
                        put("status", if (block.failed) "FAILED" else "FINISHED")
                        put("results", buildJsonArray { block.results.forEach { source -> add(buildJsonObject {
                            put("url", source.url); put("title", source.title.orEmpty()); put("snippet", source.snippet.orEmpty())
                            put("published_at", date(source.pageAge)); put("site_name", source.siteName?.let(::JsonPrimitive) ?: JsonNull)
                            put("site_icon", source.siteIcon?.let(::JsonPrimitive) ?: JsonNull)
                        }) } })
                    })
                    is ReplyBlock.FetchResult -> if (!block.signature.isNullOrBlank()) add(buildJsonObject {
                        put("type", "READ_LINK"); put("results", buildJsonArray { add(buildJsonObject {
                            put("signature", block.signature); put("url", block.url)
                            put("title", block.title?.let(::JsonPrimitive) ?: JsonNull)
                        }) })
                    })
                    else -> Unit
                } }
                text("RESPONSE", message.content)
                if (!message.errorMessage.isNullOrBlank()) text("TEMPLATE_RESPONSE", message.errorMessage)
            }
        })
    }
}
