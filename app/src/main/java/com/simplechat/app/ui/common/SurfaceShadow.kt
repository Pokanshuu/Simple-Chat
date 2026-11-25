package com.simplechat.app.ui.common

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntSize
import com.simplechat.app.ui.theme.tokens.SurfaceShadowSpec
import kotlin.math.abs
import kotlin.math.max

/*
 * 全应用唯一的投影绘制入口。
 *
 * 为什么不能直接 `Modifier.shadow`：RenderNode 的投影永远画在内容底下。
 * 元素一旦半透明（输入栏、抽屉、菜单…），底下那层投影会从元素内部透出来，
 * 表现为中间一块发灰的圆角矩形 —— 越透明越明显。这里把投影裁在形状**外面**：
 *
 *   shadow = 形状投影 ∩ (整个绘制区 - 形状)
 *
 * 另外，投影随底色不透明度一起衰减：越透明，alpha 越低、半径 / 扩散 / 位移越小，
 * 于是「透明元素浮在内容上」的层级关系还在，但不会留下一个比元素更实的影子。
 */

/** 主投影的半径 / 扩散 / 下移相对 elevation 的比例。 */
private const val MainRadius = 0.90f
private const val MainSpread = 0.05f
private const val MainOffsetY = 0.10f

/** 贴边层的比例：更窄、更实，负责把边缘“按”回地面。 */
private const val ContactRadius = 0.90f
private const val ContactSpread = 0.10f

/**
 * 按元素底色不透明度生成两层投影。
 *
 * 抽成纯函数而不是直接写在 draw 里，方便单测锁住“越透明越弱、范围越小”这条规则。
 */
internal fun SurfaceShadowSpec.layerShadows(opacity: Float): List<Shadow> {
    val strength = opacity.coerceIn(0f, 1f)
    if (strength <= 0f) return emptyList()

    val main = Shadow(
        radius = elevation * (MainRadius * strength),
        color = lerp(ambient, spot, 0.6f),
        spread = elevation * (MainSpread * strength),
        offset = DpOffset(0.dp, elevation * (MainOffsetY * strength)),
        alpha = strength,
    )
    val contact = if (contactElevation <= 0.dp) {
        null
    } else {
        Shadow(
            radius = contactElevation * (ContactRadius * strength),
            color = contactColor,
            spread = contactElevation * (ContactSpread * strength),
            offset = DpOffset.Zero,
            alpha = strength,
        )
    }
    return listOfNotNull(main, contact)
}

/**
 * 给任意形状的元素加“只向外扩散”的投影。
 *
 * @param opacity 元素底色 alpha（0 = 完全透明，1 = 完全不透明）。传 `surface.alpha` 即可；
 *   投影的浓淡和范围都会随之衰减。
 */
internal fun Modifier.surfaceShadow(
    shape: Shape,
    spec: SurfaceShadowSpec,
    opacity: Float = 1f,
): Modifier {
    val strength = opacity.coerceIn(0f, 1f)
    if (strength <= 0f || spec.elevation <= 0.dp) return this

    return drawWithCache {
        val context = obtainShadowContext()
        val layers = spec.layerShadows(strength)
        val painters = layers.map { context.createDropShadowPainter(shape, it) }
        val clip = Path().apply {
            addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache))
        }
        val margin = layers.maxOf { it.marginPx(this) }

        if (Build.VERSION.SDK_INT in 24..28) {
            // API 28 及以下对 clipPath(Difference) 支持不完整：录到离屏图层里再画。
            val layer = obtainGraphicsLayer()
            layer.compositingStrategy = CompositingStrategy.ModulateAlpha
            val layerSize = Size(size.width + margin * 2f, size.height + margin * 2f).roundToIntSize()
            layer.record(this, layoutDirection, layerSize) {
                translate(margin, margin) {
                    clipPath(clip, ClipOp.Difference) {
                        painters.forEach { painter -> painter.run { draw(size) } }
                    }
                }
            }
            layer.translationX = -margin
            layer.translationY = -margin
            onDrawBehind { drawLayer(layer) }
        } else {
            onDrawBehind {
                clipPath(clip, ClipOp.Difference) {
                    painters.forEach { painter -> painter.run { draw(size) } }
                }
            }
        }
    }
}

private fun Shadow.marginPx(density: androidx.compose.ui.unit.Density): Float = with(density) {
    (radius + spread).toPx() + max(abs(offset.x.toPx()), abs(offset.y.toPx()))
}
