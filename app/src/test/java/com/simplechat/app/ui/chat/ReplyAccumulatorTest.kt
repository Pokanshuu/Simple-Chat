package com.simplechat.app.ui.chat

import com.simplechat.app.net.Citation
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.WebSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块级流式事件的归并。
 *
 * 这一层错了的表现极难定位：界面少一段正文、引用角标错位、
 * 思考计时算成了整轮耗时。所以边界都单独钉一遍。
 */
class ReplyAccumulatorTest {

    private val acc = ReplyAccumulator()

    @Test
    fun `long stream snapshots stay unchanged while more deltas arrive`() {
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Thinking("")), 1_000)
        acc.consume(StreamEvent.BlockStart(1, ReplyBlock.Text("")), 1_000)
        val citation = Citation("https://test/one")
        repeat(10_000) {
            acc.consume(StreamEvent.ThinkingDelta(0, "why"), 2_000)
            acc.consume(StreamEvent.SignatureDelta(0, "s"), 2_000)
            acc.consume(StreamEvent.TextDelta(1, "answer"), 2_000)
        }
        acc.consume(StreamEvent.CitationDelta(1, citation), 2_100)
        val checkpoint = acc.blocks
        assertTrue(checkpoint === acc.blocks)
        acc.consume(StreamEvent.ThinkingDelta(0, " next"), 2_200)
        acc.consume(StreamEvent.TextDelta(1, " complete"), 2_200)
        acc.consume(StreamEvent.CitationDelta(1, Citation("https://test/two")), 2_200)
        acc.settle(3_000)

        val savedThought = checkpoint[0] as ReplyBlock.Thinking
        val savedText = checkpoint[1] as ReplyBlock.Text
        assertEquals("why".repeat(10_000), savedThought.thinking)
        assertEquals("s".repeat(10_000), savedThought.signature)
        assertNull(savedThought.endedAt)
        assertEquals("answer".repeat(10_000), savedText.text)
        assertEquals(listOf(citation), savedText.citations)
        assertEquals(savedThought.thinking + " next", acc.thinking)
        assertEquals(savedText.text + " complete", acc.answer)
        assertEquals(2_000L, acc.thinkingMillis(9_000))
    }

    @Test
    fun `continuation never closes a prior unfinished block using the new clock`() {
        val old = ReplyBlock.Thinking("previous", startedAt = 1_700_000_000_000L)
        acc.seed(listOf(old))
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Thinking("current")), 1_000)
        acc.settle(2_000)
        assertEquals(old, acc.blocks.first())
        assertEquals(1_000L, acc.thinkingMillis(2_000))
    }

    @Test
    fun `正文增量按 index 累加`() {
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Text("")), 1_000)
        acc.consume(StreamEvent.TextDelta(0, "雨是从"), 1_001)
        acc.consume(StreamEvent.TextDelta(0, "傍晚开始下的"), 1_002)
        assertEquals("雨是从傍晚开始下的", acc.answer)
    }

    @Test
    fun `未知 index 的增量被忽略，不抛异常`() {
        acc.consume(StreamEvent.TextDelta(9, "幽灵"), 1_000)
        assertTrue(acc.blocks.isEmpty())
    }

    @Test
    fun `思考块记录起止时刻`() {
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Thinking("")), 1_000)
        acc.consume(StreamEvent.ThinkingDelta(0, "先想一下"), 1_100)
        acc.consume(StreamEvent.BlockStop(0), 3_000)
        val block = acc.blocks.single() as ReplyBlock.Thinking
        assertEquals(1_000L, block.startedAt)
        assertEquals(3_000L, block.endedAt)
        assertEquals(2_000L, acc.thinkingMillis(9_999))
    }

    @Test
    fun `未结束的思考按当前时刻计用时`() {
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Thinking("")), 1_000)
        assertEquals(4_000L, acc.thinkingMillis(5_000))
    }

    @Test
    fun `没有思考块时用时为 null`() {
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Text("正文")), 1_000)
        assertNull(acc.thinkingMillis(5_000))
    }

    @Test
    fun `签名为增量拼接`() {
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Thinking("想")), 1_000)
        acc.consume(StreamEvent.SignatureDelta(0, "abc"), 1_100)
        acc.consume(StreamEvent.SignatureDelta(0, "def"), 1_200)
        assertEquals("abcdef", (acc.blocks.single() as ReplyBlock.Thinking).signature)
    }

    @Test
    fun `同一块内引用按 url 去重`() {
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Text("甲")), 1_000)
        val citation = Citation("https://a/1", "标题", "甲")
        acc.consume(StreamEvent.CitationDelta(0, citation), 1_100)
        acc.consume(StreamEvent.CitationDelta(0, citation.copy(citedText = "甲更多")), 1_200)
        assertEquals(1, (acc.blocks.single() as ReplyBlock.Text).citations.size)
    }

    @Test
    fun `多个块保持到达顺序`() {
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Thinking("")), 1_000)
        acc.consume(StreamEvent.BlockStop(0), 2_000)
        acc.consume(
            StreamEvent.BlockStart(
                1,
                ReplyBlock.ToolUse("t1", "deepseek_web_search", "search", "search", listOf("天气")),
            ),
            2_100,
        )
        acc.consume(
            StreamEvent.BlockStart(
                2,
                ReplyBlock.SearchResult("t1", "search", listOf(WebSource("https://a/1", "甲"))),
            ),
            3_000,
        )
        acc.consume(StreamEvent.BlockStart(3, ReplyBlock.Text("")), 3_100)
        acc.consume(StreamEvent.TextDelta(3, "回答"), 3_200)

        assertEquals(4, acc.blocks.size)
        assertTrue(acc.blocks[1] is ReplyBlock.ToolUse)
        assertEquals("回答", acc.answer)
    }

    @Test
    fun `块序号在新一轮里可以重来`() {
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Text("甲")), 1_000)
        acc.consume(StreamEvent.BlockStop(0), 1_100)
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Text("乙")), 1_200)
        acc.consume(StreamEvent.TextDelta(0, "丙"), 1_300)
        assertEquals("甲乙丙", acc.answer)
    }

    @Test
    fun `标题与结束原因被记下`() {
        acc.consume(StreamEvent.Title("新标题"), 1_000)
        acc.consume(StreamEvent.Stopped("max_tokens"), 1_100)
        assertEquals("新标题", acc.title)
        assertEquals("max_tokens", acc.stopReason)
    }

    @Test
    fun `安全拒绝时保留已经收到的正文与思考`() {
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Thinking("")), 1_000)
        acc.consume(StreamEvent.ThinkingDelta(0, "想过"), 1_100)
        acc.consume(
            StreamEvent.BlockStart(1, ReplyBlock.ToolUse("t", "deepseek_web_search", "search", "search")),
            1_200,
        )
        acc.consume(StreamEvent.BlockStart(2, ReplyBlock.Text("已经写出来的字")), 1_300)
        acc.consume(StreamEvent.Stopped("refusal"), 1_400)
        acc.settle(2_000)

        // 第三方客户端不替服务端销毁：拒答只影响"还能不能继续写"，不影响"已经写了什么"
        assertEquals(3, acc.blocks.size)
        assertEquals("已经写出来的字", acc.answer)
        assertEquals("想过", acc.thinking)
        assertEquals("refusal", acc.stopReason)
    }

    @Test
    fun `正常结束时只收口未结束的思考`() {
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Text("正文")), 1_000)
        acc.consume(StreamEvent.BlockStart(1, ReplyBlock.Thinking("")), 1_100)
        acc.consume(StreamEvent.ThinkingDelta(1, "想了"), 1_200)
        acc.consume(StreamEvent.Stopped("end_turn"), 1_300)
        acc.settle(4_000)

        assertEquals(2, acc.blocks.size)
        assertNotNull((acc.blocks[1] as ReplyBlock.Thinking).endedAt)
        assertEquals("正文", acc.answer)
        assertEquals("想了", acc.thinking)
    }

    @Test
    fun `续写时新内容接在已有块之后`() {
        acc.seed(listOf(ReplyBlock.Text("前半段")))
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Text("")), 2_000)
        acc.consume(StreamEvent.TextDelta(0, "后半段"), 2_100)
        acc.consume(StreamEvent.Stopped("end_turn"), 2_200)
        acc.settle(2_300)

        assertEquals(2, acc.blocks.size)
        assertEquals("前半段后半段", acc.answer)
    }

    @Test
    fun `续写遇到安全拒绝时两段正文都在`() {
        val seeded = ReplyBlock.Text("已经写好的半篇")
        acc.seed(listOf(seeded))
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Text("这一轮写出来的")), 2_000)
        acc.consume(StreamEvent.Stopped("refusal"), 2_100)
        acc.settle(2_200)

        // 上一轮与这一轮的字都不动 —— 「继续生成」绝不能变成删稿
        assertEquals(2, acc.blocks.size)
        assertEquals("已经写好的半篇这一轮写出来的", acc.answer)
    }

    @Test
    fun `续写后块序号从 0 重新开始，不覆盖已有块`() {
        acc.seed(listOf(ReplyBlock.Text("甲")))
        acc.consume(StreamEvent.BlockStart(0, ReplyBlock.Text("乙")), 2_000)
        assertEquals(listOf("甲", "乙"), acc.blocks.map { (it as ReplyBlock.Text).text })
    }
}
