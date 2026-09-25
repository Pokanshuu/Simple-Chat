package com.simplechat.app.ui.chat

import com.simplechat.app.data.Attachment
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageIndexModelTest {

    /**
     * 占位文案的中文版。
     *
     * 纯 JVM 单测没有 Android Resources，所以**自己把字面量塞进来** ——
     * 这也顺带钉住了"占位长什么样"这件事；界面那边走默认值（随语言取）。
     */
    private val zhLabels = MessageIndexLabels(
        empty = { "（无内容）" },
        attachments = { count -> "（附件 $count）" },
        image = { "（图片）" },
        file = { name -> "（文件·$name）" },
    )

    private fun preview(message: UiMessage) = messageIndexPreview(message, zhLabels)

    private fun user(id: String = "u1", content: String = "", attachments: List<Attachment> = emptyList()) =
        UiMessage(id = id, role = MessageRole.USER, content = content, attachments = attachments)

    private fun assistant(id: String = "a1", content: String = "") =
        UiMessage(id = id, role = MessageRole.ASSISTANT, content = content)

    private fun image(name: String = "a.png") =
        Attachment(kind = Attachment.Kind.IMAGE, name = name, data = "AAAA")

    private fun file(name: String) =
        Attachment(kind = Attachment.Kind.TEXT, name = name, data = "正文")

    // ── 预览文本 ───────────────────────────────────────────

    @Test
    fun `body text wins over attachments`() {
        assertEquals("看这张", preview(user(content = "  看这张  ", attachments = listOf(image()))))
    }

    @Test
    fun `a single image shows the image placeholder`() {
        assertEquals("（图片）", preview(user(attachments = listOf(image()))))
    }

    @Test
    fun `several attachments show a count`() {
        assertEquals("（附件 3）", preview(user(attachments = listOf(image(), file("a.md"), file("b.md")))))
    }

    @Test
    fun `two attachments still show a count`() {
        assertEquals("（附件 2）", preview(user(attachments = listOf(image(), file("a.md")))))
    }

    @Test
    fun `a single file shows its name`() {
        assertEquals("（文件·设定.md）", preview(user(attachments = listOf(file("设定.md")))))
    }

    @Test
    fun `an image plus nothing else is not a file`() {
        assertEquals("（图片）", preview(user(attachments = listOf(image("photo.jpg")))))
    }

    @Test
    fun `a message with neither body nor attachment never leaves a blank line`() {
        assertEquals("（无内容）", preview(user()))
    }

    @Test
    fun `blank body counts as no body`() {
        assertEquals("（图片）", preview(user(content = "   \n ", attachments = listOf(image()))))
    }

    // ── 建索引 ────────────────────────────────────────────

    @Test
    fun `only user messages are listed`() {
        val entries = buildMessageIndexEntries(
            listOf(user("u1", "一"), assistant("a1", "回"), user("u2", "二")),
        )

        assertEquals(listOf("u1", "u2"), entries.map { it.id })
    }

    @Test
    fun `entries carry their index in the message list`() {
        val entries = buildMessageIndexEntries(
            listOf(assistant("a1", "回"), user("u1", "一"), assistant("a2", "回"), user("u2", "二")),
        )

        assertEquals(listOf(1, 3), entries.map { it.index })
    }

    @Test
    fun `previews are computed up front`() {
        val entries = buildMessageIndexEntries(listOf(user("u1", " 你好 ")))

        assertEquals("你好", entries.single().preview)
    }

    @Test
    fun `an empty conversation yields an empty index`() {
        assertEquals(emptyList<MessageIndexEntry>(), buildMessageIndexEntries(emptyList()))
    }

    @Test
    fun `an assistant-only conversation has no index`() {
        assertEquals(emptyList<MessageIndexEntry>(), buildMessageIndexEntries(listOf(assistant(), assistant("a2"))))
    }
}
