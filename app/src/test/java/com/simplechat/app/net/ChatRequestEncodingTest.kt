package com.simplechat.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求体编码回归测试。
 *
 * 背景：曾因 `encodeDefaults = false` + `stream: Boolean = true` 的默认值，
 * 导致 kotlinx.serialization **把 `stream` 字段整个省略**，
 * 服务端于是返回非流式的一整块 JSON —— 表现是「流跑完了、内容为空、也不报错」，
 * 极难排查。这组用例把请求体的关键字段钉死。
 */
class ChatRequestEncodingTest {

    private val json = ChatApi.defaultJson()

    private fun encode(request: ChatRequestDto): String =
        json.encodeToString(ChatRequestDto.serializer(), request)

    private fun request(
        stream: Boolean = true,
        thinking: ThinkingDto? = null,
        reasoningEffort: String? = null,
        temperature: Double? = null,
        topP: Double? = null,
        maxTokens: Int? = null,
        messages: List<OutMessageDto> = listOf(
            OutMessageDto(role = "user", content = MessageContent.text("hi")),
        ),
    ) = ChatRequestDto(
        model = "deepseek-flash",
        messages = messages,
        stream = stream,
        thinking = thinking,
        reasoningEffort = reasoningEffort,
        temperature = temperature,
        topP = topP,
        maxTokens = maxTokens,
    )

    // ── 核心回归 ──────────────────────────────────────────

    @Test
    fun `stream 字段必须出现在请求体里`() {
        val body = encode(request(stream = true))
        assertTrue("请求体缺少 stream 字段，服务端会返回非流式响应：$body", body.contains("\"stream\":true"))
    }

    @Test
    fun `思考开关即使是默认语义也必须显式发送`() {
        val body = encode(request(thinking = ThinkingDto.Disabled))
        assertTrue("thinking 被省略：$body", body.contains("\"thinking\":{\"type\":\"disabled\"}"))
    }

    @Test
    fun `开启思考时同时带 thinking 与 reasoning_effort`() {
        val body = encode(
            request(thinking = ThinkingDto.Enabled, reasoningEffort = "high"),
        )
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\"}"))
        assertTrue(body.contains("\"reasoning_effort\":\"high\""))
    }

    // ── null 字段不应泄漏 ─────────────────────────────────

    @Test
    fun `未设置的参数不出现在请求体里`() {
        val body = encode(request())
        assertFalse("null 泄漏进请求体：$body", body.contains("null"))
        assertFalse(body.contains("reasoning_effort"))
        assertFalse(body.contains("top_p"))
        assertFalse(body.contains("max_tokens"))
        assertFalse(body.contains("temperature"))
        assertFalse(body.contains("user_id"))
    }

    @Test
    fun `显式设置的参数会出现在请求体里`() {
        val body = encode(request(temperature = 0.7, topP = 0.98, maxTokens = 4096))
        assertTrue(body.contains("\"temperature\":0.7"))
        assertTrue(body.contains("\"top_p\":0.98"))
        assertTrue(body.contains("\"max_tokens\":4096"))
    }

    // ── content 形态 ─────────────────────────────────────

    @Test
    fun `纯文本消息编码为字符串而非内容块数组`() {
        val body = encode(request())
        assertTrue("content 应是纯字符串：$body", body.contains("\"content\":\"hi\""))
    }

    @Test
    fun `带图片的消息编码为内容块数组且图片块结构正确`() {
        val body = encode(
            request(
                messages = listOf(
                    OutMessageDto(
                        role = "user",
                        content = MessageContent.withImages(
                            text = "看图",
                            images = listOf(ImageInput(dataUrl = "data:image/jpeg;base64,AAA")),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(body.contains("\"type\":\"text\""))
        assertTrue(body.contains("\"type\":\"image_url\""))
        assertTrue(body.contains("\"url\":\"data:image/jpeg;base64,AAA\""))
        // 精度默认 high：low 会把图缩到 512×512，截图里的小字会糊掉
        assertTrue(body.contains("\"detail\":\"high\""))
    }

    @Test
    fun `只有图片没有文字时不产生空 text 块`() {
        val body = encode(
            request(
                messages = listOf(
                    OutMessageDto(
                        role = "user",
                        content = MessageContent.withImages(
                            text = "",
                            images = listOf(ImageInput("data:image/png;base64,BBB")),
                        ),
                    ),
                ),
            ),
        )
        assertFalse("不应出现空 text 块：$body", body.contains("\"text\":\"\""))
        assertTrue(body.contains("\"image_url\""))
    }
}
