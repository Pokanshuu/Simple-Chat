package com.simplechat.app.net.anthropic

import com.simplechat.app.net.Citation
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.WebSource
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic 兼容通道的 SSE 解析用例。
 *
 * 覆盖真实流里会出现的形态：按 `index` 分块的增量、原子下发的工具块、
 * 引用角标、服务端标题、`message_stop`、`error` 帧、`ping` 与脏数据。
 * 没有 API Key 也能验证协议层正确性 —— 与 [com.simplechat.app.net.SseLineParserTest] 同一套思路。
 */
class AnthropicStreamParserTest {

    private val parser = AnthropicStreamParser(
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        },
    )

    private fun events(line: String): List<StreamEvent> {
        val result = parser.parse(line)
        assertTrue("期望解析出事件，实际是 $result", result is AnthropicStreamParser.Result.Events)
        return (result as AnthropicStreamParser.Result.Events).events
    }

    // ── 基本事件 ──────────────────────────────────────────

    @Test
    fun `message_stop 表示整条回复结束`() {
        assertEquals(AnthropicStreamParser.Result.Done, parser.parse("""data: {"type":"message_stop"}"""))
    }

    @Test
    fun `event 行本身不产生事件`() {
        assertEquals(
            AnthropicStreamParser.Result.Ignore,
            parser.parse("event: content_block_delta"),
        )
    }

    @Test
    fun `ping 与注释被忽略`() {
        assertEquals(AnthropicStreamParser.Result.Ignore, parser.parse(""": keep-alive"""))
        assertEquals(AnthropicStreamParser.Result.Ignore, parser.parse("""data: {"type":"ping"}"""))
    }

    @Test
    fun `坏 JSON 静默丢弃，不中断整条流`() {
        assertEquals(AnthropicStreamParser.Result.Ignore, parser.parse("data: {oops"))
    }

    @Test
    fun `网关补发的 DONE 也按结束处理`() {
        assertEquals(AnthropicStreamParser.Result.Done, parser.parse("data: [DONE]"))
    }

    @Test
    fun `流中错误帧必须报出来`() {
        val result = events("""data: {"type":"error","error":{"type":"overloaded_error","message":"等待生成超时"}}""")
        assertEquals(listOf(StreamEvent.Error("等待生成超时")), result)
    }

    // ── 内容块 ────────────────────────────────────────────

    @Test
    fun `正文块开始与增量`() {
        assertEquals(
            listOf(StreamEvent.BlockStart(0, ReplyBlock.Text(""))),
            events("""data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
        )
        assertEquals(
            listOf(StreamEvent.TextDelta(0, "雨是从傍晚")),
            events("""data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"雨是从傍晚"}}"""),
        )
    }

    @Test
    fun `思考块与签名增量`() {
        assertEquals(
            listOf(StreamEvent.BlockStart(1, ReplyBlock.Thinking(""))),
            events("""data: {"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":""}}"""),
        )
        assertEquals(
            listOf(StreamEvent.ThinkingDelta(1, "先想一下")),
            events("""data: {"type":"content_block_delta","index":1,"delta":{"type":"thinking_delta","thinking":"先想一下"}}"""),
        )
        assertEquals(
            listOf(StreamEvent.SignatureDelta(1, "msg_123")),
            events("""data: {"type":"content_block_delta","index":1,"delta":{"type":"signature_delta","signature":"msg_123"}}"""),
        )
    }

    @Test
    fun `块结束事件`() {
        assertEquals(
            listOf(StreamEvent.BlockStop(2)),
            events("""data: {"type":"content_block_stop","index":2}"""),
        )
    }

    @Test
    fun `引用增量`() {
        val result = events(
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://a.example/1","title":"标题","cited_text":"正文"}}}""",
        )
        assertEquals(
            listOf(StreamEvent.CitationDelta(0, Citation("https://a.example/1", "标题", "正文"))),
            result,
        )
    }

    @Test
    fun `文本块自带的 citations 数组一并解析`() {
        val result = events(
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":"甲","citations":[{"type":"web_search_result_location","url":"https://a/1","title":null}]}}""",
        )
        val block = (result.single() as StreamEvent.BlockStart).block as ReplyBlock.Text
        assertEquals(1, block.citations.size)
        assertNull(block.citations[0].title)
    }

    // ── 工具调用 ──────────────────────────────────────────

    @Test
    fun `搜索调用是原子的，开始时 input 已完整`() {
        val result = events(
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_s_1_ab","name":"deepseek_web_search","kind":"search","input":{"action":"search","queries":["天气","气温"]},"caller":{"type":"direct"}}}""",
        )
        assertEquals(
            listOf(
                StreamEvent.BlockStart(
                    0,
                    ReplyBlock.ToolUse(
                        id = "srvtoolu_s_1_ab",
                        name = "deepseek_web_search",
                        kind = "search",
                        action = "search",
                        queries = listOf("天气", "气温"),
                    ),
                ),
            ),
            result,
        )
    }

    @Test
    fun `读链接调用保留 url`() {
        val result = events(
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srvtoolu_rl_1","name":"deepseek_read_url","kind":"read_link","input":{"action":"read_link","url":"https://example.com"}}}""",
        )
        val block = (result.single() as StreamEvent.BlockStart).block as ReplyBlock.ToolUse
        assertEquals("https://example.com", block.url)
    }

    @Test
    fun `页内查找调用保留关键词`() {
        val result = events(
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"x","name":"deepseek_web_search","kind":"deep_search","input":{"action":"find_in_page","url":"https://e.com","pattern":"结局"}}}""",
        )
        val block = (result.single() as StreamEvent.BlockStart).block as ReplyBlock.ToolUse
        assertEquals("结局", block.pattern)
    }

    @Test
    fun `未知动作的调用块被丢弃`() {
        assertTrue(
            events(
                """data: {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"x","name":"deepseek_web_search","kind":"search","input":{"action":"teleport"}}}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `客户端工具块被丢弃`() {
        assertTrue(
            events(
                """data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t","name":"read_file","input":{}}}""",
            ).isEmpty(),
        )
    }

    // ── 工具结果 ──────────────────────────────────────────

    @Test
    fun `搜索结果块解析出网页列表`() {
        val result = events(
            """data: {"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_s_1","kind":"search","content":[{"type":"web_search_result","url":"https://a/1","title":"甲","snippet":"摘要","site_name":"甲站","site_icon":"https://a/i.png","page_age":"2026-11-01T00:00:00Z","encrypted_content":"xxx"},{"type":"web_search_result","url":"https://b/2","title":"乙","snippet":null,"site_name":null,"site_icon":null,"page_age":null,"encrypted_content":"yyy"}]}}""",
        )
        val block = (result.single() as StreamEvent.BlockStart).block as ReplyBlock.SearchResult
        assertEquals("srvtoolu_s_1", block.toolUseId)
        assertEquals(2, block.results.size)
        assertEquals(
            WebSource(
                url = "https://a/1",
                title = "甲",
                siteName = "甲站",
                siteIcon = "https://a/i.png",
                snippet = "摘要",
                pageAge = "2026-11-01T00:00:00Z",
            ),
            block.results[0],
        )
        assertNull(block.results[1].snippet)
    }

    @Test
    fun `搜索失败块标记为 failed`() {
        val result = events(
            """data: {"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"t","kind":"search","content":[{"type":"web_search_tool_result_error","error_code":"unavailable"}]}}""",
        )
        val block = (result.single() as StreamEvent.BlockStart).block as ReplyBlock.SearchResult
        assertTrue(block.failed)
        assertTrue(block.results.isEmpty())
    }

    @Test
    fun `搜索成功但无结果时 failed 为 false`() {
        val result = events(
            """data: {"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"t","kind":"search","content":[]}}""",
        )
        val block = (result.single() as StreamEvent.BlockStart).block as ReplyBlock.SearchResult
        assertTrue(!block.failed && block.results.isEmpty())
    }

    @Test
    fun `读链接结果保留 signature`() {
        val result = events(
            """data: {"type":"content_block_start","index":2,"content_block":{"type":"web_fetch_tool_result","tool_use_id":"srvtoolu_rl_1","kind":"read_link","signature":"sig-abc","content":{"type":"web_fetch_result","url":"https://e.com","content":{"type":"document","title":"页面","source":{"type":"text","media_type":"text/plain","data":""}},"published_at":"2026-10-30"}}}""",
        )
        val block = (result.single() as StreamEvent.BlockStart).block as ReplyBlock.FetchResult
        assertEquals("sig-abc", block.signature)
        assertEquals("页面", block.title)
        assertEquals("2026-10-30", block.publishedAt)
        assertTrue(!block.failed)
    }

    @Test
    fun `读链接失败块标记为 failed`() {
        val result = events(
            """data: {"type":"content_block_start","index":2,"content_block":{"type":"web_fetch_tool_result","tool_use_id":"t","kind":"read_link","content":{"type":"web_fetch_tool_result_error","error_code":"url_not_allowed"}}}""",
        )
        val block = (result.single() as StreamEvent.BlockStart).block as ReplyBlock.FetchResult
        assertTrue(block.failed)
    }

    // ── 结束与标题 ────────────────────────────────────────

    @Test
    fun `message_delta 给出结束原因与标题`() {
        val result = events(
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null,"title":"新对话标题"},"usage":{"output_tokens":42}}""",
        )
        assertTrue(result.contains(StreamEvent.Stopped("end_turn")))
        assertTrue(result.contains(StreamEvent.Title("新对话标题")))
        assertTrue(result.contains(StreamEvent.Usage(0, 42, null)))
    }

    @Test
    fun `max_tokens 结束原因可被识别`() {
        val result = events("""data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""")
        assertEquals(listOf(StreamEvent.Stopped("max_tokens")), result)
    }

    @Test
    fun `message_start 的 usage 计入缓存命中`() {
        val result = events(
            """data: {"type":"message_start","message":{"id":"abc","model":"deepseek-flash","usage":{"input_tokens":10,"cache_creation_input_tokens":5,"cache_read_input_tokens":7,"output_tokens":0}}}""",
        )
        assertEquals(listOf(StreamEvent.Usage(22, 0, 7)), result)
    }

    // ── 错误信息提取 ──────────────────────────────────────

    @Test
    fun `非 2xx 响应体的错误信息可提取`() {
        assertEquals(
            "rate limit",
            parser.parseErrorMessage("""{"error":{"message":"rate limit","type":"rate_limit_error"}}"""),
        )
        // 不是 JSON 时原样透出（截断到 300 字）
        assertEquals("Bad Gateway", parser.parseErrorMessage("Bad Gateway"))
    }
}
