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

    /** 最长边上限：1440px。平板宽屏也够用，4K 原图纯属浪费。 */
    private const val MaxSide = 1440

    fun dir(context: Context): File = File(context.filesDir, Dir)

    fun fileOf(context: Context): File = File(dir(context), FileName)

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

    fun delete(context: Context): Boolean = runCatching {
        val file = fileOf(context)
        !file.exists() || file.delete()
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
