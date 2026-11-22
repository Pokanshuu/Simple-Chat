package com.simplechat.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * 按 [wallpaper] 的参数在画布上绘制壁纸。
 *
 * 聊天页背景和设置页预览**共用这一段**：预览如果只画原图，用户调完模糊/对比度
 * 看不到任何变化，会以为滑块坏了。同一个函数保证"预览即所得"。
 */
fun DrawScope.drawWallpaper(
    bitmap: ImageBitmap,
    wallpaper: WallpaperState,
    /** 壁纸铺满的区域。默认是当前画布；悬浮玻璃的源图层要按聊天页整体尺寸画。 */
    area: androidx.compose.ui.geometry.Size = size,
) {
    val canvasW = area.width
    val canvasH = area.height
    val srcW = bitmap.width.toFloat()
    val srcH = bitmap.height.toFloat()

    val (fitW, fitH) = when (wallpaper.fit) {
        "STRETCH" -> canvasW to canvasH
        "FILL" -> {
            val s = minOf(canvasW / srcW, canvasH / srcH)
            srcW * s to srcH * s
        }
        "CENTER" -> srcW to srcH
        else -> {
            val s = maxOf(canvasW / srcW, canvasH / srcH)
            srcW * s to srcH * s
        }
    }
    val drawW = fitW * wallpaper.scale
    val drawH = fitH * wallpaper.scale
    val left = (canvasW - drawW) * wallpaper.offsetX
    val top = (canvasH - drawH) * wallpaper.offsetY

    val paint = wallpaperPaint(wallpaper)

    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val save = native.save()
        if (wallpaper.flipH) {
            native.translate(canvasW, 0f)
            native.scale(-1f, 1f)
        }
        native.drawBitmap(
            bitmap.asAndroidBitmap(),
            null,
            android.graphics.RectF(left, top, left + drawW, top + drawH),
            paint,
        )
        native.restoreToCount(save)
    }
    if (wallpaper.dim > 0f) {
        drawRect(Color.Black.copy(alpha = wallpaper.dim.coerceIn(0f, 1f)), size = area)
    }
}


/** 壁纸的画笔：不透明度 + 亮度 / 对比度 / 饱和度（ColorMatrix）。聊天页、编辑器共用。 */
fun wallpaperPaint(wallpaper: WallpaperState): android.graphics.Paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
    alpha = (wallpaper.opacity.coerceIn(0f, 1f) * 255f).toInt()
    if (wallpaper.hasColorTuning) {
        val matrix = android.graphics.ColorMatrix().apply { setSaturation(wallpaper.saturation) }
        matrix.postConcat(
            android.graphics.ColorMatrix().apply {
                setScale(wallpaper.brightness, wallpaper.brightness, wallpaper.brightness, 1f)
            },
        )
        val overflow = (1f - wallpaper.contrast) * 128f
        // ColorMatrix 没有 setTranslate（那是 android.graphics.Matrix 的方法），
        // 对比度的平移只能按 4×5 矩阵原始数组填。
        matrix.postConcat(
            android.graphics.ColorMatrix(
                floatArrayOf(
                    wallpaper.contrast, 0f, 0f, 0f, overflow,
                    0f, wallpaper.contrast, 0f, 0f, overflow,
                    0f, 0f, wallpaper.contrast, 0f, overflow,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
    }
}
