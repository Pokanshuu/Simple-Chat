package com.simplechat.app.data

import com.simplechat.app.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份格式的往返测试。
 *
 * 这些断言的意义在于**锁死字段名**：导出的 JSON 是给用户长期保存的资产，
 * 一旦改名，老备份就再也导不回来。改动字段名时这些测试必须先失败。
 */
class BackupTest {

    private fun sampleMessage(id: String, active: Boolean, variant: Int) = MessageEntity(
        id = id,
        conversationId = "c1",
        parentId = "m0",
        variantIndex = variant,
        active = active,
        role = "assistant",
        content = "正文",
        reasoning = "思考",
        status = "DONE",
        errorMessage = null,
        excluded = false,
        thinkingMs = 1200,
        ttftMs = 300,
        tps = 42.5f,
        createdAt = 1_700_000_000_000,
    )

    private fun sampleFile() = BackupFile(
        exportedAt = 1_700_000_000_000,
        conversations = listOf(
            BackupConversation(
                id = "c1",
                title = "一个会话",
                mode = "CREATIVE",
                provider = "DEEPSEEK",
                model = "deepseek-flash",
                systemPrompt = "保持第三人称",
                pinned = true,
                pinnedAt = 1_700_000_000_001,
                createdAt = 1_699_000_000_000,
                updatedAt = 1_700_000_000_000,
                messages = listOf(
                    sampleMessage("m1", active = false, variant = 0).toBackup(),
                    sampleMessage("m2", active = true, variant = 1).toBackup(),
                ),
            ),
        ),
        presets = listOf(
            BackupPreset(
                id = "p1",
                name = "慢节奏",
                content = "放慢",
                sortOrder = 0,
                createdAt = 1_699_000_000_000,
                updatedAt = 1_699_000_000_000,
            ),
        ),
    )

    @Test
    fun `round trips through json without losing anything`() {
        val original = sampleFile()

        val text = backupJson.encodeToString(original)
        val decoded = backupJson.decodeFromString<BackupFile>(text)

        assertEquals(original, decoded)
    }

    @Test
    fun `keeps every variant not just the active one`() {
        val decoded = backupJson.decodeFromString<BackupFile>(
            backupJson.encodeToString(sampleFile()),
        )

        val messages = decoded.conversations.single().messages
        assertEquals("同一 slot 的两个版本都必须导出", 2, messages.size)
        assertEquals(listOf(0, 1), messages.map { it.variantIndex })
        // 未激活的那个版本（m1）也必须留着，否则用户回不到旧版本
        assertEquals(listOf("m1", "m2"), messages.map { it.id })
        assertEquals(listOf("m2"), messages.filter { it.active }.map { it.id })
    }

    @Test
    fun `uses a stable format marker`() {
        // 导入时会校验这个字段；改名等于让所有老备份失效
        assertTrue(backupJson.encodeToString(sampleFile()).contains("\"simplechat-backup\""))
    }

    @Test
    fun `message entity survives the entity-backup-entity hop`() {
        val entity = sampleMessage("m9", active = true, variant = 4)

        val restored = entity.toBackup().toEntity("c1")

        assertEquals(entity, restored)
    }

    /**
     * 附件必须原样进备份、原样回来。
     *
     * 这条最要紧：图片只有备份里那一份拷贝 —— 丢了就真没了
     * （Markdown 文稿导出刻意不带图片数据）。
     */
    @Test
    fun `attachments survive the entity-backup-entity hop`() {
        val entity = sampleMessage("m10", active = true, variant = 0).copy(
            content = "",
            attachments = Attachments.encode(
                listOf(
                    Attachment(
                        kind = Attachment.Kind.IMAGE,
                        name = "shot.png",
                        mime = "image/jpeg",
                        data = "AAAA",
                        width = 100,
                        height = 50,
                    ),
                    Attachment(
                        kind = Attachment.Kind.TEXT,
                        name = "设定.md",
                        data = "# 世界观",
                    ),
                ),
            ),
        )

        val restored = entity.toBackup().toEntity("c1")

        assertEquals(entity, restored)
        assertEquals(2, Attachments.decode(restored.attachments).size)
    }

    @Test
    fun `old backups without the attachments field still load`() {
        // 刻意不写 attachments —— 模拟上一版的备份文件
        val text = backupJson.encodeToString(sampleFile())

        val decoded = backupJson.decodeFromString<BackupFile>(text)

        assertEquals(emptyList<Attachment>(), decoded.conversations.single().messages.first().attachments)
    }

    @Test
    fun `attachments are kept as a nested list in the exported json`() {
        val file = sampleFile()
        val withAttachment = file.copy(
            conversations = file.conversations.map { conversation ->
                conversation.copy(
                    messages = conversation.messages.map { message ->
                        message.copy(
                            attachments = listOf(
                                Attachment(
                                    kind = Attachment.Kind.TEXT,
                                    name = "设定.md",
                                    data = "# 世界观",
                                ),
                            ),
                        )
                    },
                )
            },
        )

        val text = backupJson.encodeToString(withAttachment)

        // 嵌套列表而不是"把 JSON 塞进字符串"—— 备份是给人看的，能读能 diff
        assertTrue(text.contains("\"attachments\""))
        assertTrue(text.contains("\"设定.md\""))

        val decoded = backupJson.decodeFromString<BackupFile>(text)
        assertEquals("设定.md", decoded.conversations.single().messages.first().attachments.single().name)
    }
}
