package com.simplechat.app.data.archive

import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.BackupFile
import com.simplechat.app.data.BackupMessage
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZIP 归档的往返。
 *
 * 备份这一层最怕的是"能导出、导不回来" —— 用户在换机那一刻才发现，而原机可能已经清了。
 * 所以这里把「写出去 → 读回来 → 内容逐字相等」整条链路钉一遍。
 */
class ChatArchiveTest {

    private fun sampleBackup() = BackupFile(
        exportedAt = 1_700_000_000_000,
        conversations = listOf(
            BackupConversation(
                id = "c1",
                title = "雨夜/序章: 第一稿",
                mode = "CREATIVE",
                createdAt = 1_700_000_000_000,
                updatedAt = 1_700_000_001_000,
                messages = listOf(
                    BackupMessage(
                        id = "m1",
                        role = "user",
                        content = "开始吧",
                        createdAt = 1_700_000_000_500,
                    ),
                ),
            ),
        ),
    )

    private fun entriesOf(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
            }
        }
        return out
    }

    @Test
    fun `导出包含 manifest 与 backup 两个条目`() {
        val bytes = ChatArchive.write(sampleBackup(), emptyList())
        val entries = entriesOf(bytes)
        assertTrue(entries.containsKey(ChatArchive.MANIFEST))
        assertTrue(entries.containsKey(ChatArchive.BACKUP))
    }

    @Test
    fun `manifest 带格式与版本`() {
        val entries = entriesOf(ChatArchive.write(sampleBackup(), emptyList()))
        val manifest = entries.getValue(ChatArchive.MANIFEST).decodeToString()
        assertTrue(manifest.contains(ChatArchive.FORMAT))
        assertTrue(manifest.contains("\"version\":${ChatArchive.VERSION}"))
        assertTrue(manifest.contains("\"conversations\":1"))
    }

    @Test
    fun `写出去再读回来内容一致`() {
        val entries = entriesOf(ChatArchive.write(sampleBackup(), emptyList()))
        val restored = ChatArchive.readBackupJson { entries[it] }
        assertNotNull(restored)
        assertEquals("c1", restored!!.conversations.single().id)
        assertEquals("开始吧", restored.conversations.single().messages.single().content)
        assertEquals(1_700_000_000_000, restored.exportedAt)
    }

    @Test
    fun `Markdown 文稿进 markdown 目录`() {
        val bytes = ChatArchive.write(
            sampleBackup(),
            listOf(ChatArchive.Document("0001-雨夜.md", "# 雨夜\n\n正文")),
        )
        val entries = entriesOf(bytes)
        assertEquals("# 雨夜\n\n正文", entries.getValue("markdown/0001-雨夜.md").decodeToString())
    }

    @Test
    fun `不是本应用的归档时返回 null`() {
        // Chatbox 的 manifest 也叫 manifest.json，但 format 不同 —— 必须交给对方的分支
        val foreign = """{"format":"chatbox-backup"}""".toByteArray()
        assertNull(ChatArchive.readBackupJson { if (it == ChatArchive.MANIFEST) foreign else null })
    }

    @Test
    fun `缺少 backup_json 时返回 null 而不是抛异常`() {
        val entries = entriesOf(ChatArchive.write(sampleBackup(), emptyList())).toMutableMap()
        entries.remove(ChatArchive.BACKUP)
        assertNull(ChatArchive.readBackupJson { entries[it] })
    }

    @Test
    fun `损坏的 backup_json 返回 null`() {
        val entries = entriesOf(ChatArchive.write(sampleBackup(), emptyList())).toMutableMap()
        entries[ChatArchive.BACKUP] = "{oops".toByteArray()
        assertNull(ChatArchive.readBackupJson { entries[it] })
    }

    // ── 文稿文件名 ────────────────────────────────────────

    @Test
    fun `标题里的非法字符被替换`() {
        assertEquals("0001-雨夜_序章_ 第一稿.md", ChatArchive.documentName(1, "雨夜/序章: 第一稿"))
    }

    @Test
    fun `序号补到四位`() {
        assertEquals("0007-标题.md", ChatArchive.documentName(7, "标题"))
        assertEquals("0123-标题.md", ChatArchive.documentName(123, "标题"))
    }

    @Test
    fun `空标题有兜底名`() {
        assertEquals("0001-conversation.md", ChatArchive.documentName(1, "   "))
        assertEquals("0001-conversation.md", ChatArchive.documentName(1, "///"))
    }

    @Test
    fun `超长标题被截断`() {
        val name = ChatArchive.documentName(1, "长".repeat(200))
        assertTrue(name.length < 80)
    }

    @Test
    fun `标题首尾的点号与空格被去掉`() {
        assertEquals("0001-标题.md", ChatArchive.documentName(1, "..标题.. "))
    }
}
