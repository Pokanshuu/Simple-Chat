package com.simplechat.app.data

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片格式判定与"发得出去"的整理。
 *
 * 这一层错了的表现最隐蔽也最贵：服务端只回一句
 * `You have uploaded an unsupported image`，而这个会话**每一轮**都会失败
 * （坏图在历史里，每轮都要重发）。所以按纯函数把判定钉死。
 */
class ApiImageTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun jpegHeader() = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46)
    private fun pngHeader() = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
    private fun webpHeader() = ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP")
    private fun isoBmff(brand: String) = bytes(0, 0, 0, 0x18) + ascii("ftyp") + ascii(brand) + bytes(0, 0, 0, 0)

    @Test
    fun `按文件头识别四种服务端支持的格式`() {
        assertEquals(ApiImage.Format.JPEG, ApiImage.detect(jpegHeader()))
        assertEquals(ApiImage.Format.PNG, ApiImage.detect(pngHeader()))
        assertEquals(ApiImage.Format.GIF, ApiImage.detect(ascii("GIF89a") + bytes(0, 0)))
        assertEquals(ApiImage.Format.WEBP, ApiImage.detect(webpHeader()))
    }

    @Test
    fun `识别服务端不收的格式，好在本地转码`() {
        assertEquals(ApiImage.Format.BMP, ApiImage.detect(ascii("BM") + bytes(0, 0, 0, 0)))
        assertEquals(ApiImage.Format.TIFF, ApiImage.detect(bytes(0x49, 0x49, 0x2A, 0x00)))
        assertEquals(ApiImage.Format.TIFF, ApiImage.detect(bytes(0x4D, 0x4D, 0x00, 0x2A)))
        assertEquals(ApiImage.Format.HEIF, ApiImage.detect(isoBmff("heic")))
        assertEquals(ApiImage.Format.HEIF, ApiImage.detect(isoBmff("mif1")))
        assertEquals(ApiImage.Format.AVIF, ApiImage.detect(isoBmff("avif")))
    }

    @Test
    fun `认不出来的内容不会被当成图片接受`() {
        assertEquals(ApiImage.Format.UNKNOWN, ApiImage.detect(bytes(0x00, 0x01, 0x02, 0x03)))
        assertEquals(ApiImage.Format.UNKNOWN, ApiImage.detect(ByteArray(0)))
        // 文本文件常被用户当图片塞进来 —— 这是"数据损坏"那一类，必须能识别出来
        assertEquals(ApiImage.Format.UNKNOWN, ApiImage.detect(ascii("hello world")))
    }

    @Test
    fun `media_type 与内容对齐，历史里写错的 mime 会被纠正`() {
        assertEquals("image/png", ApiImage.mediaTypeOf(pngHeader(), declared = "image/jpeg"))
        assertEquals("image/webp", ApiImage.mediaTypeOf(webpHeader(), declared = null))
        // 内容认不出来时才用声明值兜底（而且要合法）
        assertEquals("image/jpeg", ApiImage.mediaTypeOf(bytes(9, 9, 9), declared = "image/jpg"))
        assertEquals("image/png", ApiImage.mediaTypeOf(bytes(9, 9, 9), declared = "IMAGE/PNG"))
    }

    @Test
    fun `legalMediaType 只放行协议 enum 里的四个值`() {
        assertEquals("image/jpeg", ApiImage.legalMediaType(null))
        assertEquals("image/jpeg", ApiImage.legalMediaType(""))
        assertEquals("image/jpeg", ApiImage.legalMediaType("image/jpg"))
        assertEquals("image/jpeg", ApiImage.legalMediaType("image/heic"))
        assertEquals("image/png", ApiImage.legalMediaType(" image/png "))
        assertEquals("image/webp", ApiImage.legalMediaType("IMAGE/WEBP"))
    }

    @Test
    fun `小图原样发送，不做有损转码`() {
        val png = pngHeader() + ByteArray(1024) { it.toByte() }
        val prepared = ApiImage.prepare(png)!!
        assertEquals("image/png", prepared.mediaType)
        assertTrue("小图不该被转码", !prepared.transcoded)
        assertArrayEquals(png, Base64.getDecoder().decode(prepared.base64))
    }

    @Test
    fun `空数据不会变成一张图片`() {
        assertNull(ApiImage.prepare(ByteArray(0)))
    }

    @Test
    fun `认不出来的内容转不了码就放弃，而不是发个坏的出去`() {
        // JVM 里没有 BitmapFactory，转码必然失败 —— 正好验证"失败就返回 null"这条底线
        assertNull(ApiImage.prepare(ascii("not an image at all")))
    }
}
