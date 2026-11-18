package com.simplechat.app.data.archive

import com.simplechat.app.data.*
import com.simplechat.app.data.import.ExternalBackup
import com.simplechat.app.db.MessageLight
import com.simplechat.app.net.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeepSeekArchiveTest {
    @Test fun `official schema contains all branches while native extension round trips all fields`() = runBlocking {
        val image = Attachment(Attachment.Kind.IMAGE, "测试.png", "image/png", "aW1hZ2U=")
        val timeline = listOf(ReplyBlock.SearchResult("search", "web_search", listOf(WebSource("https://example.com", "Source"))),
            ReplyBlock.Text("Answer", listOf(Citation("https://example.com", "Source"))))
        val messages = listOf(
            BackupMessage("u", role = "user", content = "Question with complete text", attachments = listOf(image,
                Attachment(Attachment.Kind.TEXT, "local.txt", data = "附件全文")), createdAt = 1700000000000),
            BackupMessage("a", parentId = "u", role = "assistant", content = "Answer", reasoning = "Reason", thinkingMs = 2500,
                model = "deepseek-flash", ttftMs = 100, tps = 25f, timeline = timeline, createdAt = 1700000000100),
            BackupMessage("b", parentId = "u", variantIndex = 1, active = false, role = "assistant", content = "Other answer",
                status = "STOPPED", createdAt = 1700000000200))
        val header = BackupConversation("id/with/path", "兼容导出", "CREATIVE", createdAt = 1700000000000,
            updatedAt = 1700000000200, enabledPresetIds = listOf("p"))
        val preset = BackupPreset("p", "preset", "Prompt", createdAt = 1)
        val tree = messages.reversed().map { MessageLight(it.id, it.parentId, it.variantIndex, it.active, it.role, it.status, it.createdAt) }
        val target = File("build/test-fixtures/deepseek-compatible.zip").apply { parentFile.mkdirs() }
        target.outputStream().use { output -> DeepSeekArchive.writeTo(output, listOf(DeepSeekArchive.Session(header, tree)),
            attachmentSource = { error("Fixture images are inline") }, readMessage = { id -> messages.first { it.id == id } }) { native ->
            writeBackupStream(native.writer().buffered(), 1, listOf(header), listOf(preset)) { _, sink -> messages.forEach { sink(it) } }
        } }
        ZipFile(target).use { zip ->
            assertEquals("manifest.json", zip.entries().nextElement().name)
            val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()).jsonObject
            assertEquals(3, manifest["export_format_version"]!!.jsonPrimitive.int)
            val index = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("chat_session_index.json")).reader().readText()).jsonArray
            val id = index.single().jsonObject["id"]!!.jsonPrimitive.content
            assertFalse(id.contains('/'))
            val session = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("chat_sessions/$id.json")).reader().readText()).jsonObject
            val exported = session["messages"]!!.jsonArray
            assertEquals(3, exported.size)
            exported.forEach { item -> val node = item.jsonObject
                val parent = node["parent_id"]
                if (parent != JsonNull) assertTrue(parent!!.jsonPrimitive.int < node["id"]!!.jsonPrimitive.int)
            }
            val fileEntry = exported.first().jsonObject["fragments"]!!.jsonArray.first().jsonObject["files"]!!.jsonArray.first().jsonObject
            val path = fileEntry["export_path"]!!.jsonPrimitive.content
            assertEquals("image", zip.getInputStream(zip.getEntry(path)).reader().readText())
            val restored = ChatArchive.readBackupJson { name -> zip.getEntry(name)?.let { zip.getInputStream(it).readBytes() } }!!
            assertEquals(header.copy(messages = messages), restored.conversations.single())
            assertEquals(listOf(preset), restored.presets)
        }
        val parsed = ExternalBackup.parse(target.readBytes())
        assertEquals(ExternalBackup.Source.SIMPLE_CHAT, parsed.source)
        assertEquals(messages, parsed.file.conversations.single().messages)
    }

    @Test fun `same role and assistant roots get compatibility bridges without changing native messages`() = runBlocking {
        val messages = listOf(BackupMessage("a", role = "assistant", content = "start", createdAt = 1),
            BackupMessage("b", parentId = "a", role = "assistant", content = "next", createdAt = 2))
        val header = BackupConversation("s", "t", "CREATIVE", createdAt = 1, updatedAt = 2)
        val tree = messages.map { MessageLight(it.id,it.parentId,0,true,it.role,it.status,it.createdAt) }
        val target = File.createTempFile("deepseek-export", ".zip")
        try {
            target.outputStream().use { output -> DeepSeekArchive.writeTo(output, listOf(DeepSeekArchive.Session(header,tree)),
                { error("no image") }, { id -> messages.first { it.id == id } }) { it.write("{}".toByteArray()) } }
            ZipFile(target).use { zip ->
                val session = zip.entries().asSequence().first { it.name.startsWith("chat_sessions/") }
                val exported = Json.parseToJsonElement(zip.getInputStream(session).reader().readText()).jsonObject["messages"]!!.jsonArray
                assertEquals(listOf("USER","ASSISTANT","USER","ASSISTANT"), exported.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            }
        } finally { target.delete() }
    }
}
