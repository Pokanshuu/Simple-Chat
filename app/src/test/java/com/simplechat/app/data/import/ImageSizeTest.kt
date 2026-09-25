package com.simplechat.app.data.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图片头解析。
 *
 * 这段逻辑的错法都很隐蔽：JPEG 的 SOFn 段里是**先高后宽**，写反了图片会被
 * 转 90°；而 PNG 的宽高在 `IHDR` 里，偏移算错就取到别人家的字段。
 * 两种都不会报错，只会"图有点不对劲"。
 */
class ImageSizeTest {

    /** 最小 PNG 头：签名 + IHDR 长度 + "IHDR" + 宽 + 高。 */
    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArray(33)
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        signature.copyInto(out, 0)
        // 8..11 长度（IHDR 固定 13）
        out[11] = 13
        "IHDR".forEachIndexed { i, c -> out[12 + i] = c.code.toByte() }
        putBeInt(out, 16, width)
        putBeInt(out, 20, height)
        return out
    }

    /** 最小 JPEG 头：SOI + SOF0 段（先高后宽）。 */
    private fun jpeg(width: Int, height: Int): ByteArray {
        val out = ByteArray(20)
        out[0] = 0xFF.toByte(); out[1] = 0xD8.toByte() // SOI
        out[2] = 0xFF.toByte(); out[3] = 0xC0.toByte() // SOF0
        putBeShort(out, 4, 17)                          // 段长
        out[6] = 8                                      // 精度
        putBeShort(out, 7, height)                      // ← 先高
        putBeShort(out, 9, width)                       // ← 后宽
        return out
    }

    private fun putBeInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte(); b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte(); b[at + 3] = v.toByte()
    }

    private fun putBeShort(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 8).toByte(); b[at + 1] = v.toByte()
    }

    @Test
    fun `reads png dimensions`() {
        assertEquals(1920 to 1080, imageSizeOf(png(1920, 1080)))
    }

    @Test
    fun `reads jpeg dimensions without swapping them`() {
        // 横图：宽必须大于高。写反的话这里会得到 1080 to 1920
        val size = imageSizeOf(jpeg(1920, 1080))
        assertEquals(1920, size?.first)
        assertEquals(1080, size?.second)
    }

    @Test
    fun `handles non-square images in both orientations`() {
        assertEquals(800 to 1200, imageSizeOf(jpeg(800, 1200)))
        assertEquals(800 to 1200, imageSizeOf(png(800, 1200)))
    }

    @Test
    fun `skips jpeg segments before the frame header`() {
        // 真实的 JPEG 在 SOF 之前还有 APP0/JFIF、可能还有 EXIF 段，
        // 不是"SOI 之后就是 SOF"
        val app0 = byteArrayOf(
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
            0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        )
        val sof = byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08)
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + sof +
            byteArrayOf(0x04, 0x38, 0x07, 0x80.toByte())

        assertEquals(1920 to 1080, imageSizeOf(bytes))
    }

    @Test
    fun `skips the exif thumbnail inside an app1 segment`() {
        // APP1(EXIF) 里常常**内嵌一张缩略图**，那段数据里也有 FF C0 之类的字节。
        // 不按段长跳过去、而是硬扫标记的话，会读到缩略图的尺寸
        val thumbnail = byteArrayOf(
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08, 0x00, 0xA0.toByte(), 0x00, 0xF0.toByte(),
        )
        val app1Body = byteArrayOf(0xFF.toByte(), 0xE1.toByte()) +
            byteArrayOf(0x00, (thumbnail.size + 2).toByte()) + thumbnail
        val sof = byteArrayOf(
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08, 0x04, 0x38, 0x07, 0x80.toByte(),
        )
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1Body + sof

        assertEquals(1920 to 1080, imageSizeOf(bytes))
    }

    @Test
    fun `returns null for formats it does not know`() {
        assertNull(imageSizeOf(ByteArray(0)))
        assertNull(imageSizeOf("not an image at all".toByteArray()))
        assertNull(imageSizeOf(byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))) // GIF
    }

    @Test
    fun `returns null instead of throwing on truncated headers`() {
        // 截断的字节不能让导入整个崩掉 —— 大不了退回方形占位
        assertNull(imageSizeOf(png(100, 100).copyOf(20)))
        assertNull(imageSizeOf(jpeg(100, 100).copyOf(6)))
        assertNull(imageSizeOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    @Test
    fun `returns null on a zero or bogus size`() {
        assertNull(imageSizeOf(png(0, 0)))
    }
}
