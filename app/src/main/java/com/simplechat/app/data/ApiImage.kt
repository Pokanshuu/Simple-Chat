package com.simplechat.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.max

/**
 * 图片**按真实内容**判断格式，并把"服务端一定收得下"的字节交给请求层。
 *
 * ### 为什么不能相信 `Attachment.mime`
 *
 * 历史里的图片来自三条完全不同的路：本应用自己压的 JPEG、DeepSeek 导出里的原件、
 * Chatbox 备份里的原件。后两条路带进来的是什么格式、`mime` 字段写的是什么，
 * 我们**从来没有保证过**。而服务端的判据只有一条：真实内容。
 *
 * 表现就是这句报错（v1.1.3 之前多位用户遇到）：
 *
 * ```text
 * messages.18.content.0: You have uploaded an unsupported image.
 * Please make sure your image is valid and in a supported image format.
 * ```
 *
 * 更糟的是它**每一轮都会复现**：坏图在历史里，每次请求都会被重新带上，
 * 于是这个会话再也发不出去 —— 用户只会觉得"导入坏了"。
 *
 * ### 服务端认什么
 *
 * 官方 Vision 指南：JPEG / PNG / GIF / WebP，**格式由实际内容判断**，
 * 不看文件名、也不看声明的 MIME。所以：
 *
 * - 内容确实是这四种 → 原样发，`media_type` 用**内容**对应的那个；
 * - 内容是别的格式（HEIC / AVIF / BMP / TIFF…）或认不出来 → 本地转成 JPEG；
 * - 转不出来（数据损坏）→ 这张图不发了，宁可少一张图，也不能让整个会话作废。
 *
 * ### 尺寸与体积
 *
 * 服务端会把图缩到约 1300×1300 再算 token，原样发几 MB 的 PNG 只是白占带宽、
 * 白等上传。所以超过 [PassthroughBytes] 的图统一转成 [ApiImageMaxSide] 的 JPEG；
 * 单张 base64 也守住 32MiB 的硬上限（见 [MaxBase64Chars]）。
 */
object ApiImage {

    /** 转码后的最长边。与用户选图那条链路（`AttachmentImport`）保持同一规格。 */
    const val ApiImageMaxSide = 1600

    private const val JpegQuality = 88

    /**
     * 原图多大以内可以原样发。
     *
     * 1.5MB 是"截图"与"照片"的分界附近：截图多半是几百 KB 的 PNG（文字清晰度靠无损），
     * 而更大的图在服务端反正会被缩小 —— 本地先缩反而省时省流量。
     */
    private const val PassthroughBytes = 1_500_000

    /** 单张图 base64 的保守上限。服务端单图上限 32MiB、整包 48MiB，这里留出余量。 */
    private const val MaxBase64Chars = 12 * 1024 * 1024

    /**
     * 可以原样发出去的四个格式；[mime] 是请求里 `media_type` 的唯一合法取值。
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

    /**
     * 把一张图整理成请求可用的形态。
     *
     * @return null 表示这张图**发不出去**（数据为空、损坏、或格式连转码都解不开）。
     *   调用方应当跳过它并如实告诉用户，而不是让整轮请求 400。
     */
    fun prepare(bytes: ByteArray): Prepared? {
        if (bytes.isEmpty()) return null
        val format = detect(bytes)

        // 快路径：内容就是服务端认的四种，且体积不大 → 原样发（无损，最省事）
        if (format.mime != null && bytes.size <= PassthroughBytes) {
            val base64 = Base64.getEncoder().encodeToString(bytes)
            if (base64.length <= MaxBase64Chars) {
                return Prepared(base64, format.mime, transcoded = false)
            }
        }

        val key = sha256(bytes)
        cached(key)?.let { return it }

        // 需要转码：格式不收，或者文件太大
        val jpeg = transcodeToJpeg(bytes)
        if (jpeg != null) {
            val base64 = Base64.getEncoder().encodeToString(jpeg)
            if (base64.length <= MaxBase64Chars) {
                val prepared = Prepared(base64, Format.JPEG.mime!!, transcoded = true)
                remember(key, prepared)
                return prepared
            }
        }

        // 转不了但内容本身是合法格式且没有超硬上限 → 原样发（例如超大 PNG 解码失败）
        if (format.mime != null) {
            val base64 = Base64.getEncoder().encodeToString(bytes)
            if (base64.length <= MaxBase64Chars) return Prepared(base64, format.mime, transcoded = false)
        }
        return null
    }

    /**
     * 解码 → 缩到 [ApiImageMaxSide] → 压成 JPEG。
     *
     * 用 `inSampleSize` 先按 2 的幂降采样，避免一张 8000×6000 的拍照图在解码阶段
     * 就吃掉上百兆堆；剩下的零头再交给 `createScaledBitmap`。
     */
    private fun transcodeToJpeg(bytes: ByteArray): ByteArray? {
        val bounds = runCatching {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { options ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        }.getOrNull() ?: return null
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var sample = 1
        while (max(width, height) / (sample * 2) >= ApiImageMaxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
            .getOrNull() ?: return null
        var scaled: Bitmap? = null
        var flattened: Bitmap? = null
        return try {
            val longest = max(decoded.width, decoded.height)
            val source = if (longest > ApiImageMaxSide) {
                val ratio = ApiImageMaxSide.toFloat() / longest
                val w = (decoded.width * ratio).toInt().coerceAtLeast(1)
                val h = (decoded.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, w, h, true).also { scaled = it }
            } else {
                decoded
            }
            // JPEG 没有透明通道：直接压会把透明区域变成黑块（与 AttachmentImport 同一个坑）
            val solid = if (source.hasAlpha()) {
                Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
                    flattened = it
                    android.graphics.Canvas(it).apply {
                        drawColor(android.graphics.Color.WHITE)
                        drawBitmap(source, 0f, 0f, null)
                    }
                }
            } else {
                source
            }
            val buffer = ByteArrayOutputStream()
            solid.compress(Bitmap.CompressFormat.JPEG, JpegQuality, buffer)
            buffer.toByteArray().takeIf { it.isNotEmpty() }
        } catch (error: Throwable) {
            // 单张图转码失败不该拖垮整轮请求，交给调用方按"这张发不出去"处理
            null
        } finally {
            runCatching { decoded.recycle() }
            scaled?.let { runCatching { it.recycle() } }
            flattened?.let { runCatching { it.recycle() } }
        }
    }

    // ── 转码结果的小缓存 ─────────────────────────────────
    //
    // 历史里的图**每轮都要重发**，同一张 HEIC 每次发送都转码一遍纯属浪费。
    // 缓存的是"转码后的 base64"，键是原图字节的哈希（内容不同绝不会撞）。

    private const val CacheChars = 8 * 1024 * 1024

    private val cache = object : LinkedHashMap<String, Prepared>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Prepared>?): Boolean {
            var total = 0
            for (value in values) {
                total += value.base64.length
                if (total > CacheChars) return true
            }
            return false
        }
    }

    @Synchronized
    private fun cached(key: String): Prepared? = cache[key]

    @Synchronized
    private fun remember(key: String, value: Prepared) {
        cache[key] = value
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }
}
