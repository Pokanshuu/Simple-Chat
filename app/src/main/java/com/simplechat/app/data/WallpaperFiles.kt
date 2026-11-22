package com.simplechat.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.geometry.Rect
import java.io.File

/**
 * 聊天背景壁纸的文件管理。
 *
 * 与字体同一个思路：**只把文件名存 DataStore，字节落到 `filesDir/wallpaper/`**。
 * 壁纸是整屏图，动辄几 MB，绝不能进设置流。
 *
 * 存进去之前统一**压成 JPEG 并限制最长边**：用户随手拍的照片可能 4000×3000，
 * 直接当背景图（还叠着气泡）既吃内存又没有任何视觉收益 —— 屏幕就那么大。
 */
object WallpaperFiles {

    private const val Dir = "wallpaper"
    private const val FileName = "wallpaper.jpg"
    private const val SourceName = "wallpaper_source.jpg"
    private const val SourceMaxSide = 2560

    /** 最长边上限：1440px。平板宽屏也够用，4K 原图纯属浪费。 */
    private const val MaxSide = 1440

    fun dir(context: Context): File = File(context.filesDir, Dir)

    /**
     * 壁纸文件。每次裁剪都换一个文件名（带时间戳）：聊天页按路径决定要不要重新解码，
     * 同名覆盖会让它一直显示旧图。`name` 为空时按老版本的固定文件名找。
     */
    fun fileOf(context: Context, name: String? = null): File = File(dir(context), name?.takeIf { it.isNotBlank() } ?: FileName)

    /**
     * 导入一张壁纸：解码 → 缩放到 [MaxSide] 以内 → 存成 JPEG。
     *
     * @return 成功返回文件名；读不出来返回 null。
     */
    fun import(context: Context, uri: Uri): String? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
        val scaled = scaleDown(bitmap)
        return runCatching {
            dir(context).mkdirs()
            fileOf(context).outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            FileName
        }.getOrNull()
    }

    /**
     * 裁剪用的原图：选图时先存一份（最长边 [SourceMaxSide]），之后「重新裁剪」都从它出发 ——
     * 否则每次都在上一次裁过的图上再裁，只会越裁越小。
     */
    fun sourceOf(context: Context): File = File(dir(context), SourceName)

    /** 解码选中的图片（摆正 EXIF 方向、限制尺寸）。保存主题前不写文件。 */
    fun decodeSource(context: Context, uri: Uri): Bitmap? = runCatching {
        val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > SourceMaxSide) {
                    val r = SourceMaxSide.toFloat() / longest
                    decoder.setTargetSize((info.size.width * r).toInt().coerceAtLeast(1), (info.size.height * r).toInt().coerceAtLeast(1))
                }
            }
        } else {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } ?: return null
        scaleDown(bitmap, SourceMaxSide).also { if (it !== bitmap) bitmap.recycle() }
    }.getOrNull()

    private fun sourceFor(context: Context, name: String): File = File(dir(context), "source_$name")

    private fun existingSource(context: Context, name: String): File? =
        sourceFor(context, name).takeIf { it.exists() } ?: sourceOf(context).takeIf { it.exists() }

    internal data class CropSource(val bitmap: Bitmap, val rect: Rect?, val usesOriginal: Boolean)

    private fun cropFile(context: Context, name: String): File = File(dir(context), "crop_$name.txt")

    /** Older wallpapers have no crop coordinates. Start from the saved framing in that case. */
    internal fun loadSource(context: Context, current: String): CropSource? {
        val rect = runCatching {
            val (left, top, right, bottom) = cropFile(context, current).readText().split(',').map(String::toFloat)
            Rect(left, top, right, bottom)
        }.getOrNull()
        if (rect != null) {
            val original = BitmapFactory.decodeFile(sourceFor(context, current).absolutePath)
            if (original != null) {
                if (rect.left >= 0f && rect.top >= 0f && rect.width > 0f && rect.height > 0f &&
                    rect.right <= original.width && rect.bottom <= original.height
                ) return CropSource(original, rect, usesOriginal = true)
                original.recycle()
            }
        }
        return BitmapFactory.decodeFile(fileOf(context, current).absolutePath)
            ?.let { CropSource(it, null, usesOriginal = false) }
    }

    /** 写入一对新文件，设置指向它们之后才清理旧图。 */
    internal fun saveCropped(context: Context, cropped: Bitmap, source: Bitmap, rect: Rect, previous: String?): String? {
        val name = "wallpaper_${java.util.UUID.randomUUID()}.jpg"
        val target = fileOf(context, name)
        val sourceTarget = sourceFor(context, name)
        val scaled = scaleDown(cropped)
        return try {
            dir(context).mkdirs()
            val previousSource = previous?.let { existingSource(context, it) }
            if (previousSource != null) previousSource.copyTo(sourceTarget)
            else sourceTarget.outputStream().use { check(source.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            target.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
            cropFile(context, name).writeText(listOf(rect.left, rect.top, rect.right, rect.bottom).joinToString(","))
            name
        } catch (_: Exception) {
            deleteWallpaper(context, name)
            null
        } finally {
            if (scaled !== cropped) scaled.recycle()
        }
    }

    /** 只清理本次替换的旧壁纸，避免碰到尚未提交的另一张图。 */
    fun deleteWallpaper(context: Context, name: String) {
        runCatching {
            fileOf(context, name).delete()
            sourceFor(context, name).delete()
            cropFile(context, name).delete()
        }
    }

    /** 按最长边等比缩小；已经够小就原样返回（调用方不要 recycle 返回值以外的对象两次）。 */
    private fun scaleDown(source: Bitmap, maxSide: Int = MaxSide): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide) return source
        val ratio = maxSide.toFloat() / longest
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
