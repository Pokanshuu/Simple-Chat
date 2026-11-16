package com.simplechat.app.data

import java.io.Writer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Same backup format, but only one message is encoded at a time. Does not close [writer]. */
internal suspend fun writeBackupStream(
    writer: Writer,
    exportedAt: Long,
    headers: List<BackupConversation>,
    presets: List<BackupPreset>,
    attachmentSource: ((Attachment) -> InputStream)? = null,
    forEachMessage: suspend (String, suspend (BackupMessage) -> Unit) -> Unit,
) {
    writer.write("{\"format\":${JsonPrimitive(BackupFile.FORMAT)},\"version\":${BackupFile.VERSION},\"exportedAt\":$exportedAt,\"conversations\":[")
    headers.forEachIndexed { index, header ->
        currentCoroutineContext().ensureActive()
        if (index > 0) writer.write(",")
        val fields = backupJson.encodeToJsonElement(BackupConversation.serializer(), header)
            .jsonObject.filterKeys { it != "messages" }
        writer.write(JsonObject(fields).toString().dropLast(1))
        writer.write(",\"messages\":[")
        var first = true
        forEachMessage(header.id) { message ->
            currentCoroutineContext().ensureActive()
            if (!first) writer.write(",")
            first = false
            writeBackupMessage(writer, message, attachmentSource)
        }
        writer.write("]}")
    }
    writer.write("],\"presets\":[")
    presets.forEachIndexed { index, preset ->
        if (index > 0) writer.write(",")
        writer.write(backupJson.encodeToString(BackupPreset.serializer(), preset))
    }
    writer.write("]}")
    writer.flush()
}

/** JsonElement holds references to the original strings; writing never builds an escaped full copy. */
internal fun writeJsonElement(writer: Writer, element: JsonElement) {
    when (element) {
        JsonNull -> writer.write("null")
        is JsonPrimitive -> if (element.isString) writeJsonString(writer, element.content) else writer.write(element.content)
        is JsonArray -> {
            writer.write("[")
            element.forEachIndexed { index, value -> if (index > 0) writer.write(","); writeJsonElement(writer, value) }
            writer.write("]")
        }
        is JsonObject -> {
            writer.write("{")
            element.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) writer.write(",")
                writeJsonString(writer, key); writer.write(":"); writeJsonElement(writer, value)
            }
            writer.write("}")
        }
    }
}

private fun writeJsonString(writer: Writer, text: String) {
    writer.write("\"")
    var start = 0
    text.forEachIndexed { index, char ->
        val escape = when (char) {
            '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
            '\b' -> "\\b"; '\u000C' -> "\\f"
            else -> if (char < ' ') "\\u" + char.code.toString(16).padStart(4, '0') else null
        }
        if (escape != null) {
            if (index > start) writer.write(text, start, index - start)
            writer.write(escape)
            start = index + 1
        }
    }
    if (start < text.length) writer.write(text, start, text.length - start)
    writer.write("\"")
}

private fun writeBackupMessage(writer: Writer, message: BackupMessage, source: ((Attachment) -> InputStream)?) {
    val fields = backupJson.encodeToJsonElement(BackupMessage.serializer(), message.copy(attachments = emptyList())).jsonObject
    writer.write("{")
    fields.entries.forEachIndexed { index, (name, value) ->
        if (index > 0) writer.write(",")
        writeJsonString(writer, name); writer.write(":")
        if (name != "attachments") writeJsonElement(writer, value)
        else {
            writer.write("[")
            message.attachments.forEachIndexed { i, attachment ->
                if (i > 0) writer.write(",")
                if (!attachment.isStored || source == null) {
                    writeJsonElement(writer, backupJson.encodeToJsonElement(Attachment.serializer(), attachment))
                } else {
                    val metadata = backupJson.encodeToJsonElement(Attachment.serializer(), attachment.copy(data = "", path = null)).jsonObject
                    writer.write("{")
                    metadata.entries.forEachIndexed { j, (key, field) ->
                        if (j > 0) writer.write(",")
                        writeJsonString(writer, key); writer.write(":")
                        if (key != "data") writeJsonElement(writer, field)
                        else {
                            writer.write("\"")
                            val ascii = object : OutputStream() {
                                override fun write(value: Int) { writer.write(value) }
                                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                                    writer.write(String(bytes, offset, length, Charsets.US_ASCII))
                                }
                                override fun close() { writer.flush() }
                            }
                            source(attachment).use { input -> java.util.Base64.getEncoder().wrap(ascii).use { input.copyTo(it, 16 * 1024) } }
                            writer.write("\"")
                        }
                    }
                    writer.write("}")
                }
            }
            writer.write("]")
        }
    }
    writer.write("}")
}
