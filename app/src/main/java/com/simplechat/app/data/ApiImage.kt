package com.simplechat.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.max

/**
 * Normalize request images using an actual bounded Android decode and JPEG encode.
 * File signatures and dimensions alone cannot validate pixel data, profiles or container features.
 * Local originals remain unchanged; unreadable images are omitted only from the request.
 */
object ApiImage {

    /** 转码后的最长边。与用户选图那条链路（`AttachmentImport`）保持同一规格。 */
    const val ApiImageMaxSide = 1600

    private const val JpegQuality = 88

    /** 单张图 base64 的保守上限。服务端单图上限 32MiB、整包 48MiB，这里留出余量。 */
    private const val MaxBase64Chars = 12 * 1024 * 1024

    /**
     * 单边像素上限，**故意取 4096 而不是官方的 8192**。
     *
     * 官方 Vision 指南写的是"单边 ≤8192px，但一次请求里 ≥15 张图时降到 4096px"。
     * 整理单张图时拿不到"本轮一共几张图"，而历史会话一轮带十几二十张图是常态 ——
     * 所以只能按 4096 这个保守值判定：多转码一次只损失一点清晰度，
     * 判错的代价是**整轮请求 400**（也就是这次要修的 bug）。
     */
    private const val MaxImageSide = 4096

    /** 总像素上限。和官方"总像素"口径保持一致，同时兜住畸形文件头。 */
    private const val MaxImagePixels = 30_000_000

    /**
     * 这张图的尺寸是否超过服务端能收的上限。**纯函数**，不碰 `BitmapFactory`。
     *
     * 规则（任一命中即超限）：
     * - 宽或高 > [MaxImageSide]；
     * - 宽 × 高 > [MaxImagePixels]；
     * - 宽/高 ≤ 0。尺寸必须是正数，0 或负数只可能来自解析失败或伪造的文件头，
     *   也就是"无法证明尺寸合规" —— 这种图不能原样发，否则又回到
     *   "魔数对但内容是坏的"那条被服务端拒的老路。
     *
     * 乘法用 `Long`：文件头里的宽高是 32 位字段，`width * height` 用 `Int` 会溢出成
     * 负数，那样一张巨大的图反而会被判成"没超限"。
     *
     * 为什么是纯函数：这条判定是本次修复的核心，必须能在 JVM 单测里跑
     * （`BitmapFactory` 的实际解码由 Android 设备测试覆盖）。
     */
    fun exceedsDimensionLimit(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        if (width > MaxImageSide || height > MaxImageSide) return true
        return width.toLong() * height.toLong() > MaxImagePixels
    }

    /**
     * 可识别的图片容器；[mime] 用于导入和 MIME 修正，发送时仍统一解码转为 JPEG。
     *
     * `null` 表示"内容不是服务端认的格式" —— 这类必须转码。
     */
    enum class Format(val mime: String?) {
        JPEG("image/jpeg"),
        PNG("image/png"),
        GIF("image/gif"),
        WEBP("image/webp"),

        /** 以下都是**能解码但服务端不收**的格式，转码后才发。 */
        BMP(null),
        TIFF(null),
        HEIF(null),
        AVIF(null),

        /** 认不出来（也可能是数据损坏）。 */
        UNKNOWN(null),
    }

    /** 已经可以塞进请求的图片：base64 与对应的 `media_type`。 */
    data class Prepared(val base64: String, val mediaType: String, val transcoded: Boolean)

    /**
     * 按**文件头**判断真实格式。
     *
     * 不用 `BitmapFactory`：这里只需要回一个枚举，而它要能跑在 JVM 单测里
     * （图片格式判断错一次，就是"用户所有带图会话都发不出去"）。
     */
    fun detect(bytes: ByteArray): Format {
        fun byteAt(i: Int): Int = bytes[i].toInt() and 0xFF
        fun ascii(offset: Int, text: String): Boolean {
            if (bytes.size < offset + text.length) return false
            return text.indices.all { bytes[offset + it].toInt() == text[it].code }
        }

        if (bytes.size >= 3 && byteAt(0) == 0xFF && byteAt(1) == 0xD8 && byteAt(2) == 0xFF) {
            return Format.JPEG
        }
        if (bytes.size >= 8 && byteAt(0) == 0x89 && ascii(1, "PNG")) return Format.PNG
        // GIF87a / GIF89a：动画 GIF 也认（服务端只看容器，取首帧）
        if (bytes.size >= 6 && (ascii(0, "GIF87a") || ascii(0, "GIF89a"))) return Format.GIF
        // RIFF....WEBP
        if (bytes.size >= 12 && ascii(0, "RIFF") && ascii(8, "WEBP")) return Format.WEBP
        if (bytes.size >= 2 && ascii(0, "BM")) return Format.BMP
        // TIFF 两种字节序
        if (bytes.size >= 4 && (ascii(0, "II\u002A\u0000") || ascii(0, "MM\u0000\u002A"))) {
            return Format.TIFF
        }
        // ISO BMFF：ftyp 之后是品牌。HEIC/HEIF 与 AVIF 都在这一族里
        if (bytes.size >= 12 && ascii(4, "ftyp")) {
            val brand = String(bytes, 8, 4, Charsets.ISO_8859_1)
            // mif1/msf1 是 HEIF 的通用品牌（手机相册里的 .heic 多半仍是 heic/heix）
            if (brand in HeifBrands) return Format.HEIF
            if (brand in AvifBrands) return Format.AVIF
        }
        return Format.UNKNOWN
    }

    private val HeifBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1")
    private val AvifBrands = setOf("avif", "avis")

    /**
     * 不管声明的 MIME 是什么，回一个**与内容对应**的 MIME。
     *
     * 声明值只在"内容认不出来"时才拿来兜底 —— 那时它至少比 `image/jpeg` 更接近事实，
     * 而真正发送前一定会过 [prepare]（认不出来就会被转码）。
     */
    fun mediaTypeOf(bytes: ByteArray, declared: String?): String {
        detect(bytes).mime?.let { return it }
        return declared?.trim()?.lowercase()?.takeIf { it in DeclaredMimes } ?: "image/jpeg"
    }

    private val DeclaredMimes = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    /**
     * 请求里 `media_type` 的**合法取值**只有四个（协议 schema 的 enum）。
     *
     * 兜底用：即便某个调用点忘了走 [prepare]，也不会因为 `image/jpg`、`image/heic`
     * 这类值把整轮请求打成 400。
     */
    fun legalMediaType(mime: String?): String =
        mime?.trim()?.lowercase()?.takeIf { it in DeclaredMimes } ?: "image/jpeg"

    fun prepare(bytes: ByteArray): Prepared? = prepare { bytes.inputStream() }

    /** Reopenable source keeps large imported originals off the Java heap. */
    fun prepare(open: () -> java.io.InputStream): Prepared? {
        return try {
            val key = open().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            cached(key)?.let { return it }
            val jpeg = transcodeToJpeg(open) ?: return null
            val encoded = Base64.getEncoder().encodeToString(jpeg)
            if (encoded.length > MaxBase64Chars) return null
            Prepared(encoded, "image/jpeg", transcoded = true).also { remember(key, it) }
        } catch (_: Exception) { null }
          catch (_: OutOfMemoryError) { null }
    }

    private fun transcodeToJpeg(open: () -> java.io.InputStream): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > ApiImageMaxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        var scaled: Bitmap? = null
        var solid: Bitmap? = null
        return try {
            val longest = max(decoded.width, decoded.height)
            val source = if (longest > ApiImageMaxSide) {
                val ratio = ApiImageMaxSide.toFloat() / longest
                Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1), true).also { scaled = it }
            } else decoded
            // Flatten transparency and remove source-specific profiles/animation/container metadata.
            val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { solid = it }
            android.graphics.Canvas(output).apply {
                drawColor(android.graphics.Color.WHITE)
                drawBitmap(source, 0f, 0f, null)
            }
            ByteArrayOutputStream().use { buffer ->
                if (!output.compress(Bitmap.CompressFormat.JPEG, JpegQuality, buffer)) return null
                buffer.toByteArray().takeIf { it.isNotEmpty() }
            }
        } finally {
            solid?.recycle()
            scaled?.takeIf { it !== decoded }?.recycle()
            decoded.recycle()
        }
    }

    private const val CacheChars = 8 * 1024 * 1024
    private val cache = LinkedHashMap<String, Prepared>(16, 0.75f, true)
    private var cachedChars = 0

    @Synchronized
    private fun cached(key: String): Prepared? = cache[key]

    @Synchronized
    private fun remember(key: String, value: Prepared) {
        if (value.base64.length > CacheChars) return
        cache.remove(key)?.let { cachedChars -= it.base64.length }
        cache[key] = value
        cachedChars += value.base64.length
        while (cachedChars > CacheChars) {
            val first = cache.entries.iterator().next()
            cachedChars -= first.value.base64.length
            cache.remove(first.key)
        }
    }
}
