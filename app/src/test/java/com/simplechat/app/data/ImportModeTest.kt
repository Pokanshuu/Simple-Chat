package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入策略里两段**纯逻辑**：副本命名与换 id。
 *
 * 这两处判错的后果都是"数据被覆盖"，而覆盖是无声的 —— 界面上只会看到会话少了一条，
 * 不会有任何报错。所以按纯函数钉住。
 */
class ImportModeTest {

    private val suffix = "（副本）"
    private val suffixN: (Int) -> String = { n -> "（副本 $n）" }

    // ── 副本命名 ────────────────────────────────────────────

    @Test
    fun `标题没被占用时原样返回`() {
        assertEquals("周末计划", uniqueCopyTitle("周末计划", setOf("别的"), suffix, suffixN))
    }

    @Test
    fun `标题撞名时加副本后缀`() {
        assertEquals(
            "周末计划（副本）",
            uniqueCopyTitle("周末计划", setOf("周末计划"), suffix, suffixN),
        )
    }

    @Test
    fun `副本也撞名时从 2 开始编号`() {
        val taken = setOf("周末计划", "周末计划（副本）")
        assertEquals("周末计划（副本 2）", uniqueCopyTitle("周末计划", taken, suffix, suffixN))
    }

    @Test
    fun `连续编号跳过已被占用的序号`() {
        val taken = setOf("a", "a（副本）", "a（副本 2）", "a（副本 3）")
        assertEquals("a（副本 4）", uniqueCopyTitle("a", taken, suffix, suffixN))
    }

    // ── 换 id ──────────────────────────────────────────────

    private fun conversation(id: String) = BackupConversation(
        id = id,
        title = "标题",
        mode = "CREATIVE",
        createdAt = 1_000L,
        updatedAt = 2_000L,
        messages = listOf(
            BackupMessage(
                id = "$id-u1",
                parentId = "",
                role = "USER",
                content = "问题",
                createdAt = 1_000L,
            ),
            BackupMessage(
                id = "$id-a1",
                parentId = "$id-u1",
                role = "ASSISTANT",
                content = "回答",
                createdAt = 1_500L,
            ),
        ),
        compactions = listOf(
            BackupCompaction(
                id = "$id-cmp",
                throughMessageId = "$id-a1",
                summary = "前情",
                createdAt = 1_600L,
                updatedAt = 1_700L,
            ),
        ),
    )

    @Test
    fun `换 id 时会话与消息一起换，父子引用同步重映射`() {
        val fresh = conversation("old").withFreshIds("new")

        assertEquals("new", fresh.id)
        assertEquals(listOf("new#old-u1", "new#old-a1"), fresh.messages.map { it.id })
        // 根消息的 parentId 保持空串（不是 remap 成 "new#"，那样会指向不存在的消息）
        assertEquals("", fresh.messages[0].parentId)
        assertEquals("new#old-u1", fresh.messages[1].parentId)
    }

    @Test
    fun `换 id 不动内容与时间`() {
        val fresh = conversation("old").withFreshIds("new")
        assertEquals("标题", fresh.title)
        assertEquals(listOf("问题", "回答"), fresh.messages.map { it.content })
        assertEquals(1_000L, fresh.messages[0].createdAt)
        assertEquals(2_000L, fresh.updatedAt)
    }

    @Test
    fun `换 id 时压缩摘要的边界跟着消息一起换`() {
        val fresh = conversation("old").withFreshIds("new")

        val compaction = fresh.compactions.single()
        assertEquals("new#old-cmp", compaction.id)
        assertEquals("new#old-a1", compaction.throughMessageId)
        assertEquals("前情", compaction.summary)
        // 边界必须落在换过 id 的后一条消息上，否则导入后摘要永远不生效
        assertTrue(fresh.messages.any { it.id == compaction.throughMessageId })
    }

    @Test
    fun `换 id 之后不会与原来的会话撞`() {
        val old = conversation("same")
        val fresh = old.withFreshIds("same~1720000000000")
        assertNotEquals(old.id, fresh.id)
        assertTrue(fresh.messages.none { message -> old.messages.any { it.id == message.id } })
    }

    // ── 策略枚举 ────────────────────────────────────────────

    @Test
    fun `导入菜单三项之外多一个同步专用的按 id 更新模式`() {
        // 前三个是用户可见的导入菜单；SYNC_APPLY 只给多端同步用（按 id 原地更新），
        // 不暴露在菜单里 —— 它的身份判定（id）和 REPLACE_SAME（标题）是安全边界。
        assertEquals(
            listOf("MERGE", "REPLACE_SAME", "RENAME", "SYNC_APPLY"),
            ImportMode.entries.map { it.name },
        )
    }
}
