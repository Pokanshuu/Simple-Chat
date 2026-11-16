package com.simplechat.app.data

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件的**两种存储形态**。
 *
 * 落盘方案的关键前提是"JSON 自描述、不需要数据库迁移" —— 老数据（只有 `data`）
 * 必须原样读得出来，新数据（只有 `path`）也必须读得出来。这条断言就是那个前提。
 */
class AttachmentFilesTest {

    @Test fun `backup attachment paths cannot escape private attachment directory`() {
        val root = java.io.File(System.getProperty("java.io.tmpdir"), "attachment-root")
        listOf(null, "", ".", "..", "../settings", "..\\settings", "/data/settings", "C:\\settings", "a\u0000b")
            .forEach { assertNull(attachmentFile(root, it)) }
        assertEquals(java.io.File(root, "safe.png").canonicalFile, attachmentFile(root, "safe.png"))
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Test
    fun `老数据只有 base64 时照旧可读`() {
        val raw = """[{"kind":"IMAGE","name":"图","mime":"image/jpeg","data":"/9j/4AAQ","width":10,"height":20}]"""
        val list = json.decodeFromString<List<Attachment>>(raw)
        val attachment = list.single()
        assertEquals("/9j/4AAQ", attachment.data)
        assertNull(attachment.path)
        assertTrue(!attachment.isStored)
    }

    @Test
    fun `落盘的附件只认 path`() {
        val raw = """[{"kind":"IMAGE","name":"图","mime":"image/png","data":"","width":10,"height":20,"path":"abc.png"}]"""
        val list = json.decodeFromString<List<Attachment>>(raw)
        val attachment = list.single()
        assertTrue(attachment.isStored)
        assertEquals("abc.png", attachment.path)
        // 落盘时 data 为空：内联大小算出来是 0，界面必须改问文件系统（见 AttachmentFiles.sizeOf）
        assertEquals(0, attachment.sizeBytes)
    }

    @Test
    fun `文本附件仍然内联`() {
        val attachment = Attachment(kind = Attachment.Kind.TEXT, name = "a.md", data = "正文")
        assertTrue(!attachment.isStored)
        assertEquals(6, attachment.sizeBytes)
    }
}
