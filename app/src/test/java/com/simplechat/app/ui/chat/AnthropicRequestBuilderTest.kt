package com.simplechat.app.ui.chat

import com.simplechat.app.data.Attachment
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.anthropic.AnthropicMessageParam
import com.simplechat.app.net.anthropic.AnthropicTool
import kotlinx.serialization.json.JsonArray
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
        assertEquals("disabled", build(listOf(user("你好"))).thinking!!.type)
        assertEquals("enabled", build(listOf(user("你好")), thinking = true).thinking!!.type)
    }

    @Test
    fun `安全审核档位始终为 high（扩展模式的开关）`() {
        assertEquals("high", build(listOf(user("你好"))).safetyCheck!!.level)
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

    /**
     * 真机 400 原文：
     * `messages.9: a 'latest_reminder' message must be immediately followed by a 'user' message`。
     *
     * 「编辑 AI 回复 → 保存并发送」正是这条路径：新回复还在流式、不在路径里，
     * 路径以**被编辑的那条 assistant** 结尾，而提醒原先插在"路径最后一条之前"——
     * 于是提醒后面跟的是 assistant，服务端直接拒。
     */
    @Test
    fun `路径以 assistant 结尾时提醒仍然紧跟 user`() {
        val request = build(
            listOf(user("问"), assistant("被改过的回答")),
            reminder = "2026-11-14,星期六,杭州,Android,中文",
        )
        val roles = request.messages.map { it.role }
        assertEquals(listOf(AnthropicMessageParam.RoleReminder, "user", "assistant"), roles)
    }

    @Test
    fun `续写时提醒落在最后一条 user 前面`() {
        val prefix = assistant("写到一半", timeline = listOf(ReplyBlock.Text("写到一半")))
        val request = build(
            listOf(user("写故事")),
            reminder = "2026-11-14,星期六,杭州",
            prefix = prefix,
        )
        val roles = request.messages.map { it.role }
        assertEquals(listOf(AnthropicMessageParam.RoleReminder, "user", "assistant"), roles)
    }

    @Test
    fun `落单的提醒会被整理掉`() {
        // 提醒后面只剩 assistant（合并 / 丢空消息之后可能出现）：留着就是 400
        val kept = sanitizeTurns(
            listOf(
                AnthropicMessageParam(AnthropicMessageParam.RoleReminder, JsonPrimitive("提醒")),
                AnthropicMessageParam(AnthropicMessageParam.RoleAssistant, JsonArray(emptyList())),
            ),
        )
        assertTrue(kept.none { it.role == AnthropicMessageParam.RoleReminder })
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

    // ── 图片：底线是"不发出服务端一定拒的块" ─────────────────

    private fun image(data: String, mime: String = "image/jpeg") = Attachment(
        kind = Attachment.Kind.IMAGE,
        name = "图",
        mime = mime,
        data = data,
        width = 10,
        height = 10,
    )

    @Test
    fun `图片按服务端要求的 base64 块发出`() {
        val request = build(listOf(user("看图", listOf(image("/9j/AAA")))))
        val blocks = request.messages.single().content.jsonArray
        assertEquals(2, blocks.size)
        assertEquals("image", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        val source = blocks[0].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("/9j/AAA", source["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `media_type 只认协议允许的四个值`() {
        // 历史数据里出现过 image/jpg、image/heic 之类：原样发出去就是 400
        val request = build(listOf(user("看图", listOf(image("/9j/AAA", mime = "image/heic")))))
        val source = request.messages.single().content.jsonArray[0]
            .jsonObject["source"]!!.jsonObject
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `没有字节的图片不发出空块`() {
        /*
         * 落盘附件的 data 是空的，要靠 prepareRequestAttachments 读回。
         * 万一某条路径漏了那一步：宁可不发这张图，也不能发一个空 base64 ——
         * 服务端会回 unsupported image，而且这个会话**每一轮都会失败**。
         */
        val request = build(listOf(user("看图", listOf(image("")))))
        val content = request.messages.single().content
        assertEquals("看图", (content as JsonPrimitive).content)
    }

    /** 手上这份 JSON 是对象还是数组，读错了会静默空转，所以显式断言一次类型。 */
    private fun kotlinx.serialization.json.JsonElement.asObject(): JsonObject = jsonObject
    // ══════════════════════════════════════════════════════
    //  原版 Anthropic / 第三方兼容网关（deepSeekExtensions = false）
    // ══════════════════════════════════════════════════════

    private fun buildVanilla(
        path: List<UiMessage>,
        thinking: Boolean = false,
        temperature: Double? = null,
        maxTokens: Int? = null,
        reminder: String? = null,
        search: Boolean = true,
        title: Boolean = true,
    ) = buildAnthropicRequest(
        model = "claude-sonnet-4-5",
        systemPrompt = "",
        path = path,
        searchEnabled = search,
        thinkingEnabled = thinking,
        requestTitle = title,
        latestReminder = reminder,
        maxTokens = maxTokens,
        deepSeekExtensions = false,
        temperature = temperature,
        topP = 0.9,
    )

    @Test
    fun `原版目标不带 DeepSeek 专有字段`() {
        val request = buildVanilla(listOf(user("你好")))
        // 这些字段原版不认识，带上就是 400
        assertNull(request.safetyCheck)
        assertNull(request.title)
        assertTrue(request.tools.isEmpty())
        // 空系统提示词不再塞 DeepSeek 身份说明
        assertEquals("", request.system)
    }

    @Test
    fun `原版目标关闭思考时省略 thinking 字段`() {
        assertNull(buildVanilla(listOf(user("你好"))).thinking)
    }

    @Test
    fun `原版目标开启思考时带 budget_tokens 且 max_tokens 大于预算`() {
        val request = buildVanilla(listOf(user("你好")), thinking = true)
        val thinking = request.thinking!!
        assertEquals("enabled", thinking.type)
        assertEquals(4096, thinking.budgetTokens)
        val maxTokens = request.maxTokens!!
        assertTrue("max_tokens 必须大于 budget_tokens", maxTokens > thinking.budgetTokens!!)
    }

    @Test
    fun `原版目标 max_tokens 必填`() {
        assertEquals(8192, buildVanilla(listOf(user("你好"))).maxTokens)
        // 用户自己填的值优先
        assertEquals(2048, buildVanilla(listOf(user("你好")), maxTokens = 2048).maxTokens)
    }

    @Test
    fun `原版目标不把 DeepSeek 的提醒角色发出去`() {
        val request = buildVanilla(listOf(user("问")), reminder = "2026-11-14,星期六,杭州")
        assertEquals(1, request.messages.size)
        assertEquals(AnthropicMessageParam.RoleUser, request.messages.single().role)
    }

    @Test
    fun `原版目标开启思考时不发温度`() {
        assertNull(buildVanilla(listOf(user("你好")), thinking = true, temperature = 0.5).temperature)
    }

    @Test
    fun `原版目标关闭思考时发温度`() {
        assertEquals(0.5, buildVanilla(listOf(user("你好")), temperature = 0.5).temperature)
        // 没填就用协议默认值，不把 top_p 也塞进去
        assertEquals(1.0, buildVanilla(listOf(user("你好"))).temperature)
        assertNull(buildVanilla(listOf(user("你好"))).topP)
    }

    @Test
    fun `原版目标把 DeepSeek 工具块退化成纯正文`() {
        val reply = assistant(
            "正文",
            timeline = listOf(
                ReplyBlock.ToolUse("t1", "deepseek_read_url", "read_link", "read_link", emptyList(), url = "https://a.b"),
                ReplyBlock.FetchResult("t1", "read_link", url = "https://a.b", signature = "sig"),
                ReplyBlock.Text("正文"),
            ),
        )
        val blocks = buildVanilla(listOf(user("问"), reply)).messages[1].content.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("正文", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    // ── 历史序列整理（换端点后继续聊） ─────────────────────

    @Test
    fun `相邻同角色消息合并`() {
        val request = buildVanilla(listOf(user("第一句"), user("第二句")))
        assertEquals(1, request.messages.size)
        assertEquals("第一句\n\n第二句", request.messages.single().content.jsonPrimitive.content)
    }

    @Test
    fun `以 assistant 开头的历史被截掉`() {
        // 导入 / 删消息后可能出现"开头是 AI 回复"，原版 Anthropic 要求第一条是 user
        val request = buildVanilla(listOf(assistant("旧的回答"), user("新问题")))
        assertEquals(1, request.messages.size)
        assertEquals(AnthropicMessageParam.RoleUser, request.messages.single().role)
    }

    @Test
    fun `空消息不会发出去`() {
        val request = buildVanilla(listOf(user("有内容"), assistant("")))
        assertEquals(1, request.messages.size)
    }

    @Test
    fun `同角色合并后仍然 user-assistant 交替`() {
        val request = buildVanilla(
            listOf(user("问一"), assistant("答一"), user("问二"), user("问三"), assistant("答二"), user("问四")),
        )
        val roles = request.messages.map { it.role }
        assertEquals(listOf("user", "assistant", "user", "assistant", "user"), roles)
    }
}
