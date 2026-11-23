package com.simplechat.app.ui.chat

import com.simplechat.app.data.Attachment
import com.simplechat.app.net.ReplyBlock
import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Test

class MessageShareTest {
    @Test fun `long screenshot limit is bounded by memory while paging stays phone sized`() {
        assertEquals(2400, shareImagePageHeight(ShareImageMode.PAGES, 512))
        assertEquals(12_000, shareImagePageHeight(ShareImageMode.LONG, 1024))
        assertTrue(shareImagePageHeight(ShareImageMode.LONG, 128) in 2401..12_000)
        assertTrue(shareImagePageHeight(ShareImageMode.LONG, 128) < shareImagePageHeight(ShareImageMode.LONG, 512))
    }
    private fun user(id: String, body: String = id) = UiMessage(id, MessageRole.USER, body)
    private fun answer(id: String, body: String = id) = UiMessage(id, MessageRole.ASSISTANT, body)

    @Test fun `exchange includes its question and consecutive answers in current branch order`() {
        val groups = messageShareGroups(listOf(answer("orphan"), user("q1"), answer("a1"), answer("continued"), user("q2"), answer("a2")))
        assertEquals(listOf(listOf("orphan"), listOf("q1", "a1", "continued"), listOf("q2", "a2")), groups.map { it.messages.map { m -> m.id } })
        val selection = MessageShareSelection()
        selection.begin("a1", groups)
        assertEquals(setOf("q1"), selection.selectedIds)
        selection.toggle(groups.last())
        selection.toggle(groups.first())
        assertEquals(groups, selection.selected(groups))
        selection.toggle(groups[1])
        assertEquals(listOf("orphan", "q2"), selection.selected(groups).map { it.id })
    }

    @Test fun `select all excludes incomplete exchanges and stale selections are removed`() {
        val groups = messageShareGroups(listOf(user("q1"), answer("a1"), user("q2"), answer("a2").copy(status = MessageStatus.STREAMING)))
        val selection = MessageShareSelection()
        selection.begin("q2", groups)
        assertFalse(selection.active)
        selection.begin("a1", groups)
        selection.toggleAll(groups)
        assertTrue(selection.selectedIds.isEmpty())
        selection.toggleAll(groups)
        assertEquals(setOf("q1"), selection.selectedIds)
        selection.reconcile(groups.drop(1))
        assertTrue(selection.selectedIds.isEmpty())
        selection.close()
        assertFalse(selection.active)
    }

    @Test fun `cancel keeps selected exchanges and closing clears multi-select mode`() {
        val groups = messageShareGroups(listOf(user("q1"), answer("a1"), user("q2"), answer("a2")))
        val selection = MessageShareSelection()
        selection.begin("a1", groups)
        selection.toggle(groups.last())

        assertEquals(setOf("q1", "q2"), selection.selectedIds)
        assertEquals(2, selection.selected(groups).size)
        selection.close()
        assertFalse(selection.active)
        assertTrue(selection.selectedIds.isEmpty())
    }

    @Test fun `markdown contains only selected bodies and attachments without private metadata`() {
        val messages = listOf(user("u", "Question\n中文😀"), answer("a", "# Title\n\n**Answer**\n\n```kt\ncode\n```").copy(
            reasoning = "PRIVATE_THINKING", model = "PRIVATE_MODEL", errorMessage = "PRIVATE_ERROR",
            attachments = listOf(Attachment(Attachment.Kind.TEXT, "notes.txt", data = "````\noriginal"),
                Attachment(Attachment.Kind.IMAGE, "photo.png", data = "PRIVATE_IMAGE_BASE64", path = "PRIVATE_PATH"))),
            user("unselected", "DO_NOT_EXPORT"))
        val document = MessageShareDocument("Title", messageShareGroups(messages).take(1))
        val writer = StringWriter()
        writeSharedMarkdown(document, writer, "我", "AI", "图片附件")
        val exported = writer.toString()
        assertTrue(exported.contains(messages[1].content))
        assertTrue(exported.contains("`````text\n````\noriginal\n`````"))
        assertTrue(exported.contains("图片附件: photo.png"))
        assertFalse(exported.contains("PRIVATE"))
        assertFalse(exported.contains("DO_NOT_EXPORT"))
        assertTrue(sharedPlainText(document, "我", "AI", "图片附件").contains("中文😀"))
    }

    @Test fun `markdown export is complete beyond message pagination limits`() {
        val body = "中文😀\n".repeat(80_000)
        val writer = StringWriter()
        writeSharedMarkdown(MessageShareDocument("large", messageShareGroups(listOf(user("u", body)))), writer, "Me", "AI", "Image")
        assertTrue(writer.toString().contains(body))
    }

    @Test fun `image reasoning is hidden by default and timeline thinking is not duplicated`() {
        val reply = answer("a", "BeforeAfter").copy(
            reasoning = "First thoughtSecond thought",
            timeline = listOf(
                ReplyBlock.Text("Before"),
                ReplyBlock.Thinking("First thought", signature = "PRIVATE_SIGNATURE"),
                ReplyBlock.Thinking("Second thought"),
                ReplyBlock.Text("After", citations = emptyList()),
            ),
        )
        assertEquals(listOf(ShareImageText("BeforeAfter")), shareImageText(reply))
        assertEquals(listOf(ShareImageText("Before"), ShareImageText("First thoughtSecond thought", reasoning = true),
            ShareImageText("After")), shareImageText(reply, hideReasoning = false))
    }

    @Test fun `image reasoning falls back to legacy when timeline thinking is missing or blank`() {
        val reply = answer("a", "Answer").copy(reasoning = "Legacy thought")
        val expected = listOf(ShareImageText("Legacy thought", reasoning = true), ShareImageText("Answer"))
        assertEquals(expected, shareImageText(reply, hideReasoning = false))
        assertEquals(expected, shareImageText(reply.copy(timeline = listOf(ReplyBlock.Thinking(" "),
            ReplyBlock.Text("Answer"))), hideReasoning = false))
        assertEquals(listOf(ShareImageText("Timeline thought", reasoning = true), ShareImageText("Answer")),
            shareImageText(reply.copy(timeline = listOf(ReplyBlock.Thinking("Timeline thought"))), hideReasoning = false))
        assertEquals(listOf(ShareImageText("Answer")), shareImageText(reply.copy(reasoning = " "), hideReasoning = false))
        assertEquals(listOf(ShareImageText("Answer")), shareImageText(reply.copy(role = MessageRole.USER), hideReasoning = false))
    }

    @Test fun `image code fences preserve code and do not swallow subsequent headings`() {
        val blocks = shareRenderBlocks("# Heading\n\n```kotlin\n**literal**\n# code\n```\n\n## End", true)
        assertTrue((blocks.first() as ShareRenderBlock.Text).source.startsWith("# Heading"))
        assertTrue(blocks.filterIsInstance<ShareRenderBlock.Text>().any { it.source.contains("**literal**\n# code") })
        assertTrue((blocks.last() as ShareRenderBlock.Text).source.contains("## End"))
        assertFalse((shareRenderBlocks("**literal user input**", false).single() as ShareRenderBlock.Text).markdown)
    }

    @Test fun `math inside table cells preserves a complete table and row count`() {
        val table = "| Name | Formula |\n| --- | --- |\n| fraction | \$\\frac{a}{b}\$ |\n| root | \$\\sqrt{x}\$ |"
        val blocks = shareRenderBlocks("Before\n\n$table\n\n\$\$\\sum_{i=1}^n i\$\$\n\nAfter", true)
        assertEquals(table, blocks.filterIsInstance<ShareRenderBlock.Table>().single().source)
        assertEquals(2, blocks.filterIsInstance<ShareRenderBlock.Table>().single().rowCount)
        assertEquals("\\sum_{i=1}^n i", blocks.filterIsInstance<ShareRenderBlock.Formula>().single().tex)
    }

    @Test fun `long native text chunks retain surrogate pairs and all content`() {
        val source = "abc😀中文".repeat(3000)
        val chunks = boundedShareChunks(source, 100)
        assertEquals(source, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 100 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun selectedExcerptsKeepInlineAndDisplayMathRenderable() {
        val source = "Area \$x^2\$ and fraction \$\\frac{a}{b}\$.\n\n\\(\\ovalbox{项链还戴着吗}\\quad\\text{\\tiny已读}\\)\n\n\\(\\ovalbox{还是还在抽屉里}\\quad\\text{\\tiny已读}\\)\n\n\\(\\ovalbox{我去感应一下}\\quad\\text{\\tiny已读}\\)\n\nGaussian integral:\n\n\$\$\n\\int_{-\\infty}^{\\infty} e^{-x^2}\\,dx = \\sqrt{\\pi}\n\$\$"
        assertTrue(shareSectionUsesMarkdown(user = false, excerpt = true, reasoning = false))
        val blocks = shareRenderBlocks(source, shareSectionUsesMarkdown(user = false, excerpt = true, reasoning = false))
        assertEquals("\\int_{-\\infty}^{\\infty} e^{-x^2}\\,dx = \\sqrt{\\pi}", blocks.filterIsInstance<ShareRenderBlock.Formula>().single().tex)
        val inline = blocks.filterIsInstance<ShareRenderBlock.Text>().first()
        assertTrue(inline.markdown)
        val allInlineFormulas = blocks.filterIsInstance<ShareRenderBlock.Text>().flatMap { text ->
            com.simplechat.app.ui.markdown.inlineMathDocument(text.source).formulas.values.map { it.tex }
        }
        assertEquals(listOf("x^2", "\\frac{a}{b}", "\\ovalbox{项链还戴着吗}\\quad\\text{\\tiny已读}",
            "\\ovalbox{还是还在抽屉里}\\quad\\text{\\tiny已读}",
            "\\ovalbox{我去感应一下}\\quad\\text{\\tiny已读}"), allInlineFormulas)
        assertFalse(shareSectionUsesMarkdown(user = true, excerpt = false, reasoning = false))
        assertFalse(shareSectionUsesMarkdown(user = false, excerpt = true, reasoning = true))
    }
}
