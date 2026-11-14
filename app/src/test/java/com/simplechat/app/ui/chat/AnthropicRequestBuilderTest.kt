package com.simplechat.app.ui.chat

import com.simplechat.app.data.Attachment
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.anthropic.AnthropicMessageParam
import com.simplechat.app.net.anthropic.AnthropicTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扩展通道的请求装配。
 *
 * 这一层错了只表现为「模型答非所问 / 搜索不触发 / 400」，从界面上完全看不出
 * 是哪一环 —— 所以每条协议规则都单独钉一个用例。
 */
class AnthropicRequestBuilderTest {

    private fun user(text: String, attachments: List<Attachment> = emptyList()) = UiMessage(
        id = "u-$text",
        role = MessageRole.USER,
        content = text,
        attachments = attachments,
        model = null,
    )

    private fun assistant(
        text: String,
        timeline: List<ReplyBlock> = emptyList(),
        stopReason: String? = null,
    ) = UiMessage(
        id = "a-$text",
        role = MessageRole.ASSISTANT,
        content = text,
        model = "deepseek-flash",
        timeline = timeline,
        stopReason = stopReason,
    )

    private fun build(
        path: List<UiMessage>,
        search: Boolean = false,
        thinking: Boolean = false,
        title: Boolean = false,
        reminder: String? = null,
        maxTokens: Int? = null,
        prefix: UiMessage? = null,
        system: String = "",
    ) = buildAnthropicRequest(
        model = "deepseek-flash",
        systemPrompt = system,
        path = path,
        searchEnabled = search,
        thinkingEnabled = thinking,
        requestTitle = title,
        latestReminder = reminder,
        maxTokens = maxTokens,
        prefix = prefix,
    )

    // ── 系统提示词与工具 ──────────────────────────────────

    @Test
    fun `系统提示词为空时用身份说明兜底`() {
        val request = build(listOf(user("你好")))
        assertEquals(DefaultSystemPrompt, request.system)
    }

    @Test
    fun `系统提示词原样透出`() {
        val request = build(listOf(user("你好")), system = "  保持第三人称  ")
        assertEquals("保持第三人称", request.system)
    }

    @Test
    fun `读链接始终声明，搜索受开关控制`() {
        assertEquals(listOf(AnthropicTool.ReadUrl), build(listOf(user("你好"))).tools)
        assertEquals(
            listOf(AnthropicTool.ReadUrl, AnthropicTool.WebSearch),
            build(listOf(user("你好")), search = true).tools,
        )
    }

    @Test
    fun `思考开关映射到 thinking 字段`() {
        assertEquals("disabled", build(listOf(user("你好"))).thinking.type)
        assertEquals("enabled", build(listOf(user("你好")), thinking = true).thinking.type)
    }

    @Test
    fun `安全审核档位始终为 high（扩展模式的开关）`() {
        assertEquals("high", build(listOf(user("你好"))).safetyCheck.level)
    }

    @Test
    fun `标题只在请求时才带`() {
        assertNull(build(listOf(user("你好"))).title)
        assertEquals(true, build(listOf(user("你好")), title = true).title?.enabled)
    }

    @Test
    fun `输出上限为 0 或空时不发`() {
        assertNull(build(listOf(user("你好")), maxTokens = null).maxTokens)
        assertNull(build(listOf(user("你好")), maxTokens = 0).maxTokens)
        assertEquals(8192, build(listOf(user("你好")), maxTokens = 8192).maxTokens)
    }

    // ── 时间地点提醒 ──────────────────────────────────────

    @Test
    fun `提醒插在本轮提问之前`() {
        val request = build(
            listOf(user("第一问"), assistant("第一答"), user("第二问")),
            reminder = "2026-11-14,星期六,杭州,Android,中文",
        )
        assertEquals(4, request.messages.size)
        assertEquals(AnthropicMessageParam.RoleReminder, request.messages[2].role)
        assertEquals("2026-11-14,星期六,杭州,Android,中文", request.messages[2].content.jsonPrimitive.content)
        assertEquals(AnthropicMessageParam.RoleUser, request.messages[3].role)
    }

    @Test
    fun `没有提醒时不插空消息`() {
        val request = build(listOf(user("你好")))
        assertEquals(1, request.messages.size)
    }

    // ── 用户消息与附件 ────────────────────────────────────

    @Test
    fun `纯文本用户消息用字符串 content`() {
        val request = build(listOf(user("你好")))
        assertTrue(request.messages.single().content is JsonPrimitive)
    }

    @Test
    fun `图片走 base64 块，先图后文`() {
        val image = Attachment(
            kind = Attachment.Kind.IMAGE,
            name = "a.png",
            mime = "image/png",
            data = "QUJD",
        )
        val request = build(listOf(user("这是什么", listOf(image))))
        val blocks = request.messages.single().content.jsonArray
        assertEquals(2, blocks.size)
        val imageBlock = blocks[0].jsonObject
        assertEquals("image", imageBlock["type"]!!.jsonPrimitive.content)
        val source = imageBlock["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", source["data"]!!.jsonPrimitive.content)
        assertEquals("这是什么", blocks[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `文本附件拼进正文而不是单独成块`() {
        val file = Attachment(kind = Attachment.Kind.TEXT, name = "设定.md", data = "世界观")
        val request = build(listOf(user("按这个写", listOf(file))))
        val content = request.messages.single().content.jsonPrimitive.content
        assertTrue(content.startsWith("按这个写"))
        assertTrue(content.contains("世界观"))
    }

    // ── assistant 历史回传规则 ────────────────────────────

    @Test
    fun `历史里的思考不回传`() {
        val reply = assistant(
            "正文",
            timeline = listOf(
                ReplyBlock.Thinking("想过", signature = "sig"),
                ReplyBlock.Text("正文"),
            ),
        )
        val blocks = build(listOf(user("问"), reply)).messages[1].content.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("正文", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `历史里的搜索结果不回传`() {
        val reply = assistant(
            "正文",
            timeline = listOf(
                ReplyBlock.ToolUse("t1", "deepseek_web_search", "search", "search", listOf("q")),
                ReplyBlock.SearchResult("t1", "search", emptyList()),
                ReplyBlock.Text("正文"),
            ),
        )
        val blocks = build(listOf(user("问"), reply)).messages[1].content.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `读链接的调用与结果连同签名原样回传`() {
        val reply = assistant(
            "正文",
            timeline = listOf(
                ReplyBlock.ToolUse(
                    id = "rl1",
                    name = "deepseek_read_url",
                    kind = ReadLinkKind,
                    action = "read_link",
                    url = "https://e.com",
                ),
                ReplyBlock.FetchResult(
                    toolUseId = "rl1",
                    kind = ReadLinkKind,
                    url = "https://e.com",
                    title = "页面",
                    signature = "sig-1",
                ),
                ReplyBlock.Text("正文"),
            ),
        )
        val blocks = build(listOf(user("问"), reply)).messages[1].content.jsonArray
        assertEquals(3, blocks.size)
        assertEquals("server_tool_use", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("read_link", blocks[0].jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("web_fetch_tool_result", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sig-1", blocks[1].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("text", blocks[2].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `缺少有效签名的读链接整对丢弃`() {
        val reply = assistant(
            "正文",
            timeline = listOf(
                ReplyBlock.ToolUse(
                    id = "rl1",
                    name = "deepseek_read_url",
                    kind = ReadLinkKind,
                    action = "read_link",
                    url = "https://e.com",
                ),
                ReplyBlock.FetchResult(
                    toolUseId = "rl1",
                    kind = ReadLinkKind,
                    url = "https://e.com",
                    signature = null,
                ),
                ReplyBlock.Text("正文"),
            ),
        )
        val blocks = build(listOf(user("问"), reply)).messages[1].content.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `没有时间线的老回复按一段正文回传`() {
        val reply = assistant("老消息")
        val blocks = build(listOf(user("问"), reply)).messages[1].content.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("老消息", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `内容为空的 assistant 不进请求`() {
        val empty = assistant("", timeline = emptyList())
        val request = build(listOf(user("问"), empty))
        assertEquals(1, request.messages.size)
    }

    @Test
    fun `相邻正文块合并成一块`() {
        val reply = assistant(
            "",
            timeline = listOf(ReplyBlock.Text("甲"), ReplyBlock.Text("乙")),
        )
        val blocks = build(listOf(user("问"), reply)).messages[1].content.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("甲乙", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    // ── 续写前缀 ──────────────────────────────────────────

    @Test
    fun `续写把本轮回答作为末尾 assistant 发出去`() {
        val prefix = assistant(
            "写到一半",
            timeline = listOf(ReplyBlock.Text("写到一半")),
            stopReason = "max_tokens",
        )
        val request = build(listOf(user("写故事")), prefix = prefix)
        assertEquals(2, request.messages.size)
        assertEquals(AnthropicMessageParam.RoleAssistant, request.messages.last().role)
    }

    @Test
    fun `续写开启思考时带上本轮思考及其签名`() {
        val prefix = assistant(
            "正文",
            timeline = listOf(
                ReplyBlock.Thinking("想过", signature = "sig"),
                ReplyBlock.Text("正文"),
            ),
        )
        val blocks = build(listOf(user("问")), thinking = true, prefix = prefix)
            .messages.last().content.jsonArray
        assertEquals(2, blocks.size)
        assertEquals("thinking", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("sig", blocks[0].jsonObject["signature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `续写关闭思考时不带思考块`() {
        val prefix = assistant(
            "正文",
            timeline = listOf(
                ReplyBlock.Thinking("想过", signature = "sig"),
                ReplyBlock.Text("正文"),
            ),
        )
        val blocks = build(listOf(user("问")), thinking = false, prefix = prefix)
            .messages.last().content.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `续写前缀为空时整条不追加`() {
        val prefix = assistant("", timeline = emptyList())
        val request = build(listOf(user("问")), prefix = prefix)
        assertEquals(1, request.messages.size)
    }

    /** 手上这份 JSON 是对象还是数组，读错了会静默空转，所以显式断言一次类型。 */
    private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject = jsonObject
}
