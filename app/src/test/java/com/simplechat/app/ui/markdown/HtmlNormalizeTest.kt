package com.simplechat.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HTML → Markdown 折算。
 *
 * 这一层错了，表现是"内容少了"或"正文里一堆尖括号" ——
 * 渲染器对 `HTML_BLOCK` / `HTML_TAG` 没有对应组件，标签与内容都可能消失。
 * 认不出的标签必须**只去标签、留内容**，所以把每类标签都钉死。
 */
class HtmlNormalizeTest {

    // 占位文字自己塞进来:纯 JVM 单测不碰 Android Resources(界面那边随语言取)
    private fun normalize(input: String) = normalizeHtml(input, imageAlt = { "图片" })

    // ── 行内 ─────────────────────────────────────────────

    @Test
    fun `br becomes a hard line break`() {
        assertEquals("a  \nb", normalize("a<br>b"))
    }

    @Test
    fun `self closing br is accepted`() {
        assertEquals("a  \nb", normalize("a<br/>b"))
    }

    @Test
    fun `bold tags become emphasis markers`() {
        assertEquals("**粗**", normalize("<b>粗</b>"))
        assertEquals("**粗**", normalize("<strong>粗</strong>"))
    }

    @Test
    fun `italic tags become emphasis markers`() {
        assertEquals("*斜*", normalize("<i>斜</i>"))
        assertEquals("*斜*", normalize("<em>斜</em>"))
    }

    @Test
    fun `strike tags become tildes`() {
        assertEquals("~~删~~", normalize("<del>删</del>"))
        assertEquals("~~删~~", normalize("<s>删</s>"))
    }

    @Test
    fun `inline code tag becomes a backtick span`() {
        assertEquals("`码`", normalize("<code>码</code>"))
    }

    @Test
    fun `tag names are case insensitive`() {
        assertEquals("**x**", normalize("<B>x</B>"))
    }

    @Test
    fun `attributes are ignored for paired tags`() {
        assertEquals("**粗**", normalize("""<b class="x">粗</b>"""))
    }

    // ── 块级 ─────────────────────────────────────────────

    @Test
    fun `heading tags become atx headings`() {
        assertEquals("\n### 标题\n", normalize("<h3>标题</h3>"))
    }

    @Test
    fun `p tag becomes a paragraph boundary`() {
        assertEquals("一\n\n二", normalize("一<p>二"))
    }

    @Test
    fun `li becomes a bullet item`() {
        assertEquals("- 项\n", normalize("<li>项</li>"))
    }

    @Test
    fun `hr becomes a thematic break`() {
        assertEquals("\n---\n", normalize("<hr>"))
    }

    @Test
    fun `unknown tags are stripped but content is kept`() {
        // 认不出的标签不能把内容吞掉 —— 少一点格式没事，字不能丢。
        assertEquals("\n标题\n内容\n", normalize("<details><summary>标题</summary>内容</details>"))
    }

    @Test
    fun `self closing block tag produces no stray boundary`() {
        // 自闭合不该留下"关闭"那一半的语义，也不该多一段空白。
        assertEquals("", normalize("<div/>"))
    }

    @Test
    fun `repeated blank lines collapse to one`() {
        assertEquals("一\n\n二", normalize("一</p><p>二"))
    }

    @Test
    fun `tags that only produce blank lines collapse to nothing`() {
        assertEquals("", normalize("</p><p>"))
    }

    // ── 链接与图片 ───────────────────────────────────────

    @Test
    fun `anchor becomes an inline link`() {
        assertEquals("[链接](https://a.b)", normalize("""<a href="https://a.b">链接</a>"""))
    }

    @Test
    fun `anchor without href keeps its text`() {
        assertEquals("文字", normalize("""<a name="x">文字</a>"""))
    }

    @Test
    fun `image keeps its alt text without making an image node`() {
        // 不加载远程图：造图片节点只会在正文里留一块空白。
        assertEquals("图", normalize("""<img src="a.png" alt="图">"""))
    }

    @Test
    fun `image without alt keeps the src`() {
        assertEquals("a.png", normalize("""<img src="a.png">"""))
    }

    @Test
    fun `image without src or alt becomes a placeholder`() {
        assertEquals("图片", normalize("""<img>"""))
    }

    // ── 不能动的地方 ─────────────────────────────────────

    @Test
    fun `autolink is left alone`() {
        // `<https://…>` 长得像标签，其实是链接。
        assertEquals("<https://a.b/c>", normalize("<https://a.b/c>"))
    }

    @Test
    fun `email autolink is left alone`() {
        assertEquals("<a@b.com>", normalize("<a@b.com>"))
    }

    @Test
    fun `inline code is left alone`() {
        assertEquals("`<div>`", normalize("`<div>`"))
    }

    @Test
    fun `fenced code is left alone`() {
        val input = "```html\n<div>hi</div>\n```"
        assertEquals(input, normalize(input))
    }

    @Test
    fun `entities are decoded`() {
        // 实测：渲染器的 EntityConverter 不还原这些，屏幕上会原样显示 `&amp;`。
        assertEquals("A & B C", normalize("A &amp; B&nbsp;C"))
    }

    @Test
    fun `numeric entities are decoded`() {
        assertEquals("A'B", normalize("A&#39;B"))
    }

    @Test
    fun `decoded angle brackets are escaped so they stay literal`() {
        // 还原出的 `<` 会被当成 HTML 标签（而标签没有渲染组件），要保住字面量。
        assertEquals("\\<div\\>", normalize("&lt;div&gt;"))
    }

    @Test
    fun `markdown image keeps its alt text without making an image node`() {
        // 不加载远程图：图片节点只会在正文里留一大块空白。
        assertEquals("Markdown 图片：MD 图片", normalize("Markdown 图片：![MD 图片](https://a.b/c.png)"))
    }

    @Test
    fun `plain text is unchanged`() {
        val input = "一 < 二，三 > 二。"
        assertEquals(input, normalize(input))
    }

    // ── 组合 ─────────────────────────────────────────────

    @Test
    fun `mixed inline tags round trip`() {
        assertEquals("**粗**和*斜*", normalize("<b>粗</b>和<i>斜</i>"))
    }

    // ── 引用式链接 ───────────────────────────────────────

    @Test
    fun `reference link becomes an inline link`() {
        // 定义与用法常常被切进不同的块，分块解析查不到定义 → 退回字面。
        val input = "[ref]: https://example.com\n引用式链接：[引用文字][ref]"

        assertEquals("引用式链接：[引用文字](https://example.com)", normalize(input))
    }

    @Test
    fun `collapsed reference link uses its own text as the id`() {
        val input = "[ref]: https://example.com\n看 [ref][]"

        assertEquals("看 [ref](https://example.com)", normalize(input))
    }

    @Test
    fun `empty reference text falls back to the title`() {
        // 移动端没有悬停，标题无处显示 —— 只有文字为空时才拿它当文字。
        val input = "[ref]: https://example.com \"只有标题\"\n看 [][ref]"

        assertEquals("看 [只有标题](https://example.com)", normalize(input))
    }

    @Test
    fun `reference image keeps only its text`() {
        val input = "[ref]: https://example.com/a.png\n看 ![图][ref]"

        assertEquals("看 图", normalize(input))
    }

    @Test
    fun `unknown reference is left as literal text`() {
        assertEquals("看 [文字][nope]", normalize("看 [文字][nope]"))
    }

    @Test
    fun `reference definition lines are removed`() {
        val input = "[ref]: https://example.com\n看 [ref][]"

        assertEquals("看 [ref](https://example.com)", normalize(input))
    }

    @Test
    fun `reference links inside code fences are left alone`() {
        val input = "```\n[ref]: a\n[x][ref]\n```"

        assertEquals(input, normalize(input))
    }
}
