package com.simplechat.app.net.anthropic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic 兼容通道的**跨端点**细节：地址怎么拼、请求头带什么、哪些状态值得重试。
 *
 * 没有 Key 也能把这些钉死 —— 它们错了只表现为 404 / 401 / 400，
 * 而错误信息未必指得出来（尤其是换网关之后继续聊历史对话）。
 */
class AnthropicApiTest {

    // ── 地址 ──────────────────────────────────────────────

    @Test
    fun `官方域走 anthropic 前缀`() {
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            anthropicMessagesUrl("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            anthropicMessagesUrl("https://api.deepseek.com/anthropic"),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            anthropicMessagesUrl("https://api.deepseek.com/anthropic/v1"),
        )
        // 尾斜杠不影响
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            anthropicMessagesUrl("https://api.deepseek.com/"),
        )
    }

    @Test
    fun `第三方 Anthropic 兼容网关走标准 v1 路径`() {
        assertEquals(
            "https://gw.example.com/v1/messages",
            anthropicMessagesUrl("https://gw.example.com"),
        )
        assertEquals(
            "https://gw.example.com/v1/messages",
            anthropicMessagesUrl("https://gw.example.com/v1"),
        )
        // 自建网关也可能把路径写成 /anthropic
        assertEquals(
            "https://gw.example.com/anthropic/v1/messages",
            anthropicMessagesUrl("https://gw.example.com/anthropic"),
        )
    }

    @Test
    fun `已经是最具体的地址就原样用`() {
        assertEquals(
            "https://gw.example.com/v1/messages",
            anthropicMessagesUrl("https://gw.example.com/v1/messages"),
        )
    }

    // ── 请求头 ────────────────────────────────────────────

    @Test
    fun `请求头同时带两种鉴权与协议版本`() {
        val headers = anthropicHeaders("sk-abc")
        // 原版 Anthropic 只认 x-api-key；部分中转只认 Bearer —— 两个都带
        assertEquals("sk-abc", headers.getValue("x-api-key"))
        assertEquals("Bearer sk-abc", headers.getValue("Authorization"))
        // 原版强制要求；缺了直接 400
        assertEquals(AnthropicVersion, headers.getValue("anthropic-version"))
        assertEquals("text/event-stream", headers.getValue("Accept"))
    }

    @Test
    fun `没填 Key 时不带鉴权头`() {
        val headers = anthropicHeaders("")
        assertFalse(headers.containsKey("x-api-key"))
        assertFalse(headers.containsKey("Authorization"))
    }

    @Test
    fun `额外头可以覆盖默认值`() {
        // 例如 OpenCode 的 x-opencode-session
        val headers = anthropicHeaders("sk", mapOf("x-opencode-session" to "s1"))
        assertEquals("s1", headers.getValue("x-opencode-session"))
    }

    // ── 重试判据 ──────────────────────────────────────────

    @Test
    fun `限流与上游不可用值得重试`() {
        assertTrue(isRetryableStatus(429))
        assertTrue(isRetryableStatus(500))
        assertTrue(isRetryableStatus(502))
        assertTrue(isRetryableStatus(503))
        assertTrue(isRetryableStatus(504))
        // Anthropic 特有的 overloaded_error
        assertTrue(isRetryableStatus(529))
    }

    @Test
    fun `请求本身有问题不重试`() {
        assertFalse(isRetryableStatus(400))
        assertFalse(isRetryableStatus(401))
        assertFalse(isRetryableStatus(403))
        assertFalse(isRetryableStatus(404))
        assertFalse(isRetryableStatus(413))
    }
}
