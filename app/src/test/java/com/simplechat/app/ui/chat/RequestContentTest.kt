package com.simplechat.app.ui.chat

import com.simplechat.app.data.Attachment
import com.simplechat.app.data.Attachments
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 请求里附件怎么摆。
 *
 * 这一层错了，表现是"模型对图片视而不见"或者"请求直接 400"，
 * 从界面上**完全看不出**是哪一环断的 —— 所以每条形态都钉死。
 */
class RequestContentTest {

    private fun textFile(name: String, body: String) = Attachment(
        kind = Attachment.Kind.TEXT,
        name = name,
        data = body,
    )

    private fun image(name: String = "shot.png") = Attachment(
        kind = Attachment.Kind.IMAGE,
        name = name,
        mime = "image/jpeg",
        data = "AAAA",
        width = 100,
        height = 50,
    )

    // ── 无附件：保持最省事的纯字符串 ──────────────────────

    @Test
    fun `no attachments stays a plain string`() {
        val content = userContent("你好", emptyList())

        assertEquals(JsonPrimitive("你好"), content)
    }

    // ── 只有文本附件：仍然是纯字符串 ──────────────────────

    @Test
    fun `text-only attachments stay a plain string with delimited blocks`() {
        val content = userContent("看看这个", listOf(textFile("notes.md", "# 标题\n正文")))

        val text = (content as JsonPrimitive).content
        assertTrue(text.startsWith("看看这个"))
        assertTrue(text.contains("<<<附件开始：notes.md>>>"))
        assertTrue(text.contains("# 标题"))
        assertTrue(text.contains("<<<附件结束：notes.md>>>"))
    }

    @Test
    fun `empty user text does not leave a leading blank line`() {
        val content = userContent("", listOf(textFile("a.txt", "内容")))

        val text = (content as JsonPrimitive).content
        assertTrue(text.startsWith("<<<附件开始：a.txt>>>"))
    }

    // ── 有图片：必须是 content block 数组 ─────────────────

    @Test
    fun `image attachment produces a content block array`() {
        val content = userContent("描述一下", listOf(image()))

        val blocks = content.jsonArray
        assertEquals(2, blocks.size)

        val textBlock = blocks[0].jsonObject
        assertEquals("text", textBlock["type"]?.jsonPrimitive?.content)
        assertEquals("描述一下", textBlock["text"]?.jsonPrimitive?.content)

        val imageBlock = blocks[1].jsonObject
        assertEquals("image_url", imageBlock["type"]?.jsonPrimitive?.content)
        val url = imageBlock["image_url"]!!.jsonObject
        assertEquals("data:image/jpeg;base64,AAAA", url["url"]?.jsonPrimitive?.content)
        // 精度固定 high：low 会把图缩到 512×512，截图里的小字会糊掉
        assertEquals(ImageDetail, url["detail"]?.jsonPrimitive?.content)
    }

    @Test
    fun `mixed attachments put file text in the text block and images as blocks`() {
        val content = userContent(
            text = "都看看",
            attachments = listOf(textFile("notes.md", "文件内容"), image()),
        )

        val blocks = content.jsonArray
        assertEquals(2, blocks.size)

        val text = blocks[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.contains("都看看"))
        assertTrue(text.contains("文件内容"))

        assertEquals("image_url", blocks[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `blank user text is omitted from the block array`() {
        // 正文为空时不留一个空 text 块 —— 官方两种都收，少一块少一点噪音
        val content = userContent("", listOf(image()))

        val blocks = content.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("image_url", blocks[0].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `multiple images each get their own block`() {
        val content = userContent("看看", listOf(image("a.jpg"), image("b.jpg")))

        val blocks = content.jsonArray
        assertEquals(3, blocks.size)
        assertTrue(blocks.drop(1).all { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" })
    }

    // ── token 估算 ────────────────────────────────────────

    @Test
    fun `image token estimate is the documented upper bound`() {
        assertEquals(ImageTokenUpperBound, attachmentTokenEstimate(listOf(image())))
    }

    @Test
    fun `text attachment tokens follow the same rule as body text`() {
        val estimate = attachmentTokenEstimate(listOf(textFile("a.txt", "hello world")))
        assertEquals(com.simplechat.app.data.TokenEstimate.of("hello world"), estimate)
    }

    @Test
    fun `no attachments cost nothing`() {
        assertEquals(0, attachmentTokenEstimate(emptyList()))
    }
}

/** 附件列的 JSON 编解码。坏数据不能让整条消息打不开。 */
class AttachmentsCodecTest {

    @Test
    fun `round trip keeps every field`() {
        val list = listOf(
            Attachment(Attachment.Kind.IMAGE, "a.png", "image/jpeg", "AAAA", 10, 20),
            Attachment(Attachment.Kind.TEXT, "b.md", "text/plain", "# hi"),
        )

        assertEquals(list, Attachments.decode(Attachments.encode(list)))
    }

    @Test
    fun `empty list encodes to null`() {
        assertEquals(null, Attachments.encode(emptyList()))
    }

    @Test
    fun `null and blank decode to empty`() {
        assertEquals(emptyList<Attachment>(), Attachments.decode(null))
        assertEquals(emptyList<Attachment>(), Attachments.decode(""))
    }

    @Test
    fun `corrupt json decodes to empty instead of throwing`() {
        assertEquals(emptyList<Attachment>(), Attachments.decode("{not json"))
    }

    @Test
    fun `prompt block is delimited on both ends`() {
        val block = Attachment(Attachment.Kind.TEXT, "n.md", data = "内容").toPromptBlock()

        assertTrue(block.contains("<<<附件开始：n.md>>>"))
        assertTrue(block.contains("<<<附件结束：n.md>>>"))
        assertTrue(block.contains("内容"))
    }
}
