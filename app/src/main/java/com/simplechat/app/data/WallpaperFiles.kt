package com.simplechat.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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

    /** 解码选中的图片（摆正 EXIF 方向、限制尺寸），并存为裁剪原图。 */
    fun importSource(context: Context, uri: Uri): Bitmap? = runCatching {
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
        dir(context).mkdirs()
        sourceOf(context).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap
    }.getOrNull()

    /** 读回裁剪原图；老版本没有原图时退回当前壁纸。 */
    fun loadSource(context: Context, current: String?): Bitmap? = runCatching {
        val file = sourceOf(context).takeIf { it.exists() } ?: fileOf(context, current)
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()

    /** 保存裁剪结果为壁纸（缩到 [MaxSide] 以内）。 */
    fun saveCropped(context: Context, cropped: Bitmap): String? = runCatching {
        val scaled = scaleDown(cropped)
        dir(context).mkdirs()
        val name = "wallpaper_${System.currentTimeMillis()}.jpg"
        File(dir(context), name).outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        if (scaled !== cropped) scaled.recycle()
        // 清掉旧的裁剪结果，只留原图和这一张
        dir(context).listFiles()?.forEach { if (it.name != name && it.name != SourceName) it.delete() }
        name
    }.getOrNull()

    fun delete(context: Context): Boolean = runCatching {
        dir(context).listFiles()?.forEach { it.delete() }
        true
    }.getOrDefault(false)

    /** 按最长边等比缩小；已经够小就原样返回（调用方不要 recycle 返回值以外的对象两次）。 */
    private fun scaleDown(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MaxSide) return source
        val ratio = MaxSide.toFloat() / longest
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
