package com.simplechat.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 解析的边界覆盖。
 *
 * 这些用例对应真实流里会遇到的形态：思考/正文分流、心跳注释、
 * 结束标记、脏数据、流中错误对象。没有 API Key 也能验证协议层正确性。
 */
class SseLineParserTest {

    private val parser = SseLineParser(ChatApi.defaultJson())

    private fun events(line: String): List<StreamEvent> {
        val result = parser.parse(line)
        assertTrue("期望解析出事件，实际是 $result", result is SseLineParser.Result.Events)
        return (result as SseLineParser.Result.Events).events
    }

    // ── 正文增量 ──────────────────────────────────────────

    @Test
    fun `解析正文增量`() {
        val result = events("""data: {"choices":[{"index":0,"delta":{"content":"雨是从傍晚开始下的"}}]}""")
        assertEquals(listOf(StreamEvent.Content("雨是从傍晚开始下的")), result)
    }

    @Test
    fun `content 为空串时不产生事件`() {
        val result = events("""data: {"choices":[{"index":0,"delta":{"content":""}}]}""")
        assertTrue(result.isEmpty())
    }

    // ── 思考增量 ──────────────────────────────────────────

    @Test
    fun `解析 reasoning_content 增量`() {
        val result = events(
            """data: {"choices":[{"index":0,"delta":{"reasoning_content":"用户要的是第三人称"}}]}""",
        )
        assertEquals(listOf(StreamEvent.Reasoning("用户要的是第三人称")), result)
    }

    @Test
    fun `同一条 chunk 同时含 reasoning 与 content 时按顺序派发`() {
        val result = events(
            """data: {"choices":[{"index":0,"delta":{"reasoning_content":"想","content":"写"}}]}""",
        )
        assertEquals(
            listOf(StreamEvent.Reasoning("想"), StreamEvent.Content("写")),
            result,
        )
    }

    @Test
    fun `首条 chunk 只有 role 时不产生事件`() {
        val result = events("""data: {"choices":[{"index":0,"delta":{"role":"assistant"}}]}""")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `reasoning 字符串作为别名且不会重复派发`() {
        assertEquals(
            listOf(StreamEvent.Reasoning("primary")),
            events("""data: {"choices":[{"delta":{"reasoning_content":"primary","reasoning":"alias"}}]}"""),
        )
        assertEquals(
            listOf(StreamEvent.Reasoning("alias")),
            events("""data: {"choices":[{"delta":{"reasoning_content":" ","reasoning":"alias"}}]}"""),
        )
        assertEquals(
            listOf(StreamEvent.Reasoning("\n")),
            events("""data: {"choices":[{"delta":{"reasoning":"\n"}}]}"""),
        )
    }

    @Test
    fun `解析显式摘要文本和已知摘要块`() {
        assertEquals(
            listOf(StreamEvent.Reasoning("摘要"), StreamEvent.Content("正文")),
            events("""data: {"choices":[{"delta":{"reasoning_summary":"摘要","content":"正文"}}]}"""),
        )
        assertEquals(
            listOf(StreamEvent.Reasoning("摘要 第二部分")),
            events("""data: {"choices":[{"delta":{"reasoning":{"summary":[{"type":"summary_text","text":"摘要"},{"type":"summary_text","text":" 第二部分"}]}}}]}"""),
        )
        assertEquals(
            listOf(StreamEvent.Reasoning("结构化摘要")),
            events("""data: {"choices":[{"delta":{"reasoning_content":{"summary":"结构化摘要"}}}]}"""),
        )
    }

    @Test
    fun `reasoning_details 只派发显式摘要`() {
        assertEquals(
            listOf(StreamEvent.Reasoning("摘要")),
            events("""data: {"choices":[{"delta":{"reasoning_details":[{"type":"reasoning.encrypted","data":"opaque"},{"type":"reasoning.summary","summary":"摘要"}]}}]}"""),
        )
    }

    @Test
    fun `未知对象不显示为思考且不吞同帧正文用量或终止原因`() {
        val result = events(
            """data: {"choices":[{"delta":{"reasoning_content":{"encrypted_content":"opaque"},"reasoning":{"effort":"high"},"reasoning_summary":null,"content":"正文"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":4}}""",
        )
        assertEquals(listOf(StreamEvent.Content("正文"), StreamEvent.Usage(3, 4, null), StreamEvent.Stopped("stop")), result)
        assertTrue(events("""data: {"choices":[{"delta":{"reasoning":false,"reasoning_summary":[]}}]}""").isEmpty())
    }

    @Test
    fun `只有 refusal 文本时当作正文且保留同帧 content 优先级`() {
        assertEquals(
            listOf(StreamEvent.Content("无法回答")),
            events("""data: {"choices":[{"delta":{"content":null,"refusal":"无法回答"}}]}"""),
        )
        assertEquals(
            listOf(StreamEvent.Content("正文")),
            events("""data: {"choices":[{"delta":{"content":"正文","refusal":"无法回答"}}]}"""),
        )
        assertEquals(
            listOf(StreamEvent.Content("结构化拒绝")),
            events("""data: {"choices":[{"delta":{"refusal":{"type":"refusal","text":"结构化拒绝"}}}]}"""),
        )
        assertTrue(events("""data: {"choices":[{"delta":{"refusal":{"reason":"metadata"}}}]}""").isEmpty())
    }

    @Test
    fun `reasoning_text is a fallback and preserves content in the same frame`() {
        assertEquals(
            listOf(StreamEvent.Reasoning("Summary"), StreamEvent.Content("Answer")),
            events("""data: {"choices":[{"delta":{"reasoning_content":null,"reasoning":{"effort":"high"},"reasoning_text":"Summary","content":"Answer"}}]}"""),
        )
        assertEquals(
            listOf(StreamEvent.Reasoning("First")),
            events("""data: {"choices":[{"delta":{"reasoning_content":"First","reasoning":"Second","reasoning_text":"Third"}}]}"""),
        )
    }

    // ── 用量 ─────────────────────────────────────────────

    @Test
    fun `解析 usage`() {
        val result = events(
            """data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":340,"prompt_cache_hit_tokens":80}}""",
        )
        assertEquals(listOf(StreamEvent.Usage(120, 340, 80)), result)
    }

    @Test
    fun `支持 OpenAI cached_tokens 用量字段`() {
        val result = events(
            """data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":12,"prompt_tokens_details":{"cached_tokens":80}}}""",
        )
        assertEquals(listOf(StreamEvent.Usage(120, 12, 80)), result)
    }

    @Test
    fun `usage 缺失缓存字段时为 null`() {
        val result = events(
            """data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}""",
        )
        assertEquals(listOf(StreamEvent.Usage(1, 2, null)), result)
    }

    // ── 结束与忽略 ────────────────────────────────────────

    @Test
    fun `DONE 标记返回 Done`() {
        assertEquals(SseLineParser.Result.Done, parser.parse("data: [DONE]"))
    }

    @Test
    fun `DONE 前有空格也能识别`() {
        assertEquals(SseLineParser.Result.Done, parser.parse("data:[DONE]"))
    }

    @Test
    fun `空行被忽略`() {
        assertEquals(SseLineParser.Result.Ignore, parser.parse(""))
    }

    @Test
    fun `心跳注释被忽略`() {
        assertEquals(SseLineParser.Result.Ignore, parser.parse(": keep-alive"))
    }

    @Test
    fun `event 字段被忽略`() {
        assertEquals(SseLineParser.Result.Ignore, parser.parse("event: message"))
    }

    @Test
    fun `data 前缀但无内容被忽略`() {
        assertEquals(SseLineParser.Result.Ignore, parser.parse("data: "))
    }

    @Test
    fun `finish_reason preserves completion and truncation signals`() {
        val result = events("""data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")
        assertEquals(listOf(StreamEvent.Stopped("stop")), result)
        assertEquals(listOf(StreamEvent.Stopped("length")), events("""data: {"choices":[{"delta":{},"finish_reason":"length"}]}"""))
    }

    // ── 健壮性 ───────────────────────────────────────────

    @Test
    fun `脏数据不抛异常且返回空事件`() {
        val result = events("data: {这不是合法 JSON")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `未知字段被忽略`() {
        val result = events(
            """data: {"choices":[{"index":0,"delta":{"content":"好"},"logprobs":null}],"莫名字段":123}""",
        )
        assertEquals(listOf(StreamEvent.Content("好")), result)
    }

    @Test
    fun `流中错误对象转成 Error 事件`() {
        val result = events("""data: {"error":{"message":"Rate limit reached","type":"rate_limit"}}""")
        assertEquals(1, result.size)
        val error = result.first()
        assertTrue(error is StreamEvent.Error)
        assertEquals("Rate limit reached", (error as StreamEvent.Error).message)
    }

    // ── 错误信息提取 ──────────────────────────────────────

    @Test
    fun `从 error envelope 提取错误信息`() {
        val raw = """{"error":{"message":"Insufficient Balance","type":"insufficient_quota"}}"""
        assertEquals("Insufficient Balance", parser.parseErrorMessage(raw))
    }

    @Test
    fun `非 JSON 错误体原样截断返回`() {
        val raw = "Bad Gateway"
        assertEquals("Bad Gateway", parser.parseErrorMessage(raw))
    }

    @Test
    fun `空错误体返回 null`() {
        assertEquals(null, parser.parseErrorMessage(null))
        assertEquals(null, parser.parseErrorMessage("   "))
    }
}
