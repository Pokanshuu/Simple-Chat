package com.simplechat.app.data

import com.simplechat.app.data.archive.ChatArchive
import com.simplechat.app.data.import.JsonStream
import java.io.File
import java.io.StringWriter
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupStreamTest {
    private val header = BackupConversation(
        id = "c1", title = "中文\"标题", mode = "CREATIVE", createdAt = 123, updatedAt = 456,
        pinned = true, pinnedAt = 456, enabledPresetIds = listOf("preset"),
    )

    @Test
    fun `streamed backup retains empty sessions branches attachments and presets`() = runBlocking {
        val messages = listOf(
            BackupMessage(id = "u", role = "user", content = "中文 😀\n\"转义\"", createdAt = 123),
            BackupMessage(id = "a", parentId = "u", role = "assistant", content = "旧分支", active = false, createdAt = 124),
            BackupMessage(id = "b", parentId = "u", variantIndex = 1, role = "assistant", content = "新分支", reasoning = "思考", createdAt = 125),
            BackupMessage(id = "u2", parentId = "b", role = "user", content = "", createdAt = 126,
                attachments = listOf(Attachment(Attachment.Kind.IMAGE, "图.png", "image/png", "AAAA"))),
        )
        val empty = header.copy(id = "empty", title = "Empty")
        val preset = BackupPreset("preset", "预设", "内容", 0, 100)
        val writer = StringWriter()
        writeBackupStream(writer, 999, listOf(header, empty), listOf(preset)) { id, sink ->
            if (id == header.id) messages.forEach { sink(it) }
        }
        val restored = backupJson.decodeFromString<BackupFile>(writer.toString())
        assertEquals(BackupFile(exportedAt = 999, conversations = listOf(header.copy(messages = messages), empty), presets = listOf(preset)), restored)
    }

    @Test
    fun `empty database still produces a valid nonempty ZIP`() = runBlocking {
        val file = File.createTempFile("empty-export-", ".zip")
        try {
            stageArchive(file) { target ->
                ChatArchive.writeTo(target.outputStream(), 123, 0, 0, 0, emptyList()) { output ->
                    writeBackupStream(output.writer(), 123, emptyList(), emptyList()) { _, _ -> fail("No messages expected") }
                }
            }
            assertTrue(file.length() > 0)
            ZipFile(file).use { zip ->
                val backup = zip.getInputStream(zip.getEntry(ChatArchive.BACKUP)).reader().use { it.readText() }
                assertEquals(BackupFile(exportedAt = 123), backupJson.decodeFromString<BackupFile>(backup))
            }
        } finally { file.delete() }
    }

    @Test
    fun `streamed ZIP contains readable backup and Unicode markdown`() = runBlocking {
        val file = File.createTempFile("stream-export-", ".zip")
        try {
            ChatArchive.writeTo(file.outputStream(), 123, 1, 1, 0,
                listOf(ChatArchive.StreamDocument("0001-测试.md") { it.write("# 中文 😀".toByteArray()) }),
            ) { output ->
                writeBackupStream(output.writer(), 123, listOf(header), emptyList()) { _, sink ->
                    sink(BackupMessage(id = "m", role = "user", content = "正文", createdAt = 123))
                }
            }
            ZipFile(file).use { zip ->
                val restored = ChatArchive.readBackupJson { name -> zip.getInputStream(zip.getEntry(name)).use { it.readBytes() } }
                assertEquals("正文", restored!!.conversations.single().messages.single().content)
                assertEquals("# 中文 😀", zip.getInputStream(zip.getEntry("markdown/0001-测试.md")).reader().use { it.readText() })
            }
        } finally { file.delete() }
    }

    @Test
    fun `backup larger than a small Android heap is written and read one message at a time`() = runBlocking {
        val file = File.createTempFile("large-stream-export-", ".zip")
        val count = 8_000
        val content = "文😀".repeat(1_000)
        try {
            ChatArchive.writeTo(file.outputStream(), 123, 1, count, 0, emptyList()) { output ->
                writeBackupStream(output.writer(), 123, listOf(header), emptyList()) { _, sink ->
                    repeat(count) { index -> sink(BackupMessage(id = "m$index", parentId = if (index == 0) "" else "m${index - 1}", role = "user", content = content, createdAt = 123)) }
                }
            }
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(ChatArchive.BACKUP)
                assertTrue("UTF-8 backup exceeds 50MB", entry.size > 50L * 1024 * 1024)
                zip.getInputStream(entry).reader().buffered().use { reader ->
                    val stream = JsonStream(reader)
                    var actual = 0
                    stream.beginObject()
                    while (stream.hasNextField()) {
                        if (stream.fieldName() != "conversations") { stream.skipValue(); continue }
                        stream.beginArray()
                        while (stream.hasNextArrayElement()) {
                            stream.beginObject()
                            while (stream.hasNextField()) {
                                if (stream.fieldName() != "messages") { stream.skipValue(); continue }
                                stream.beginArray()
                                while (stream.hasNextArrayElement()) {
                                    val message = backupJson.decodeFromString<BackupMessage>(stream.captureValue())
                                    assertEquals("m$actual", message.id)
                                    assertEquals(content, message.content)
                                    actual++
                                }
                            }
                        }
                    }
                    assertEquals(count, actual)
                }
            }
        } finally { file.delete() }
    }
}
