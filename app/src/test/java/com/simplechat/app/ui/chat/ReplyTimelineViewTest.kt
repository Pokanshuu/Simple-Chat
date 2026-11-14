package com.simplechat.app.ui.chat

import com.simplechat.app.net.Citation
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.WebSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内容块 → 界面视图的推导。
 *
 * 这些用例钉住的是**界面上看起来"就是不对"但很难定位**的东西：
 * 角标编号错位、过程性说明被当成正文、搜索步骤顺序颠倒、读链接结束后仍占一行。
 */
class ReplyTimelineViewTest {

    private fun text(value: String, vararg citations: Citation) =
        ReplyBlock.Text(value, citations.toList())

    private fun thinking(value: String, started: Long? = 0, ended: Long? = 1000) =
        ReplyBlock.Thinking(value, startedAt = started, endedAt = ended)

    private fun searchUse(id: String = "t1", queries: List<String> = listOf("天气"), kind: String = "search") =
        ReplyBlock.ToolUse(id, "deepseek_web_search", kind, "search", queries)

    private fun searchResult(
        id: String = "t1",
        urls: List<String> = listOf("https://a/1"),
        failed: Boolean = false,
        kind: String = "search",
    ) = ReplyBlock.SearchResult(
        toolUseId = id,
        kind = kind,
        results = if (failed) emptyList() else urls.map { WebSource(it, "标题 $it", "站点") },
        failed = failed,
    )

    // ── 正文 ──────────────────────────────────────────────

    @Test
    fun `只有正文时没有过程步骤`() {
        val view = buildTimelineView(listOf(text("雨是从傍晚开始下的")), streaming = false)
        assertEquals("雨是从傍晚开始下的", view.answer)
        assertTrue(view.steps.isEmpty())
        assertFalse(view.hasSteps)
    }

    @Test
    fun `思考进过程区，不进正文`() {
        val view = buildTimelineView(
            listOf(thinking("先定视角"), text("雨是从傍晚开始下的")),
            streaming = false,
        )
        assertEquals("雨是从傍晚开始下的", view.answer)
        val step = view.steps.single() as TimelineStep.Thinking
        assertEquals("先定视角", step.text)
        assertEquals("先定视角", view.steps.filterIsInstance<TimelineStep.Thinking>().joinToString("") { it.text })
    }

    @Test
    fun `相邻思考块合并成一步`() {
        val view = buildTimelineView(
            listOf(thinking("甲"), thinking("乙"), text("正文")),
            streaming = false,
        )
        assertEquals(1, view.steps.count { it is TimelineStep.Thinking })
        assertEquals("甲乙", (view.steps.single() as TimelineStep.Thinking).text)
    }

    @Test
    fun `工具调用之前的文字成为过程说明`() {
        val view = buildTimelineView(
            listOf(text("我先查一下。"), searchUse(), searchResult(), text("查到了。")),
            streaming = false,
        )
        assertTrue(view.steps.any { it is TimelineStep.Preamble })
        assertEquals("查到了。", view.answer)
    }

    @Test
    fun `工具在最后时正文为空`() {
        val view = buildTimelineView(listOf(searchUse(), searchResult()), streaming = true)
        assertFalse(view.hasAnswer)
        assertTrue(view.hasSteps)
    }

    // ── 搜索 ──────────────────────────────────────────────

    @Test
    fun `搜索结果成为步骤并带来源`() {
        val view = buildTimelineView(
            listOf(searchUse(), searchResult(urls = listOf("https://a/1", "https://b/2")), text("答案")),
            streaming = false,
        )
        val step = view.steps.filterIsInstance<TimelineStep.Search>().single()
        assertEquals(ToolStatus.DONE, step.status)
        assertEquals(listOf("天气"), step.queries)
        assertEquals(listOf(1, 2), step.sources.map { it.index })
        assertEquals("答案", view.answer)
    }

    @Test
    fun `流未结束时无结果的搜索算进行中`() {
        val view = buildTimelineView(listOf(searchUse()), streaming = true)
        assertEquals(ToolStatus.WIP, view.steps.filterIsInstance<TimelineStep.Search>().single().status)
    }

    @Test
    fun `流已结束时无结果的搜索算失败`() {
        val view = buildTimelineView(listOf(searchUse()), streaming = false)
        assertEquals(
            ToolStatus.FAILED,
            view.steps.filterIsInstance<TimelineStep.Search>().single().status,
        )
    }

    @Test
    fun `搜索失败时标记 failed`() {
        val view = buildTimelineView(
            listOf(searchUse(), searchResult(failed = true), text("没查到")),
            streaming = false,
        )
        val step = view.steps.filterIsInstance<TimelineStep.Search>().single()
        assertEquals(ToolStatus.FAILED, step.status)
        assertTrue(step.sources.isEmpty())
    }

    @Test
    fun `普通搜索按种类聚合到首次出现的位置`() {
        val view = buildTimelineView(
            listOf(
                searchUse("t1", listOf("第一次")),
                text("中间说明"),
                searchUse("t2", listOf("第二次")),
                searchResult("t1", listOf("https://a/1")),
                searchResult("t2", listOf("https://b/2")),
                text("答案"),
            ),
            streaming = false,
        )
        val search = view.steps.filterIsInstance<TimelineStep.Search>().single()
        assertEquals(listOf("第一次", "第二次"), search.queries)
        assertEquals(2, search.sources.size)
    }

    @Test
    fun `边想边搜只合并相邻的同种调用`() {
        val view = buildTimelineView(
            listOf(
                searchUse("t1", listOf("甲"), kind = "deep_search"),
                searchUse("t2", listOf("乙"), kind = "deep_search"),
                thinking("想一想"),
                searchUse("t3", listOf("丙"), kind = "deep_search"),
                text("答案"),
            ),
            streaming = false,
        )
        val searches = view.steps.filterIsInstance<TimelineStep.Search>()
        assertEquals(2, searches.size)
        assertEquals(listOf("甲", "乙"), searches[0].queries)
        assertEquals(listOf("丙"), searches[1].queries)
    }

    // ── 引用角标 ──────────────────────────────────────────

    @Test
    fun `正文引用按首次出现编号并插角标`() {
        val view = buildTimelineView(
            listOf(
                text(
                    "第一句",
                    Citation("https://a/1", "甲"),
                    Citation("https://b/2", "乙"),
                ),
                text("第二句", Citation("https://a/1", "甲")),
            ),
            streaming = false,
        )
        assertEquals(
            "第一句[¹](#cite-1)[²](#cite-2)第二句[¹](#cite-1)",
            view.answer,
        )
        assertEquals(2, view.sources.size)
    }

    @Test
    fun `搜索结果先于正文引用编号`() {
        val view = buildTimelineView(
            listOf(
                searchUse(),
                searchResult(urls = listOf("https://a/1")),
                text("正文", Citation("https://c/3", "丙")),
            ),
            streaming = false,
        )
        assertEquals(
            listOf("https://a/1", "https://c/3"),
            view.sources.map { it.url },
        )
        assertEquals("[²](#cite-2)", view.answer.takeLast(12))
    }

    @Test
    fun `角标插在块尾换行之前`() {
        val view = buildTimelineView(
            listOf(text("正文\n\n", Citation("https://a/1", "甲"))),
            streaming = false,
        )
        assertEquals("正文[¹](#cite-1)\n\n", view.answer)
    }

    @Test
    fun `解析角标为真实链接`() {
        val sources = listOf(
            SourceRef(1, "https://a/1", "甲", "a", null, null, null),
            SourceRef(2, "https://b/2?x=(y)", "乙", "b", null, null, null),
        )
        val resolved = resolveCitationLinks("正文[¹](#cite-1)与[²](#cite-2)", sources)
        assertEquals("正文[¹](https://a/1)与[²](https://b/2?x=%28y%29)", resolved)
    }

    @Test
    fun `未知编号的角标原样保留`() {
        val resolved = resolveCitationLinks("正文[³](#cite-3)", emptyList())
        assertEquals("正文[³](#cite-3)", resolved)
    }

    @Test
    fun `上标数字支持多位`() {
        assertEquals("¹", superscript(1))
        assertEquals("¹⁰", superscript(10))
        assertEquals("⁰", superscript(0))
    }

    // ── 读链接 / 打开网页 / 页内查找 ───────────────────────

    @Test
    fun `读链接进行中时展示，结束后隐藏`() {
        val use = ReplyBlock.ToolUse(
            id = "rl1",
            name = "deepseek_read_url",
            kind = "read_link",
            action = "read_link",
            url = "https://e.com",
        )
        val running = buildTimelineView(listOf(use), streaming = true)
        assertEquals(1, running.steps.filterIsInstance<TimelineStep.ReadLink>().size)

        val done = buildTimelineView(
            listOf(
                use,
                ReplyBlock.FetchResult("rl1", "read_link", "https://e.com", "页面", signature = "s"),
                text("读完了"),
            ),
            streaming = false,
        )
        assertTrue(done.steps.none { it is TimelineStep.ReadLink })
        assertEquals("读完了", done.answer)
    }

    @Test
    fun `边想边搜打开的网页归到一步`() {
        val view = buildTimelineView(
            listOf(
                ReplyBlock.ToolUse("o1", "deepseek_web_search", "deep_search", "open_page", url = "https://a/1"),
                ReplyBlock.ToolUse("o2", "deepseek_web_search", "deep_search", "open_page", url = "https://b/2"),
                ReplyBlock.FetchResult("o1", "deep_search", "https://a/1", "甲页"),
                ReplyBlock.FetchResult("o2", "deep_search", "https://b/2", "乙页"),
                text("答案"),
            ),
            streaming = false,
        )
        val step = view.steps.filterIsInstance<TimelineStep.Open>().single()
        assertEquals(listOf("甲页", "乙页"), step.pages.map { it.title })
    }

    @Test
    fun `页内查找归到一步`() {
        val view = buildTimelineView(
            listOf(
                ReplyBlock.ToolUse("f1", "deepseek_web_search", "deep_search", "find_in_page", url = "https://a/1", pattern = "结局"),
                ReplyBlock.ToolUse("f2", "deepseek_web_search", "deep_search", "find_in_page", url = "https://a/1", pattern = "番外"),
                text("答案"),
            ),
            streaming = true,
        )
        val step = view.steps.filterIsInstance<TimelineStep.Find>().single()
        assertEquals(listOf("结局", "番外"), step.patterns.map { it.pattern })
    }

    // ── 思考用时 ──────────────────────────────────────────

    @Test
    fun `思考用时按块累加`() {
        val view = buildTimelineView(
            listOf(thinking("甲", 0, 1000), thinking("乙", 2000, 3500), text("正文")),
            streaming = false,
        )
        assertEquals(2500L, view.thinkingMillis)
        assertEquals(3L, view.thinkingSeconds())
    }

    @Test
    fun `缺少起止时刻时不给用时`() {
        val view = buildTimelineView(
            listOf(ReplyBlock.Thinking("甲", startedAt = null), text("正文")),
            streaming = false,
        )
        assertNull(view.thinkingMillis)
        assertNull(view.thinkingSeconds())
    }

    @Test
    fun `不足一秒的思考按一秒算`() {
        val view = buildTimelineView(listOf(thinking("甲", 0, 300), text("正文")), streaming = false)
        assertEquals(1L, view.thinkingSeconds())
    }
}
