package com.simplechat.app.ui.chat

import com.simplechat.app.db.CompactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求序列的筛选规则。
 *
 * 输入是**已经走好的活跃路径**（顺序由 `MessageTree.activePath` 保证，
 * 见 `MessageTreeTest`），这里只负责挑哪些发出去。
 *
 * 这一层错了只会表现为"模型答非所问"，从现象上几乎不可能定位，
 * 所以把规则钉死。
 */
class RequestOrderingTest {

    private fun msg(
        id: String,
        parentId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus = MessageStatus.DONE,
        attachments: List<com.simplechat.app.data.Attachment> = emptyList(),
    ) = UiMessage(
        id = id,
        role = role,
        content = content,
        attachments = attachments,
        parentId = parentId,
        status = status,
    )

    /**
     * 只挂附件、没打字的用户消息**不能被丢掉**。
     *
     * 这是真实用法："丢一张图进来说『你看看这个』"。
     * 按 `content.isNotBlank()` 过滤会把它整条删掉，请求里只剩一串 assistant，
     * 服务端直接回 `Empty input messages` —— 现象是"发了消息但 AI 报错"。
     */
    @Test
    fun `user message with only attachments is kept`() {
        val path = listOf(
            msg(
                id = "u1",
                parentId = "",
                role = MessageRole.USER,
                content = "",
                attachments = listOf(
                    com.simplechat.app.data.Attachment(
                        kind = com.simplechat.app.data.Attachment.Kind.IMAGE,
                        name = "a.png",
                        data = "AAAA",
                    ),
                ),
            ),
        )

        assertEquals(listOf("u1"), orderedOutgoingMessages(path, null).map { it.id })
    }

    /** 页脚消息：既没正文也没附件 —— 这才该被丢掉。 */
    @Test
    fun `empty message without attachments is dropped`() {
        val path = listOf(
            msg("u1", "", MessageRole.USER, "你好"),
            msg("a1", "u1", MessageRole.ASSISTANT, "   "),
        )

        assertEquals(listOf("u1"), orderedOutgoingMessages(path, null).map { it.id })
    }

    /** 一条正常的 u/a 交替路径。 */
    private fun chain(n: Int): List<UiMessage> {
        val out = mutableListOf<UiMessage>()
        var parent = ""
        repeat(n) { index ->
            val id = "m$index"
            out += msg(
                id = id,
                parentId = parent,
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "c$index",
            )
            parent = id
        }
        return out
    }

    private fun List<UiMessage>.contents() = map { it.content }

    @Test
    fun `keeps path order as given`() {
        val path = chain(4)

        assertEquals(
            listOf("c0", "c1", "c2", "c3"),
            orderedOutgoingMessages(path, null).contents(),
        )
    }

    @Test
    fun `drops the streaming placeholder`() {
        val path = listOf(
            msg("u1", "", MessageRole.USER, "a"),
            msg("a1", "u1", MessageRole.ASSISTANT, "", MessageStatus.STREAMING),
        )

        assertEquals(listOf("a"), orderedOutgoingMessages(path, null).contents())
    }

    @Test
    fun `drops blank content`() {
        // 重新生成失败会留下空的 active 版本，不该把这一轮发出去
        val path = listOf(
            msg("u1", "", MessageRole.USER, "a"),
            msg("a1", "u1", MessageRole.ASSISTANT, "   "),
            msg("u2", "a1", MessageRole.USER, "b"),
        )

        assertEquals(listOf("a", "b"), orderedOutgoingMessages(path, null).contents())
    }

    @Test
    fun `never starts with an assistant message`() {
        val path = listOf(
            msg("a0", "", MessageRole.ASSISTANT, "orphan"),
            msg("u1", "a0", MessageRole.USER, "a"),
            msg("a1", "u1", MessageRole.ASSISTANT, "b"),
        )

        val result = orderedOutgoingMessages(path, null)

        assertEquals(MessageRole.USER, result.first().role)
        assertEquals(listOf("a", "b"), result.contents())
    }

    @Test
    fun `returns empty when there is no user message at all`() {
        val path = listOf(msg("a0", "", MessageRole.ASSISTANT, "orphan"))

        assertTrue(orderedOutgoingMessages(path, null).isEmpty())
    }

    @Test
    fun `compaction drops everything up to and including the covered node`() {
        val path = chain(6)

        assertEquals(
            listOf("c4", "c5"),
            orderedOutgoingMessages(path, compaction("m3")).contents(),
        )
    }

    @Test
    fun `compaction covering the whole path leaves only the user guard result`() {
        val path = chain(4)

        // 覆盖到 m2，剩 m3（assistant）→ 首条不是 user → 全丢
        assertTrue(orderedOutgoingMessages(path, compaction("m2")).isEmpty())
    }

    @Test
    fun `stale compaction from another branch is ignored`() {
        // 摘要是别的分支留下的（那个 id 不在当前路径上）→ 不该白丢一半上下文
        val path = chain(6)

        assertEquals(
            path.contents(),
            orderedOutgoingMessages(path, compaction("不存在的消息")).contents(),
        )
    }

    private fun compaction(throughId: String) = CompactionEntity(
        id = "c1",
        conversationId = "conv",
        throughMessageId = throughId,
        summary = "前情提要",
        createdAt = 0,
        updatedAt = 0,
    )
}
