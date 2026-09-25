package com.simplechat.app.data.import

/**
 * 从图片字节里读出像素宽高。
 *
 * ### 为什么需要它
 *
 * `Attachment` 的 `width` / `height` 是**给布局用的占位比例**：UI 里
 * `ratio = if (width > 0 && height > 0) width/height else 1f` ——
 * 也就是说，**不给尺寸的图片会被当成正方形**，一张横图导进来就是压扁的。
 *
 * 所以导入外部附件时必须把尺寸填上，而不是留 0。
 *
 * ### 为什么不用 BitmapFactory
 *
 * 这里只需要**两个整数**，而 `BitmapFactory` 要 `android.graphics`，
 * 意味着这段逻辑无法在 JVM 单测里跑。图片尺寸是"错了也看不出来"的那类
 * 细节（表现只是"图有点变形"），正该拿单测钉住 —— 所以手写头解析。
 *
 * 只认 JPEG 与 PNG：Chatbox 的备份里只有这两种，其余格式返回 null，
 * 调用方会退回 `1f` 的旧行为（至少不会崩）。
 */
internal fun imageSizeOf(bytes: ByteArray): Pair<Int, Int>? {
    val size = pngSize(bytes) ?: jpegSize(bytes) ?: return null
    // 尺寸为 0 的"假数据"要当解析失败：UI 拿到 0 会退回方形占位（ratio=1f），
    // 而退回方形起码比 `aspectRatio(0f)` 崩掉强
    return size.takeIf { it.first > 0 && it.second > 0 }
}

/** PNG：8 字节签名之后紧跟 IHDR，宽高是其中第 4、5 个字段（大端）。 */
private fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 24) return null
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (!bytes.startsWith(signature)) return null
    // 8(签名) + 4(长度) + 4("IHDR") = 16 起是宽，20 起是高
    if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
        bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
    ) {
        return null
    }
    return beInt(bytes, 16) to beInt(bytes, 20)
}

/**
 * JPEG：从 `FF D8` 之后逐个跳过段，找到 SOFn（`FF C0`~`FF CF`，
 * 但 `C4` / `C8` / `CC` 是别的段）——它的第 1、2 个字段就是高和宽。
 *
 * 注意**先高后宽**，写反了图片会被转 90°，而画面本身看着还挺正常。
 */
private fun jpegSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 4) return null
    if (bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null

    var offset = 2
    // 上界是 `offset + 8`：SOFn 里最远的字段（宽）落在 `offset + 8`。
    // 写成 `offset + 9` 会把**紧贴文件末尾**的那个 SOFn 段漏掉 —— 有些工具
    // 导出的 JPEG 就没有 SOF 之后的内容，整张图会读不出尺寸。
    while (offset + 8 < bytes.size) {
        if (bytes[offset] != 0xFF.toByte()) {
            offset++
            continue
        }
        val marker = bytes[offset + 1].toInt() and 0xFF
        when {
            // SOF0..SOF15，排除 DHT(C4) / JPG(C8) / DAC(CC)
            marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC -> {
                // 注意**先高后宽**
                val height = beShort(bytes, offset + 5)
                val width = beShort(bytes, offset + 7)
                return width to height
            }
            // 无长度字段的独立标记
            marker == 0xD8 || marker == 0xD9 ||
                marker in 0xD0..0xD7 || marker == 0x01 || marker == 0xFF -> offset += 2
            else -> {
                val length = beShort(bytes, offset + 2)
                if (length < 2) return null
                offset += 2 + length
            }
        }
    }
    return null
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    return prefix.indices.all { this[it] == prefix[it] }
}

private fun beShort(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

private fun beInt(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 24) or
        ((bytes[at + 1].toInt() and 0xFF) shl 16) or
        ((bytes[at + 2].toInt() and 0xFF) shl 8) or
        (bytes[at + 3].toInt() and 0xFF)
