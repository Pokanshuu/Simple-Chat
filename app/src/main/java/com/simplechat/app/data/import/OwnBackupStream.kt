package com.simplechat.app.data.import

import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.BackupFile
import com.simplechat.app.data.BackupMessage
import com.simplechat.app.data.BackupPreset
import com.simplechat.app.data.ImportFailure
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.Attachments
import com.simplechat.app.data.backupJson
import java.io.File
import java.io.Reader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject

/** Own JSON/ZIP backups are scanned and spooled one conversation at a time, never read as a full String. */
internal class OwnBackupStream private constructor(
    private val openReader: () -> Reader,
    private val scratch: File,
    override val expected: Int,
    override val presets: List<BackupPreset>,
    private val onFailure: (ImportFailure) -> Unit,
    private val naming: ExternalBackup.ImportNaming,
    private val onProgress: (Float) -> Unit,
) : ImportSessionSource {
    override val indexVerified = true

    override suspend fun forEachSession(block: suspend (ImportSession) -> Unit) {
        openReader().buffered().use { reader ->
            val stream = JsonStream(reader)
            stream.beginObject()
            var position = 0
            while (stream.hasNextField()) {
                if (stream.fieldName() != "conversations") { stream.skipValue(); continue }
                stream.beginArray()
                while (stream.hasNextArrayElement()) {
                    currentCoroutineContext().ensureActive()
                    position++
                    val file = File.createTempFile("conversation-import-", ".json", scratch)
                    try {
                        file.writer(Charsets.UTF_8).buffered().use { stream.copyValueTo(it) }
                        val session = try { openSession(file) }
                        catch (error: CancellationException) { throw error }
                        catch (error: Exception) {
                            onFailure(ImportFailure(null, "conversation-$position", ImportFailure.Reason.UNREADABLE,
                                "${error.javaClass.simpleName}: ${error.message}"))
                            null
                        }
                        if (session != null) block(session)
                        onProgress((position.toFloat() / expected.coerceAtLeast(1)).coerceAtMost(0.99f))
                    } finally { file.delete() }
                }
            }
        }
    }

    private fun openSession(file: File): ImportSession {
        val createdImages = linkedSetOf<String>()
        var count = 0
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        file.reader(Charsets.UTF_8).buffered().use { reader ->
            val stream = JsonStream(reader)
            stream.beginObject()
            while (stream.hasNextField()) {
                val name = stream.fieldName()
                if (name == "messages") {
                    stream.beginArray()
                    while (stream.hasNextArrayElement()) { stream.skipValue(); count++ }
                } else fields[name] = readField(stream)
            }
        }
        val header = backupJson.decodeFromJsonElement(BackupConversation.serializer(), JsonObject(fields))
        return ImportSession(header, expectedMessages = count, discard = {
            createdImages.forEach { naming.discardImage?.invoke(it) }
        }) { sink ->
            file.reader(Charsets.UTF_8).buffered().use { reader ->
                val stream = JsonStream(reader)
                stream.beginObject()
                while (stream.hasNextField()) {
                    if (stream.fieldName() != "messages") { stream.skipValue(); continue }
                    stream.beginArray()
                    while (stream.hasNextArrayElement()) {
                        currentCoroutineContext().ensureActive()
                        val message = readMessage(stream, createdImages)
                        sink(message)
                    }
                }
            }
        }
    }

    private fun readMessage(stream: JsonStream, createdImages: MutableSet<String>): BackupMessage {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
        val attachments = mutableListOf<Attachment>()
        stream.beginObject()
        while (stream.hasNextField()) {
            val name = stream.fieldName()
            if (name != "attachments") { fields[name] = readField(stream); continue }
            stream.beginArray()
            while (stream.hasNextArrayElement()) {
                val metadata = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
                var dataFile: File? = null
                try {
                    stream.beginObject()
                    while (stream.hasNextField()) {
                        val key = stream.fieldName()
                        if (key == "data") {
                            dataFile = File.createTempFile("attachment-import-", ".data", scratch)
                            dataFile.writer(Charsets.UTF_8).buffered().use { stream.copyStringTo(it) }
                            metadata[key] = kotlinx.serialization.json.JsonPrimitive("")
                        } else metadata[key] = readField(stream)
                    }
                    var attachment = backupJson.decodeFromJsonElement(Attachment.serializer(), JsonObject(metadata))
                    val data = dataFile
                    if (data != null && data.length() > 0L && attachment.isImage && naming.storeImageStream != null) {
                        val path = java.util.Base64.getMimeDecoder().wrap(data.inputStream().buffered()).use {
                            naming.storeImageStream.invoke(it, attachment.mime)
                        }
                        // Register immediately: decoding a later field/message may fail.
                        // Paths already present in a backup were not created by this import.
                        createdImages += path
                        attachment = attachment.copy(data = "", path = path)
                    } else if (data != null) attachment = attachment.copy(data = data.readText(Charsets.UTF_8))
                    attachments += attachment
                } finally { dataFile?.delete() }
            }
        }
        fields["attachments"] = backupJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Attachment.serializer()), attachments)
        return backupJson.decodeFromJsonElement(BackupMessage.serializer(), JsonObject(fields))
    }

    /** Avoid retaining both escaped JSON and a decoded copy of a multi-megabyte body. */
    private fun readField(stream: JsonStream): kotlinx.serialization.json.JsonElement =
        if (stream.valueKind() == '"') kotlinx.serialization.json.JsonPrimitive(stream.textValue())
        else backupJson.parseToJsonElement(stream.captureValue())

    companion object {
        fun format(openReader: () -> Reader): String? = openReader().buffered().use { reader ->
            val stream = JsonStream(reader)
            stream.beginObject()
            while (stream.hasNextField()) {
                if (stream.fieldName() == "format") return@use stream.textValue()
                stream.skipValue()
            }
            null
        }

        fun open(openReader: () -> Reader, scratch: File, naming: ExternalBackup.ImportNaming = ExternalBackup.ImportNaming(), onProgress: (Float) -> Unit = {}, onFailure: (ImportFailure) -> Unit): OwnBackupStream {
            var format: String? = null
            var version = BackupFile.VERSION
            var count = 0
            val presets = mutableListOf<BackupPreset>()
            openReader().buffered().use { reader ->
                val stream = JsonStream(reader)
                stream.beginObject()
                while (stream.hasNextField()) {
                    when (stream.fieldName()) {
                        "format" -> format = stream.textValue()
                        "version" -> version = stream.textValue()?.toIntOrNull() ?: -1
                        "conversations" -> {
                            stream.beginArray()
                            while (stream.hasNextArrayElement()) { stream.skipValue(); count++ }
                        }
                        "presets" -> {
                            stream.beginArray()
                            while (stream.hasNextArrayElement()) presets += backupJson.decodeFromString<BackupPreset>(stream.captureValue())
                        }
                        else -> stream.skipValue()
                    }
                }
            }
            check(format == BackupFile.FORMAT) { "Unrecognized backup format" }
            check(version == BackupFile.VERSION) { "Unsupported backup version $version (expected ${BackupFile.VERSION})" }
            return OwnBackupStream(openReader, scratch, count, presets, onFailure, naming, onProgress)
        }
    }
}
