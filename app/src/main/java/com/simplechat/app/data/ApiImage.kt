package com.simplechat.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.simplechat.app.data.import.imageSizeOf
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
 *
 * 但**体积合规不等于尺寸合规**：官方 Vision 限制是单边 ≤8192px，且一次请求带
 * ≥15 张图时收紧到 4096px。历史里一张 9000×9000 的小体积 PNG，旧逻辑只看了
 * 魔数和体积就原样透传，结果被服务端 400 拒 —— 而坏图留在历史里，**每一轮都会
 * 重发、每一轮都失败**。所以原样透传前必须查 [exceedsDimensionLimit]：超限的
 * 统一转码缩小；连文件头都解不出尺寸（数据损坏）的直接不发（见 [prepare]）。
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
     *
     * 注意它只管**体积**：能不能原样发还要看 [exceedsDimensionLimit]，
     * 两张图都 ≤1.5MB，不代表它们的像素尺寸都合规。
     */
    private const val PassthroughBytes = 1_500_000

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
     * （`BitmapFactory` 在 JVM 单测里只是 stub，见 [dimensionsOf]）。
     */
    fun exceedsDimensionLimit(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        if (width > MaxImageSide || height > MaxImageSide) return true
        return width.toLong() * height.toLong() > MaxImagePixels
    }

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
     * 决策顺序见方法体；核心变化是：**原样透传必须同时满足"格式合法 + 体积合规 +
     * 尺寸合规"**，缺一条就转码；转码也不行就宁可不发这张图，也不让整轮请求 400。
     *
     * @return null 表示这张图**发不出去**（数据为空、损坏、或格式连转码都解不开）。
     *   调用方应当跳过它并如实告诉用户，而不是让整轮请求 400。
     */
    fun prepare(bytes: ByteArray): Prepared? {
        if (bytes.isEmpty()) return null
        val format = detect(bytes)

        // 读出**真实**像素宽高（只解文件头，不分配位图）。
        // 尺寸拿不到时 dimensionsOk = false：即"数据损坏"或"环境没有 BitmapFactory"，
        // 两种情况都不能走快路径 —— 无法证明尺寸合规的图不允许原样发。
        val size = dimensionsOf(bytes)
        val dimensionsOk = size != null && !exceedsDimensionLimit(size.first, size.second)

        // ① 快路径：内容就是服务端认的四种 + 体积不大 + **尺寸合规** → 原样发（无损、零转码）。
        //    尺寸这一条是本次修复的关键：旧逻辑只看魔数和体积，一张 9000×9000 的
        //    "合法 PNG" 会被原样透传，然后被服务端按尺寸拒掉，而且每轮都重发。
        if (format.mime != null && bytes.size <= PassthroughBytes && dimensionsOk) {
            val base64 = Base64.getEncoder().encodeToString(bytes)
            if (base64.length <= MaxBase64Chars) {
                return Prepared(base64, format.mime, transcoded = false)
            }
        }

        val key = sha256(bytes)
        cached(key)?.let { return it }

        // ② 需要转码：格式不收 / 体积太大 / 尺寸超限 / 尺寸解不出来（数据损坏也要试一次，
        //    万一只是"头读不出来但能解码"的怪文件，缩成 1600 的 JPEG 就能发）。
        val jpeg = transcodeToJpeg(bytes)
        if (jpeg != null) {
            val base64 = Base64.getEncoder().encodeToString(jpeg)
            if (base64.length <= MaxBase64Chars) {
                val prepared = Prepared(base64, Format.JPEG.mime!!, transcoded = true)
                remember(key, prepared)
                return prepared
            }
        }

        // ③ 转码失败（数据坏、解码内存不够…）。这里**不能再无条件**把原图发出去：
        //    旧逻辑只要求"内容格式合法"就原样发，尺寸超限的图正是被服务端拒的那条路。
        //    允许原样发的唯一情况：格式合法 + 尺寸已知且合规（+ base64 没超硬上限）。
        //    注意能走到这里的合法格式图，实际上只可能是"体积 > 1.5MB 但尺寸合规"那一档
        //    （小图在快路径就返回了）：这类图转码失败时原样发至少不会因为尺寸被拒。
        if (format.mime != null && dimensionsOk) {
            val base64 = Base64.getEncoder().encodeToString(bytes)
            if (base64.length <= MaxBase64Chars) return Prepared(base64, format.mime, transcoded = false)
        }

        // ④ 尺寸未知/超限、格式不收、或连转码结果都超过硬上限 → 这张图不发。
        //    调用方已有"丢弃计数 + 提示"的通道，宁可少一张图，也不能让会话作废。
        return null
    }

    /**
     * 只读文件头拿**真实**像素宽高（不分配位图）。
     *
     * 主力是 `BitmapFactory`：JPEG/PNG/GIF/WebP 四种容器、EXIF 方向、各种畸形头
     * 它都处理过。自己手写解析器漏一种，就是"该拒的没拒"，代价是整轮请求 400。
     *
     * 但要区分两种"没拿到尺寸"，它们的含义完全不同：
     * - 调用成功、但 outWidth/outHeight ≤ 0 → 系统认为这张图**解不了** →
     *   按**数据损坏**处理，返回 null（[prepare] 随后会试一次转码，仍失败就丢弃）；
     * - 调用**直接抛异常** → 当前环境**没有** BitmapFactory（JVM 单测里 `android.jar`
     *   是 stub，构造 `Options` 就会抛 "not mocked"）→ 退回纯头解析 [imageSizeOf]
     *   （只认 PNG/JPEG，正好覆盖单测用到的两种格式），让"尺寸是否超限"这条最关键的
     *   判定能在 JVM 上被单测钉住。真机上不会走到这个分支。
     */
    private fun dimensionsOf(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = runCatching {
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { options ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        }
        // 环境里没有 BitmapFactory（JVM 单测）≠ 图片损坏，走纯头解析兜底
        if (bounds.isFailure) return imageSizeOf(bytes)
        val options = bounds.getOrNull() ?: return null
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
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
    //
    // 只缓存**已通过尺寸/格式校验**的转码结果：进缓存的前提是 `transcodeToJpeg`
    // 成功（输出已经是 ≤1600 的 JPEG，尺寸与格式都由我们自己保证）。尺寸超限或
    // 解不开的原图根本走不到 `remember`，所以不会出现"上一轮校验过的坏字节"被
    // 缓存下来、下一轮直接发出去的情况。

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
