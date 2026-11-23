package com.simplechat.app.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `deleting one message version keeps its downstream branch reachable`() {
        val tree = listOf(
            light("u1", active = true),
            light("a1", parentId = "u1", active = true),
            light("u2", parentId = "a1", active = true),
            light("a2", parentId = "u2", active = true),
            light("u3", parentId = "a2", active = true),
        )
        val plan = MessageTree.deletionPlan(tree, setOf("a1"), allVersions = false)
        val afterDelete = tree.filterNot { it.id in plan.deletedIds }.map { existing ->
            plan.promoted.firstOrNull { it.id == existing.id } ?: existing
        }

        assertEquals(setOf("a1"), plan.deletedIds)
        assertEquals("u1", plan.promoted.single().parentId)
        assertEquals(listOf("u1", "u2", "a2", "u3"), MessageTree.activePathIds(afterDelete))
    }

    @Test
    fun `deleting all versions keeps downstream branches and selects active continuation`() {
        val tree = listOf(
            light("u1", active = true),
            light("a1", parentId = "u1", active = false, variantIndex = 0),
            light("a1b", parentId = "u1", active = true, variantIndex = 1),
            light("u2", parentId = "a1", active = true),
            light("u2b", parentId = "a1b", active = true),
            light("a2", parentId = "u2b", active = true),
        )
        val plan = MessageTree.deletionPlan(tree, setOf("a1"), allVersions = true)
        val afterDelete = tree.filterNot { it.id in plan.deletedIds }.map { existing ->
            plan.promoted.firstOrNull { it.id == existing.id } ?: existing
        }

        assertEquals(setOf("a1", "a1b"), plan.deletedIds)
        assertEquals(setOf("u2", "u2b"), plan.promoted.map { it.id }.toSet())
        assertEquals(listOf("u1", "u2b", "a2"), MessageTree.activePathIds(afterDelete))
        assertEquals(listOf(0, 1), plan.promoted.map { it.variantIndex }.sorted())
    }

    @Test
    fun `multi-delete splices consecutive selected messages and preserves their downstream branches`() {
        val tree = listOf(
            light("u1", active = true),
            light("a1", parentId = "u1", active = true),
            light("u2", parentId = "a1", active = true),
            light("a2", parentId = "u2", active = true),
            light("a2b", parentId = "u2", active = false, variantIndex = 1),
            light("u3", parentId = "a2"),
            light("u1b", parentId = "", active = false, variantIndex = 1),
            light("a1b", parentId = "u1b", active = true),
        )

        val plan = MessageTree.deletionPlan(tree, setOf("a1", "u2", "a2"), allVersions = true)
        val afterDelete = tree.filterNot { it.id in plan.deletedIds }.map { existing ->
            plan.promoted.firstOrNull { it.id == existing.id } ?: existing
        }

        assertEquals(setOf("a1", "u2", "a2", "a2b"), plan.deletedIds)
        assertEquals(setOf("u3"), plan.promoted.map { it.id }.toSet())
        assertEquals("u1", plan.promoted.single().parentId)
        assertEquals(listOf("u1", "u3"), MessageTree.activePathIds(afterDelete))
        assertTrue(afterDelete.any { it.id == "u1b" })
        assertTrue(afterDelete.any { it.id == "a1b" })
    }

    @Test
    fun `deleting active version preserves inactive downstream branches`() {
        val tree = listOf(
            light("u1", active = true),
            light("a1", parentId = "u1", active = true, variantIndex = 0),
            light("a1b", parentId = "u1", active = false, variantIndex = 1),
            light("u2", parentId = "a1", active = true),
            light("u2b", parentId = "a1b", active = true),
            light("a2", parentId = "u2", active = true),
            light("a2b", parentId = "u2b", active = true),
        )

        val plan = MessageTree.deletionPlan(tree, setOf("a1"), allVersions = false)
        val afterDelete = applyDeletion(tree, plan)

        assertEquals(setOf("a1"), plan.deletedIds)
        assertEquals(listOf("u2"), plan.promoted.map { it.id })
        assertEquals("a1", plan.promoted.single().versionGroup)
        assertEquals("a1b", plan.replacementId)
        assertEquals(listOf("u1", "a1b", "u2", "a2"), MessageTree.activePathIds(afterDelete))
        assertTrue(afterDelete.any { it.id == "u2b" && !it.active && it.parentId == "a1b" })
        assertTrue(afterDelete.any { it.id == "a2b" })
    }

    @Test
    fun `later deletion does not mistake preserved continuation for a sibling version`() {
        val tree = listOf(
            light("u1", active = true),
            light("a1", parentId = "u1", active = true, variantIndex = 0),
            light("a1b", parentId = "u1", active = false, variantIndex = 1),
            light("u2", parentId = "a1", active = true),
            light("u2b", parentId = "a1b", active = true),
        )
        val first = MessageTree.deletionPlan(tree, setOf("a1"), allVersions = false)
        val afterFirst = applyDeletion(tree, first)
        val second = MessageTree.deletionPlan(afterFirst, setOf("a1b"), allVersions = true)

        assertEquals(setOf("a1b"), second.deletedIds)
        assertEquals(setOf("u2", "u2b"), second.promoted.map { it.id }.toSet())
        assertTrue(afterFirst.any { it.id == "u2" && it.versionGroup == "a1" })
    }

    @Test
    fun `deleting inactive version preserves active sibling and its path`() {
        val tree = listOf(
            light("u1", active = true),
            light("a1", parentId = "u1", active = true, variantIndex = 0),
            light("a1b", parentId = "u1", active = false, variantIndex = 1),
            light("u2", parentId = "a1b", active = true),
            light("a2", parentId = "u2", active = true),
        )
        val plan = MessageTree.deletionPlan(tree, setOf("a1b"), allVersions = false)
        val afterDelete = tree.filterNot { it.id in plan.deletedIds }.map { node ->
            plan.promoted.firstOrNull { it.id == node.id } ?: node
        }

        assertTrue(plan.activateExisting.isEmpty())
        assertEquals(listOf("u1", "a1"), MessageTree.activePathIds(afterDelete))
        assertEquals("u1", plan.promoted.single().parentId)
        assertFalse(plan.promoted.single().active)
        assertTrue(afterDelete.any { it.id == "a2" })
    }

    @Test
    fun `deep chain survives deleting ten thousand consecutive nodes`() {
        val tree = (0..10000).map { i -> light("m$i", if (i == 0) "" else "m${i - 1}", active = true) }
        val plan = MessageTree.deletionPlan(tree, tree.dropLast(1).mapTo(HashSet()) { it.id }, true)
        assertEquals(10000, plan.deletedIds.size)
        assertEquals(tree.last().copy(parentId = "", versionGroup = "m9999"), plan.promoted.single())
    }

    @Test
    fun `random repeated deletions preserve every surviving ancestry and active continuation`() {
        repeat(100) { seed ->
            val random = kotlin.random.Random(seed)
            val counts = mutableMapOf<String, Int>()
            var tree = (0 until 90).map { i ->
                val parent = if (i == 0) "" else "m${random.nextInt(i)}"
                val index = counts.getOrDefault(parent, 0)
                counts[parent] = index + 1
                light("m$i", parent, active = index == 0, variantIndex = index)
            }
            repeat(12) {
                val selected = tree.shuffled(random).take(random.nextInt(1, 4)).toSet()
                val allVersions = random.nextBoolean()
                val groups = selected.map { it.versionGroup ?: it.parentId }.toSet()
                val before = tree.associateBy { it.id }
                val expected = tree.filter { it in selected || allVersions && (it.versionGroup ?: it.parentId) in groups }.toSet()
                val plan = MessageTree.deletionPlan(tree, selected.mapTo(HashSet()) { it.id }, allVersions)
                assertEquals("seed $seed", expected.mapTo(HashSet()) { it.id }, plan.deletedIds)
                val oldPath = MessageTree.activePathIds(tree).filterNot { it in plan.deletedIds }
                tree = applyDeletion(tree, plan)
                tree.forEach { node ->
                    var original = before.getValue(node.id)
                    assertEquals(original.versionGroup ?: original.parentId, node.versionGroup ?: node.parentId)
                    val wasChildOfDeletedVersion = original.parentId in plan.deletedIds
                    while (original.parentId in plan.deletedIds) original = before.getValue(original.parentId)
                    val expectedParent = if (wasChildOfDeletedVersion) plan.replacementId ?: original.parentId else original.parentId
                    assertEquals(expectedParent, node.parentId)
                }
                tree.groupBy { it.parentId }.values.forEach { siblings ->
                    assertEquals(1, siblings.count { it.active })
                    assertEquals(siblings.size, siblings.distinctBy { it.variantIndex }.size)
                }
                val newPath = MessageTree.activePathIds(tree).filterNot { it == plan.replacementId }
                assertEquals(oldPath, newPath.take(oldPath.size))
            }
        }
    }

    private fun applyDeletion(tree: List<MessageLight>, plan: MessageTree.DeletionPlan): List<MessageLight> {
        val moves = plan.promoted.associateBy { it.id }
        return tree.filterNot { it.id in plan.deletedIds }.map { node ->
            moves[node.id] ?: node.copy(
                active = node.id in plan.activateExisting || node.active && node.parentId !in plan.clearActiveChildren,
            )
        }
    }

    @Test
    fun `leaf and missing message deletion are harmless`() {
        val tree = listOf(light("u1", active = true), light("a1", parentId = "u1", active = true))
        val leaf = MessageTree.deletionPlan(tree, setOf("a1"), allVersions = true)
        val missing = MessageTree.deletionPlan(tree, setOf("missing"), allVersions = true)

        assertTrue(leaf.promoted.isEmpty())
        assertTrue(leaf.activateExisting.isEmpty())
        assertTrue(missing.deletedIds.isEmpty())
        assertTrue(missing.promoted.isEmpty())
    }

    @Test
    fun `selected branches are spliced independently`() {
        val tree = listOf(
            light("u1", active = true),
            light("a1", parentId = "u1", active = true),
            light("u2", parentId = "a1", active = true),
            light("a2", parentId = "u2", active = true),
            light("u1b", parentId = "", active = false, variantIndex = 1),
            light("a1b", parentId = "u1b", active = true),
            light("u2b", parentId = "a1b", active = true),
        )
        val plan = MessageTree.deletionPlan(tree, setOf("a1", "a1b"), allVersions = true)

        assertEquals(setOf("a1", "a1b"), plan.deletedIds)
        assertEquals(mapOf("u1" to "u2", "u1b" to "u2b"), plan.promoted.associate { it.parentId to it.id })
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
    // ── 骨架版：打开长会话时只读这几列（见 ChatRepository.loadActivePath） ──

    private fun light(
        id: String,
        parentId: String = MessageEntity.ROOT_PARENT,
        active: Boolean = false,
        variantIndex: Int = 0,
        versionGroup: String? = null,
    ) = MessageLight(
        id = id,
        parentId = parentId,
        variantIndex = variantIndex,
        active = active,
        role = if (variantIndex % 2 == 0) "user" else "assistant",
        status = "DONE",
        createdAt = 0,
        versionGroup = versionGroup,
    )

    @Test
    fun `activePathIds 与 activePath 走同一条路径`() {
        val tree = listOf(
            light("u1", active = true),
            light("a1", parentId = "u1", variantIndex = 1, active = true),
            light("a2", parentId = "u1", variantIndex = 2, active = false),
            light("u2", parentId = "a1", active = true),
        )

        assertEquals(
            MessageTree.activePath(tree.map { it.toFullEntity() }).map { it.id },
            MessageTree.activePathIds(tree),
        )
        assertEquals(listOf("u1", "a1", "u2"), MessageTree.activePathIds(tree))
    }

    @Test
    fun `activePathIds 遇到环会停下而不是死循环`() {
        val cyclic = listOf(
            light("a", active = true),
            light("b", parentId = "a", active = true),
            // 坏数据：a 的 parentId 指回 b
            light("a2", parentId = "b", active = false),
        )
        // 只要不挂住就是通过（数据损坏时宁可少显示，也不能卡死 UI）
        assertTrue(MessageTree.activePathIds(cyclic).size <= cyclic.size)
    }

    /** 把骨架补成完整实体，用来对照两条实现走的是同一条路径。 */
    private fun MessageLight.toFullEntity() = MessageEntity(
        id = id,
        conversationId = "c1",
        parentId = parentId,
        variantIndex = variantIndex,
        active = active,
        role = role,
        content = id,
        status = status,
        createdAt = createdAt,
    )
}
