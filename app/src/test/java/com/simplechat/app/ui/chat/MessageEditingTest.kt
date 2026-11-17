package com.simplechat.app.ui.chat

import com.simplechat.app.net.ReplyBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑回复时对**内容块时间线**的改写。
 *
 * 这一条连着两条通道的差异：OpenAI 通道只读 `content`，扩展通道的界面与请求都读
 * `timeline`。只改 `content` 的后果是"保存了却没变"（或者界面变了、发出去的还是旧的）——
 * 从现象上几乎不可能定位，所以把规则在这里钉死。
 */
class MessageEditingTest {

    @Test
    fun `没有时间线的消息原样返回`() {
        assertEquals(emptyList<ReplyBlock>(), rewriteTimelineText(emptyList(), "新文字"))
    }

    @Test
    fun `只有一段正文时换成新文字`() {
        val timeline = listOf(ReplyBlock.Text("旧文字"))
        assertEquals(listOf(ReplyBlock.Text("新文字")), rewriteTimelineText(timeline, "新文字"))
    }

    @Test
    fun `思考块保留，多段正文合并成一段并落在第一段的位置`() {
        val timeline = listOf(
            ReplyBlock.Thinking("想过", signature = "sig"),
            ReplyBlock.Text("第一段"),
            ReplyBlock.Text("第二段"),
        )
        val out = rewriteTimelineText(timeline, "改过的正文")
        assertEquals(2, out.size)
        assertTrue(out[0] is ReplyBlock.Thinking)
        assertEquals(ReplyBlock.Text("改过的正文"), out[1])
    }

    @Test
    fun `搜索与读链接块保持原序`() {
        val timeline = listOf(
            ReplyBlock.ToolUse("t1", "deepseek_web_search", "search", "search", listOf("q")),
            ReplyBlock.SearchResult("t1", "search", emptyList()),
            ReplyBlock.Text("旧正文"),
            ReplyBlock.ToolUse("rl1", "deepseek_read_url", ReadLinkKind, "read_link", url = "https://e.com"),
            ReplyBlock.FetchResult("rl1", ReadLinkKind, url = "https://e.com", signature = "s"),
        )
        val out = rewriteTimelineText(timeline, "新正文")
        // 只有正文那一块被替换，其余四块顺序不变
        assertEquals(5, out.size)
        assertTrue(out[0] is ReplyBlock.ToolUse)
        assertTrue(out[1] is ReplyBlock.SearchResult)
        assertEquals(ReplyBlock.Text("新正文"), out[2])
        assertTrue(out[3] is ReplyBlock.ToolUse)
        assertTrue(out[4] is ReplyBlock.FetchResult)
    }

    @Test
    fun `没有正文块时新文字补在末尾`() {
        val timeline = listOf(ReplyBlock.Thinking("想过", signature = "sig"))
        val out = rewriteTimelineText(timeline, "补上的正文")
        assertEquals(2, out.size)
        assertTrue(out[0] is ReplyBlock.Thinking)
        assertEquals(ReplyBlock.Text("补上的正文"), out[1])
    }
}
