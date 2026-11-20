package com.simplechat.app.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨对话记忆的检索门槛。
 *
 * 这几个用例都是**实际踩过的坑**，不是想象出来的：
 *
 * 1. 「你好」曾注入 555 字符历史 —— 中文单字「好」命中了历史里每一条助手开场白，
 *    相关性大于 0 就把 limit 填满了。
 * 2. 当前会话必须排除，否则等于把正在聊的内容当"过往记忆"再贴一遍。
 * 3. 兜底只能补最近的几个会话，且必须真的带回忆意图 —— 否则每句话都在召回。
 */
class CrossChatMemoryTest {

    private fun user(id: String, text: String) =
        UiMessage(id = id, role = MessageRole.USER, content = text)

    private fun reply(id: String, text: String) =
        UiMessage(id = id, role = MessageRole.ASSISTANT, content = text)

    private fun session(id: String, title: String, updatedAt: Long = 1_700_000_000_000L) =
        CrossChatMemory.SessionRef(id = id, title = title, updatedAt = updatedAt)

    private fun build(
        query: String,
        current: String = "current",
        limit: Int = 4,
        sessions: List<CrossChatMemory.SessionRef> = emptyList(),
        messages: Map<String, List<UiMessage>> = emptyMap(),
    ): CrossChatMemory.Result = runBlocking {
        CrossChatMemory.build(
            query = query,
            currentConversationId = current,
            limit = limit,
            sessions = sessions,
            loadMessages = { id -> messages[id].orEmpty() },
        )
    }

    @Test
    fun `短问候不触发召回`() {
        val result = build(
            query = "你好",
            sessions = listOf(session("a", "问候")),
            messages = mapOf(
                "a" to listOf(user("u", "你好"), reply("r", "你好！有什么可以帮你的吗？")),
            ),
        )

        assertEquals("short-query", result.reason)
        assertFalse(result.injected)
    }

    @Test
    fun `英文短问候同样跳过`() {
        val result = build(
            query = "hi",
            sessions = listOf(session("a", "greeting")),
            messages = mapOf("a" to listOf(user("u", "hi"), reply("r", "Hello! How can I help?"))),
        )

        assertEquals("short-query", result.reason)
        assertFalse(result.injected)
    }

    @Test
    fun `不是对话唯一候选时当前会话被排除`() {
        val result = build(
            query = "之前聊过的 hello 还记得吗",
            sessions = listOf(session("current", "当前会话")),
            messages = mapOf(
                "current" to listOf(user("u", "hello"), reply("r", "你好！")),
            ),
        )

        assertEquals("no-candidates", result.reason)
        assertFalse(result.injected)
    }

    @Test
    fun `有回忆意图但内容无关时兜底最近会话`() {
        val sessions = (1..5).map { session("s$it", "会话$it", updatedAt = 1_700_000_000_000L + it) }
        val result = build(
            query = "我们之前聊过什么",
            sessions = sessions,
            messages = sessions.associate { it.id to listOf(user("u", "无关内容"), reply("r", "回答")) },
        )

        assertTrue(result.injected)
        assertTrue("兜底应标记 fallback", result.fallback)
        assertEquals("兜底最多补 3 个会话", 3, result.picked)
    }

    @Test
    fun `没有回忆意图的普通提问不兜底`() {
        val sessions = (1..5).map { session("s$it", "会话$it") }
        val result = build(
            query = "帮我写一段 Python 脚本",
            sessions = sessions,
            messages = sessions.associate { it.id to listOf(user("u", "无关内容"), reply("r", "回答")) },
        )

        assertFalse(result.injected)
        assertEquals("no-match", result.reason)
    }

    @Test
    fun `只有单字命中时不召回`() {
        val result = build(
            query = "帮我看看这个",
            sessions = listOf(session("a", "读书笔记")),
            messages = mapOf(
                "a" to listOf(user("u", "这本书很好看"), reply("r", "确实很好看")),
            ),
        )

        assertFalse(result.injected)
        assertEquals("no-match", result.reason)
    }

    @Test
    fun `相关会话被选中且文本带标题`() {
        val result = build(
            query = "还记得我们聊过的 hello 吗",
            sessions = listOf(session("a", "问候回应"), session("b", "别的话题")),
            messages = mapOf(
                "a" to listOf(user("u1", "hello"), reply("r1", "你好！有什么可以帮你的吗？")),
                "b" to listOf(user("u2", "今天天气如何"), reply("r2", "晴。")),
            ),
        )

        assertTrue(result.injected)
        assertTrue("应带上会话标题", result.text.contains("对话《问候回应》"))
        assertTrue("应带上原话", result.text.contains("hello"))
    }

    @Test
    fun `limit 为 0 时禁用`() {
        val result = build(
            query = "我们之前聊过什么",
            limit = 0,
            sessions = listOf(session("a", "会话")),
            messages = mapOf("a" to listOf(user("u", "内容"), reply("r", "回答"))),
        )

        assertEquals("disabled", result.reason)
        assertFalse(result.injected)
    }

    @Test
    fun `空白查询不检索`() {
        val result = build(query = "   ", sessions = listOf(session("a", "会话")))

        assertEquals("empty-query", result.reason)
        assertFalse(result.injected)
    }

    @Test
    fun `分词保留中文双字并过滤停用词`() {
        val tokens = CrossChatMemory.tokenize("我们之前聊过什么 hello world")

        assertTrue("应保留中文双字", tokens.contains("聊过"))
        assertTrue("应保留英文词", tokens.contains("hello"))
        assertFalse("应过滤停用词", tokens.contains("我们"))
        assertFalse("应过滤停用词", tokens.contains("什么"))
    }

    @Test
    fun `取消读取时不再扫描其他会话`() {
        val loaded = mutableListOf<String>()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                CrossChatMemory.build(
                    query = "我们之前聊过什么",
                    currentConversationId = "current",
                    limit = 4,
                    sessions = listOf(session("a", "A"), session("b", "B")),
                    loadMessages = { id ->
                        loaded += id
                        throw CancellationException("Stopped")
                    },
                )
            }
        }
        assertEquals(listOf("a"), loaded)
    }

    @Test
    fun `空历史会话不参与评分`() {
        val result = build(
            query = "我们之前聊过什么",
            sessions = listOf(session("empty", "空会话")),
            messages = mapOf("empty" to emptyList()),
        )

        assertEquals("no-history", result.reason)
        assertFalse(result.injected)
    }
}
