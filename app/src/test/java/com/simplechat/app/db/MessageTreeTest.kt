package com.simplechat.app.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 树的行走逻辑 —— 一旦错就是"顺序全乱 / 分支切错"，从现象上极难定位。
 *
 * 这里的用例同时是**规格说明**：把"编辑重发 = 挂个新兄弟，旧子树原样留着"
 * 这件事写成可执行的断言。
 */
class MessageTreeTest {

    private fun node(
        id: String,
        parentId: String = MessageEntity.ROOT_PARENT,
        variantIndex: Int = 0,
        active: Boolean = false,
        content: String = id,
    ) = MessageEntity(
        id = id,
        conversationId = "c1",
        parentId = parentId,
        variantIndex = variantIndex,
        active = active,
        role = if (variantIndex % 2 == 0) "user" else "assistant",
        content = content,
        createdAt = 0,
    )

    private fun List<MessageEntity>.ids() = map { it.id }

    @Test
    fun `walks the active chain from root to leaf`() {
        val tree = listOf(
            node("u1", active = true),
            node("a1", parentId = "u1", active = true),
            node("u2", parentId = "a1", active = true),
            node("a2", parentId = "u2", active = true),
        )

        assertEquals(listOf("u1", "a1", "u2", "a2"), MessageTree.activePath(tree).ids())
    }

    @Test
    fun `inactive siblings are not on the path`() {
        val tree = listOf(
            node("u1", active = true),
            node("a1", parentId = "u1", variantIndex = 0, active = false),
            node("a2", parentId = "u1", variantIndex = 1, active = true),
        )

        assertEquals(listOf("u1", "a2"), MessageTree.activePath(tree).ids())
    }

    @Test
    fun `editing a message keeps the old subtree reachable`() {
        // 在 u2 上「编辑并重发」：u2' 是新兄弟，旧 u2 的子树（a2、u3）原样留着
        val tree = listOf(
            node("u1", active = true),
            node("a1", parentId = "u1", active = true),
            node("u2", parentId = "a1", variantIndex = 0, active = false),
            node("a2", parentId = "u2", active = true),
            node("u3", parentId = "a2", active = true),
            node("u2b", parentId = "a1", variantIndex = 1, active = true),
            node("a2b", parentId = "u2b", active = true),
        )

        // 当前走的是新分支
        assertEquals(
            listOf("u1", "a1", "u2b", "a2b"),
            MessageTree.activePath(tree).ids(),
        )

        // 旧分支一条没少，切回 u2 就能看到整条
        val switched = tree.map { it.copy(active = it.id == "u1" || it.id == "a1" || it.id == "u2" || it.id == "a2" || it.id == "u3") }
        assertEquals(
            listOf("u1", "a1", "u2", "a2", "u3"),
            MessageTree.activePath(switched).ids(),
        )
    }

    @Test
    fun `switching a variant swaps the whole downstream`() {
        // i1 下有两个版本：v0 引出 [a1]，v1 引出 [b1]
        val tree = listOf(
            node("root", active = true),
            node("x1", parentId = "root", variantIndex = 1, active = true),
            node("y1", parentId = "x1", active = true),
        )

        assertEquals(
            listOf("root", "x1", "y1"),
            MessageTree.activePath(tree).ids(),
        )
    }

    @Test
    fun `sibling counts are keyed by parent`() {
        val tree = listOf(
            node("u1"),
            node("a1", parentId = "u1", variantIndex = 0),
            node("a2", parentId = "u1", variantIndex = 1),
            node("a3", parentId = "u1", variantIndex = 2),
            node("u2", parentId = "a1"),
        )

        val counts = MessageTree.siblingCounts(tree)

        assertEquals(3, counts["u1"])
        assertEquals(1, counts["a1"])
        assertEquals(1, counts[MessageEntity.ROOT_PARENT])
    }

    @Test
    fun `subtree includes all descendants across branches`() {
        val tree = listOf(
            node("u1", active = true),
            node("a1", parentId = "u1"),
            node("a1b", parentId = "u1"),          // 兄弟，也是 u1 的子树
            node("u2", parentId = "a1"),
            node("u3", parentId = "a1b"),
        )

        val doomed = MessageTree.subtreeIds(tree, "u1").toSet()

        assertEquals(setOf("u1", "a1", "a1b", "u2", "u3"), doomed)
    }

    @Test
    fun `subtree of a leaf is just itself`() {
        val tree = listOf(
            node("u1", active = true),
            node("a1", parentId = "u1", active = true),
        )

        assertEquals(listOf("a1"), MessageTree.subtreeIds(tree, "a1"))
    }

    @Test
    fun `empty conversation yields an empty path`() {
        assertTrue(MessageTree.activePath(emptyList()).isEmpty())
    }

    @Test
    fun `a broken cycle does not hang the walker`() {
        // 数据损坏时也必须能返回，不能用死循环把 UI 线程挂住
        val tree = listOf(
            node("a", parentId = "b", active = true),
            node("b", parentId = "a", active = true),
        )

        val path = MessageTree.activePath(tree)

        assertTrue("应当及时停下，实际走了 ${path.size} 步", path.size <= 2)
    }

    // ── 「从这里新建对话」（§21）────────────────────────────

    @Test
    fun `pathTo cuts the active path at the given message`() {
        val tree = listOf(
            node("u1", active = true),
            node("a1", parentId = "u1", active = true),
            node("u2", parentId = "a1", active = true),
            node("a2", parentId = "u2", active = true),
        )

        assertEquals(
            listOf("u1", "a1", "u2"),
            MessageTree.pathTo(tree, "u2").ids(),
        )
    }

    @Test
    fun `pathTo of the first message is just that message`() {
        val tree = listOf(
            node("u1", active = true),
            node("a1", parentId = "u1", active = true),
        )

        assertEquals(listOf("u1"), MessageTree.pathTo(tree, "u1").ids())
    }

    @Test
    fun `pathTo ignores messages that are not on the active path`() {
        // u2 是旧分支上的，当前 active 走的是 u2b —— 不该复制到它
        val tree = listOf(
            node("u1", active = true),
            node("a1", parentId = "u1", active = true),
            node("u2", parentId = "a1", variantIndex = 0, active = false),
            node("u2b", parentId = "a1", variantIndex = 1, active = true),
        )

        assertTrue(MessageTree.pathTo(tree, "u2").isEmpty())
    }

    @Test
    fun `fork rewires the parent chain onto fresh ids`() {
        val path = listOf(
            node("u1", active = true),
            node("a1", parentId = "u1", active = true),
            node("u2", parentId = "a1", active = true),
        )
        var n = 0

        val forked = MessageTree.forkMessages(path, conversationId = "new") { "n${n++}" }

        // id 全新，parentId 指向前一条的**新** id，首条指根
        assertEquals(listOf("n0", "n1", "n2"), forked.ids())
        assertEquals(
            listOf(MessageEntity.ROOT_PARENT, "n0", "n1"),
            forked.map { it.parentId },
        )
        assertTrue("全部应指向新会话", forked.all { it.conversationId == "new" })
    }

    @Test
    fun `fork flattens variants and keeps content`() {
        // u2 是 1/2 里的第 2 版；复制过去应该变成单版本
        val path = listOf(
            node("u1", active = true, content = "第一句"),
            node("u2", parentId = "u1", variantIndex = 1, active = true, content = "第二句"),
        )

        val forked = MessageTree.forkMessages(path, "new") { "x" + java.util.UUID.randomUUID() }

        assertTrue(forked.all { it.variantIndex == 0 && it.active })
        assertEquals(listOf("第一句", "第二句"), forked.map { it.content })
    }

    @Test
    fun `forked path is itself a valid active path`() {
        // 复制出来的东西必须能被 activePath 走通 —— 否则新会话打开是空白的
        val path = listOf(
            node("u1", active = true),
            node("a1", parentId = "u1", active = true),
            node("u2", parentId = "a1", active = true),
        )
        var n = 0

        val forked = MessageTree.forkMessages(path, "new") { "n${n++}" }

        assertEquals(forked.ids(), MessageTree.activePath(forked).ids())
    }

    @Test
    fun `forking an empty path yields nothing`() {
        assertTrue(MessageTree.forkMessages(emptyList(), "new") { "x" }.isEmpty())
    }
}
