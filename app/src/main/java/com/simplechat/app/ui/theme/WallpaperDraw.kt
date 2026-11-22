package com.simplechat.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.simplechat.app.data.MaxWallpaperBlur

/**
 * 按 [wallpaper] 的参数在画布上绘制壁纸。
 *
 * 聊天页背景和设置页预览**共用这一段**：预览如果只画原图，用户调完模糊/对比度
 * 看不到任何变化，会以为滑块坏了。同一个函数保证"预览即所得"。
 */
internal fun DrawScope.drawWallpaper(
    image: WallpaperImage,
    wallpaper: WallpaperState,
    baseColor: Color,
    compareOriginal: Boolean = false,
    /** 壁纸铺满的区域。默认是当前画布；悬浮玻璃的源图层要按聊天页整体尺寸画。 */
    area: androidx.compose.ui.geometry.Size = size,
) {
    val canvasW = area.width
    val canvasH = area.height
    val srcW = image.width.toFloat()
    val srcH = image.height.toFloat()

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

    drawWallpaperImage(image, wallpaper, android.graphics.RectF(left, top, left + drawW, top + drawH), baseColor, compareOriginal, canvasW)
    if (wallpaper.dim > 0f) {
        drawRect(Color.Black.copy(alpha = wallpaper.dim.coerceIn(0f, 1f)), size = area)
    }
}


/** 在聊天的适配矩形或编辑器的裁切矩形内画同一套图层。 */
internal fun DrawScope.drawWallpaperImage(
    image: WallpaperImage,
    wallpaper: WallpaperState,
    destination: android.graphics.RectF,
    baseColor: Color,
    compareOriginal: Boolean = false,
    canvasWidth: Float = size.width,
) {
    val blur = if (compareOriginal) 0f else wallpaper.blur.coerceIn(0f, MaxWallpaperBlur)
    val blurLevel = wallpaperBlurLevel(blur)
    val first = blurLevel.toInt()
    val blend = blurLevel - first
    val paint = wallpaperPaint(wallpaper)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val save = native.save()
        if (wallpaper.flipH) {
            native.translate(canvasWidth, 0f)
            native.scale(-1f, 1f)
        }
        if (blend == 0f) {
            native.drawBitmap(image.levels[first], null, destination, paint)
        } else {
            // 在不透明的第一层上插值，不把底色的不透明度应用到采样层。
            val area = android.graphics.RectF(native.clipBounds)
            if (area.intersect(destination)) {
                val layer = native.saveLayer(area, null)
                native.drawBitmap(image.levels[first], null, destination, paint)
                paint.alpha = (255f * blend).toInt()
                native.drawBitmap(image.levels[first + 1], null, destination, paint)
                native.restoreToCount(layer)
            }
        }
        if (!compareOriginal) {
            paint.colorFilter = null
            if (blurLevel > 0f) {
                val mix = acrylicMixColor(baseColor, blur / MaxWallpaperBlur)
                // Fade in the acrylic tint with the first transition away from the original image.
                paint.color = mix.copy(alpha = mix.alpha * blurLevel.coerceAtMost(1f)).toArgb()
                native.drawRect(destination, paint)
            }
            paint.color = baseColor.copy(alpha = 1f - wallpaper.opacity.coerceIn(0f, 1f)).toArgb()
            native.drawRect(destination, paint)
        }
        native.restoreToCount(save)
    }
}

/** 亚克力采样只使用画面调节，不使用底色不透明度。 */
private fun wallpaperPaint(wallpaper: WallpaperState): android.graphics.Paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
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
