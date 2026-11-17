package com.simplechat.app.data

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片格式判定与"发得出去"的整理。
 *
 * 这一层错了的表现最隐蔽也最贵：服务端只回一句
 * `You have uploaded an unsupported image`，而这个会话**每一轮**都会失败
 * （坏图在历史里，每轮都要重发）。所以按纯函数把判定钉死。
 *
 * 注意：JVM 单测里 `android.graphics.BitmapFactory` 是 stub（构造 `Options` 就抛
 * "not mocked"），所以这些用例**不依赖**真实解码：格式靠文件头判断，尺寸靠
 * [ApiImage.prepare] 内部的纯头解析兜底；涉及转码的用例只断言"转不了码时的取舍"。
 */
class ApiImageTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun jpegHeader() = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46)
    private fun pngHeader() = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
    private fun webpHeader() = ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP")
    private fun isoBmff(brand: String) = bytes(0, 0, 0, 0x18) + ascii("ftyp") + ascii(brand) + bytes(0, 0, 0, 0)

    private fun putBeInt(out: ByteArray, at: Int, value: Int) {
        out[at] = (value ushr 24).toByte()
        out[at + 1] = (value ushr 16).toByte()
        out[at + 2] = (value ushr 8).toByte()
        out[at + 3] = value.toByte()
    }

    /**
     * 带**真实 IHDR 尺寸**的 PNG 文件头。
     *
     * 光有 8 字节签名不够：[ApiImage.prepare] 要从头里读宽高来判断是否超限，
     * 读不出尺寸的图会被当成损坏而丢弃（这本身就是要测的行为），
     * 所以"能原样发"的测试图必须是一个尺寸可解析的 PNG。
     */
    private fun pngOfSize(width: Int, height: Int): ByteArray {
        val out = ByteArray(24)
        pngHeader().copyInto(out)
        "IHDR".forEachIndexed { i, c -> out[12 + i] = c.code.toByte() }
        putBeInt(out, 16, width)
        putBeInt(out, 20, height)
        return out
    }

    /** 带 SOF0 的最小 JPEG 头（段里**先高后宽**，与 `ImageSize` 的解析口径一致）。 */
    private fun jpegOfSize(width: Int, height: Int): ByteArray {
        val out = ByteArray(16)
        out[0] = 0xFF.toByte()
        out[1] = 0xD8.toByte() // SOI
        out[2] = 0xFF.toByte()
        out[3] = 0xC0.toByte() // SOF0
        out[4] = 0x00.toByte()
        out[5] = 17.toByte() // 段长（解析时按它跳过本段）
        out[6] = 8.toByte() // 精度
        out[7] = (height ushr 8).toByte()
        out[8] = height.toByte()
        out[9] = (width ushr 8).toByte()
        out[10] = width.toByte()
        return out
    }

    // ── 格式判定（行为不变） ─────────────────────────────

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

    // ── 尺寸上限（本次修复新增） ─────────────────────────

    @Test
    fun `尺寸上限边界：单边都不超过 4096 才放行`() {
        // 官方限制是单边 ≤8192，但一次请求带 ≥15 张图时降到 4096。
        // 调用方整理单张图时拿不到本轮总张数，所以统一按 4096 这个保守值判定：
        // 多转码一次只损失一点清晰度，判错则是整轮请求 400。
        assertFalse(ApiImage.exceedsDimensionLimit(4096, 4096))
        assertFalse(ApiImage.exceedsDimensionLimit(4096, 1000))
        assertFalse(ApiImage.exceedsDimensionLimit(1600, 900))

        assertTrue(ApiImage.exceedsDimensionLimit(4097, 1000))
        assertTrue(ApiImage.exceedsDimensionLimit(1000, 4097))
        // 6000×4000 的像素数（24M）其实没超过 30M，靠的是单边规则 —— 必须命中
        assertTrue(ApiImage.exceedsDimensionLimit(6000, 4000))
        assertTrue(ApiImage.exceedsDimensionLimit(8192, 8192))
        assertTrue(ApiImage.exceedsDimensionLimit(16_384, 1024))
    }

    @Test
    fun `尺寸为 0 或负数一律当超限`() {
        // 尺寸必须是正数：0 / 负数只可能来自解析失败或伪造的文件头。
        // 这种图"无法证明尺寸合规"，必须走转码或丢弃，不能从快路径漏出去 ——
        // 否则又回到"魔数对但内容是坏的"那条被服务端拒的老路。
        assertTrue(ApiImage.exceedsDimensionLimit(0, 0))
        assertTrue(ApiImage.exceedsDimensionLimit(0, 1000))
        assertTrue(ApiImage.exceedsDimensionLimit(1000, 0))
        assertTrue(ApiImage.exceedsDimensionLimit(-1, 1000))
        assertTrue(ApiImage.exceedsDimensionLimit(1000, -1))
    }

    @Test fun `header-only files cannot prove pixel data is valid`() {
        // Android decoding is exercised by the device suite. JVM stubs must never cause passthrough.
        assertNull(ApiImage.prepare(pngOfSize(100, 100) + ByteArray(1024)))
        assertNull(ApiImage.prepare(jpegOfSize(800, 600) + ByteArray(512)))
        assertNull(ApiImage.prepare(ByteArray(0)))
        assertNull(ApiImage.prepare(ascii("not an image")))
    }
}
