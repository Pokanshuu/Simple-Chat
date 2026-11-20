package com.simplechat.app.data.archive

import com.simplechat.app.data.*
import com.simplechat.app.data.import.ExternalBackup
import com.simplechat.app.db.MessageLight
import com.simplechat.app.net.*
import com.simplechat.app.data.import.DeepSeekHistory
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DeepSeekArchiveTest {
    @Test fun `legacy extension still restores the SimpleChat backup`() {
        val backup = BackupFile(exportedAt = 1, conversations = emptyList())
        val entries = mapOf(
            "manifest.json" to """{"export_format_version":3,"simplechat_extension_version":1,"simplechat_backup":"user.json"}""",
            "user.json" to Json.encodeToString(BackupFile.serializer(), backup),
        )
        assertEquals(backup, ChatArchive.readBackupJson { entries[it]?.toByteArray() })
    }

    @Test fun `official v3 archive contains only official entries and preserves branch and image data`() = runBlocking {
        val image = Attachment(Attachment.Kind.IMAGE, "测试.png", "image/png", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMB/6X+QX0AAAAASUVORK5CYII=")
        val timeline = listOf(
            ReplyBlock.ToolUse("search", "deepseek", "web_search", "search", queries = listOf("query")),
            ReplyBlock.SearchResult("search", "web_search", listOf(WebSource("https://example.com", "Source"))),
            ReplyBlock.ToolUse("open", "deepseek", "deep_search", "open_page", url = "https://example.com"),
            ReplyBlock.FetchResult("open", "deep_search", "https://example.com", "Opened",
                snippet = "Page excerpt", siteName = "Example"),
            ReplyBlock.ToolUse("find", "deepseek", "deep_search", "find_in_page", pattern = "needle"),
            ReplyBlock.FetchResult("find", "deep_search"),
            ReplyBlock.ToolUse("fetch", "deepseek", "read_link", "open_page", url = "https://example.com"),
            ReplyBlock.FetchResult("fetch", "read_link", "https://example.com", "Source", signature = "sealed"),
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
        val tree = messages.reversed().map { MessageLight(it.id, it.parentId, it.variantIndex, it.active, it.role, it.status, it.createdAt) }
        val target = File("build/test-fixtures/deepseek-compatible.zip").apply { parentFile?.mkdirs() }
        target.outputStream().use { output -> DeepSeekArchive.writeTo(output, listOf(DeepSeekArchive.Session(header, tree)),
            attachmentSource = { error("Fixture images are inline") }, readMessage = { id -> messages.first { it.id == id } }) }
        ZipFile(target).use { zip ->
            assertEquals("manifest.json", zip.entries().nextElement().name)
            val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()).jsonObject
            assertEquals(3, manifest["export_format_version"]!!.jsonPrimitive.int)
            assertEquals(setOf("export_format_version"), manifest.keys)
            assertNull(zip.getEntry("user.json"))
            val index = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("chat_session_index.json")).reader().readText()).jsonArray
            val id = index.single().jsonObject["id"]!!.jsonPrimitive.content
            assertFalse(id.contains('/'))
            val session = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("chat_sessions/$id.json")).reader().readText()).jsonObject
            val exported = session["messages"]!!.jsonArray
            assertEquals(3, exported.size)
            val current = session.getValue("current_message_id")
            val selected = exported.single { it.jsonObject["id"] == current }.jsonObject
            assertEquals("Answer", selected.getValue("fragments").jsonArray.single {
                it.jsonObject["type"]?.jsonPrimitive?.content == "RESPONSE"
            }.jsonObject["content"]?.jsonPrimitive?.content)
            exported.forEach { item -> val node = item.jsonObject
                val parent = node["parent_id"]
                if (parent != JsonNull) assertTrue(parent!!.jsonPrimitive.int < node["id"]!!.jsonPrimitive.int)
            }
            val fileEntry = exported.first().jsonObject["fragments"]!!.jsonArray.first().jsonObject["files"]!!.jsonArray.first().jsonObject
            val path = fileEntry["export_path"]!!.jsonPrimitive.content
            assertEquals("测试.png", fileEntry["file_name"]!!.jsonPrimitive.content)
            val original = java.util.Base64.getDecoder().decode(image.data)
            assertEquals(original.size, fileEntry["file_size"]!!.jsonPrimitive.int)
            assertArrayEquals(original, zip.getInputStream(zip.getEntry(path)).use { it.readBytes() })
            assertEquals(1, exported.first().jsonObject["fragments"]!!.jsonArray.first().jsonObject["files"]!!.jsonArray.size)
            assertEquals(setOf("manifest.json", "chat_session_index.json", "chat_sessions/$id.json", path), zip.entries().asSequence().map { it.name }.toSet())
        }
        ZipFile(target).use { zip ->
            assertTrue(DeepSeekHistory.detect(zip))
            val naming = ExternalBackup.ImportNaming(conversationFallbackTitle = { "Untitled" })
            val imported = mutableListOf<BackupMessage>()
            val stream = checkNotNull(DeepSeekHistory.open(zip, naming))
            assertTrue(stream.indexVerified)
            stream.forEachSession { it.forEachMessage { message -> imported += message } }
            assertEquals(3, imported.size)
            assertEquals("Question with complete text", imported.first().content)
            assertEquals(image.data, imported.first().attachments.single().data)
            assertEquals("测试.png", imported.first().attachments.single().name)
            val answer = imported.single { it.content == "Answer" }
            assertEquals(2500L, answer.thinkingMs)
            assertTrue(answer.active)
            assertEquals("Source", answer.timeline.filterIsInstance<ReplyBlock.SearchResult>().single().results.single().title)
            assertEquals(listOf("query"), answer.timeline.filterIsInstance<ReplyBlock.ToolUse>().single { it.action == "search" }.queries)
            assertEquals("sealed", answer.timeline.filterIsInstance<ReplyBlock.FetchResult>().single { it.kind == "archive_read_link" }.signature)
            assertTrue(answer.timeline.filterIsInstance<ReplyBlock.ToolUse>().any { it.pattern == "needle" })
            val opened = answer.timeline.filterIsInstance<ReplyBlock.FetchResult>().single { it.title == "Opened" }
            assertEquals("Page excerpt", opened.snippet)
            assertEquals("Example", opened.siteName)
            assertFalse(imported.single { it.content == "Other answer" }.active)
        }
    }

    @Test fun `same role and assistant roots get compatibility bridges`() = runBlocking {
        val messages = listOf(BackupMessage("a", role = "assistant", content = "start", createdAt = 1),
            BackupMessage("b", parentId = "a", role = "assistant", content = "next", createdAt = 2))
        val header = BackupConversation("s", "t", "CREATIVE", createdAt = 1, updatedAt = 2)
        val tree = messages.map { MessageLight(it.id,it.parentId,0,true,it.role,it.status,it.createdAt) }
        val target = File.createTempFile("deepseek-export", ".zip")
        try {
            target.outputStream().use { output -> DeepSeekArchive.writeTo(output, listOf(DeepSeekArchive.Session(header,tree)),
                { error("no image") }, { id -> messages.first { it.id == id } }) }
            ZipFile(target).use { zip ->
                val session = zip.entries().asSequence().first { it.name.startsWith("chat_sessions/") }
                val exported = Json.parseToJsonElement(zip.getInputStream(session).reader().readText()).jsonObject["messages"]!!.jsonArray
                assertEquals(listOf("USER","ASSISTANT","USER","ASSISTANT"), exported.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            }
        } finally { target.delete() }
    }
}
