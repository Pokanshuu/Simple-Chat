package com.simplechat.app.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import com.simplechat.app.data.MaxWallpaperBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 聊天背景壁纸的当前状态。
 *
 * 壁纸文件和调节参数独立存放。聊天和预览共用同一文件版本的位图。
 */
data class WallpaperState(
    val file: File? = null,
    /** 保留旧偏好方向；新版底色不透明度为 1 - opacity。 */
    val opacity: Float = 0.35f,
    /** 连续模糊强度；0 = 不模糊。 */
    val blur: Float = 0f,
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    /** 画面位置，0..1，0.5 = 居中。 */
    val offsetX: Float = 0.5f,
    val offsetY: Float = 0.5f,
    val scale: Float = 1f,
    /** 额外压暗（叠一层黑）。 */
    val dim: Float = 0f,
    /** COVER / FILL / CENTER / STRETCH。 */
    val fit: String = "COVER",
    val flipH: Boolean = false,
) {
    val active: Boolean get() = file != null

    /** 有没有需要走 ColorMatrix 的画面调节（没有就省掉一次 Paint 构造）。 */
    val hasColorTuning: Boolean
        get() = brightness != 1f || contrast != 1f || saturation != 1f
}

/** 壁纸（设置 → 外观 → 自定义主题）；null = 纯色背景。 */
val LocalWallpaper = staticCompositionLocalOf { WallpaperState() }

// 滑块保持连续。每个采样点都先做平滑模糊，而且保留同样的像素网格。
private val blurStops = floatArrayOf(0f, 3f, 10f, 20f, 40f, 72f, 120f, MaxWallpaperBlur)
private val blurSpread = floatArrayOf(0f, 0.002f, 0.005f, 0.011f, 0.024f, 0.049f, 0.10f, 0.19f, 0.21f)
private const val BlurWorkingSize = 480

internal class WallpaperImage(bitmap: Bitmap, checkCancelled: () -> Unit = {}) {
    val width = bitmap.width
    val height = bitmap.height
    val levels: List<Bitmap> = buildList {
        add(bitmap)
        val longest = maxOf(width, height)
        val ratio = minOf(1f, BlurWorkingSize.toFloat() / longest)
        val workingWidth = (width * ratio).roundToInt().coerceAtLeast(1)
        val workingHeight = (height * ratio).roundToInt().coerceAtLeast(1)
        val working = Bitmap.createScaledBitmap(bitmap, workingWidth, workingHeight, true)
        val pixels = IntArray(workingWidth * workingHeight)
        val scratch = IntArray(pixels.size)
        working.getPixels(pixels, 0, workingWidth, 0, 0, workingWidth, workingHeight)
        if (working !== bitmap) working.recycle()
        val workingLongest = maxOf(workingWidth, workingHeight)
        for (index in 1 until blurSpread.size) {
            checkCancelled()
            val previous = blurSpread[index - 1]
            val target = blurSpread[index]
            // 多次盒式模糊近似高斯模糊。相邻档位复用已经平滑的像素。
            val radius = (sqrt(target * target - previous * previous) * workingLongest).roundToInt().coerceAtLeast(1)
            repeat(3) {
                blurWallpaperLines(pixels, scratch, workingWidth, workingHeight, radius, vertical = false)
                blurWallpaperLines(scratch, pixels, workingWidth, workingHeight, radius, vertical = true)
            }
            add(Bitmap.createBitmap(pixels, workingWidth, workingHeight, Bitmap.Config.ARGB_8888))
        }
    }
    val allocationBytes = levels.sumOf { it.allocationByteCount }
}

/** 每条扫描线用滑动窗口求均值，强模糊也不需要逐像素重复扫描整个半径。 */
private fun blurWallpaperLines(
    source: IntArray,
    destination: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    vertical: Boolean,
) {
    val lineCount = if (vertical) width else height
    val lineLength = if (vertical) height else width
    val stride = if (vertical) width else 1
    val count = radius * 2 + 1
    for (line in 0 until lineCount) {
        val start = if (vertical) line else line * width
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val pixel = source[start + offset.coerceIn(0, lineLength - 1) * stride]
            alpha += pixel ushr 24
            red += (pixel ushr 16) and 255
            green += (pixel ushr 8) and 255
            blue += pixel and 255
        }
        for (position in 0 until lineLength) {
            destination[start + position * stride] =
                (((alpha + radius) / count) shl 24) or (((red + radius) / count) shl 16) or
                    (((green + radius) / count) shl 8) or ((blue + radius) / count)
            val leaving = source[start + (position - radius).coerceAtLeast(0) * stride]
            val entering = source[start + (position + radius + 1).coerceAtMost(lineLength - 1) * stride]
            alpha += (entering ushr 24) - (leaving ushr 24)
            red += ((entering ushr 16) and 255) - ((leaving ushr 16) and 255)
            green += ((entering ushr 8) and 255) - ((leaving ushr 8) and 255)
            blue += (entering and 255) - (leaving and 255)
        }
    }
}

internal fun wallpaperBlurLevel(blur: Float): Float {
    val strength = blur.coerceIn(0f, MaxWallpaperBlur)
    for (index in 1 until blurStops.size) {
        val upper = blurStops[index]
        if (strength <= upper) {
            val lower = blurStops[index - 1]
            return index - 1 + (strength - lower) / (upper - lower)
        }
    }
    return blurStops.lastIndex.toFloat()
}

private data class WallpaperVersion(val path: String, val modified: Long, val size: Long) {
    constructor(file: File) : this(file.absolutePath, file.lastModified(), file.length())
}

internal object WallpaperImageCache {
    private val images = object : LruCache<WallpaperVersion, WallpaperImage>(16 * 1024 * 1024) {
        override fun sizeOf(key: WallpaperVersion, value: WallpaperImage) = value.allocationBytes
    }
    private val decodeLock = Mutex()

    suspend fun load(file: File): WallpaperImage? = withContext(Dispatchers.IO) {
        val version = WallpaperVersion(file)
        if (version.size == 0L) return@withContext null
        images.get(version)?.let { return@withContext it }
        decodeLock.withLock {
            images.get(version)?.let { return@withLock it }
            val bitmap = BitmapFactory.decodeFile(version.path) ?: return@withLock null
            val image = withContext(Dispatchers.Default) { WallpaperImage(bitmap) { ensureActive() } }
            if (WallpaperVersion(file) != version) return@withLock null
            images.put(version, image)
            image
        }
    }

    fun clear() {
        // A composed screen can still own an evicted bitmap. Let it release the reference normally.
        images.evictAll()
    }
}

/** 聊天、侧栏和主题预览共享文件版本缓存。滑块变化不会触发解码。 */
@Composable
internal fun rememberWallpaperBitmap(wallpaper: WallpaperState): WallpaperImage? {
    val file = wallpaper.file ?: return null
    return key(file) {
        produceState<WallpaperImage?>(null) {
            value = WallpaperImageCache.load(file)
        }.value
    }
}
