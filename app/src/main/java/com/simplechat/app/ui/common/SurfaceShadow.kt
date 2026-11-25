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
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlin.math.sqrt

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
 *
 * 面积也参与：同一档 elevation 下，表面越大，半影越宽、越柔（见 [shadowAreaScale]）；
 * 下移取半径的固定比例，所以无论面积多大，影子都是朝下的。
 */

/** 主投影的半径 / 扩散相对 elevation 的比例。 */
private const val MainRadius = 0.80f
private const val MainSpread = 0.02f

/**
 * 下移取**半径**的 75%，而不是 elevation 的固定比例。
 *
 * 这样面积或模糊变大时下移同步变大，上缘始终只留约 1/4 半径的柔和残影，
 * 主体落在下缘 —— 既柔和，又不会退回"围着元素一圈均匀灰晕"。
 *
 * 早先这里是 elevation 的 0.10，而半径是 0.90：模糊上下对称，正是那个问题。
 */
private const val MainOffsetRatio = 0.75f

/** 贴边层的比例：更窄、更实，负责把边缘“按”回地面。 */
private const val ContactRadius = 0.55f
private const val ContactSpread = 0.10f
private const val ContactOffsetY = 0.35f

/**
 * 投影面积系数：以表面的几何平均边长除以参考尺寸。
 *
 * 物理上，面积越大的表面投下的半影越宽、越柔；这里把它限制在
 * [MinAreaScale]..[MaxAreaScale]，免得 1dp 的小点没有影子、整屏抽屉拖出长尾。
 */
private const val AreaReferenceDp = 140f
private const val MinAreaScale = 0.85f
private const val MaxAreaScale = 1.50f

internal fun shadowAreaScale(widthDp: Float, heightDp: Float): Float {
    if (widthDp <= 0f || heightDp <= 0f) return 1f
    return (sqrt(widthDp * heightDp) / AreaReferenceDp).coerceIn(MinAreaScale, MaxAreaScale)
}

/**
 * 按元素底色不透明度生成两层投影。
 *
 * 抽成纯函数而不是直接写在 draw 里，方便单测锁住“越透明越弱、范围越小”这条规则。
 *
 * @param areaScale 表面面积系数，见 [shadowAreaScale]。模糊、扩散与下移一起变大：
 *   面积越大的表面，影子越宽、越柔、也落得略低，但始终是朝下的。
 */
internal fun SurfaceShadowSpec.layerShadows(opacity: Float, areaScale: Float = 1f): List<Shadow> {
    val strength = opacity.coerceIn(0f, 1f)
    if (strength <= 0f) return emptyList()
    val area = areaScale.coerceIn(MinAreaScale, MaxAreaScale)

    val mainRadius = elevation * (MainRadius * area * strength)
    val main = Shadow(
        radius = mainRadius,
        color = lerp(ambient, spot, 0.6f),
        spread = elevation * (MainSpread * area * strength),
        offset = DpOffset(0.dp, mainRadius * MainOffsetRatio),
        alpha = strength,
    )
    val contact = if (contactElevation <= 0.dp) {
        null
    } else {
        Shadow(
            radius = contactElevation * (ContactRadius * area.coerceAtMost(1.2f) * strength),
            color = contactColor,
            spread = contactElevation * (ContactSpread * strength),
            offset = DpOffset(0.dp, contactElevation * (ContactOffsetY * strength)),
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
        // 用这个 modifier 实际分到的尺寸测面积：同一档 elevation 下，大面板的影子更宽更柔。
        val areaScale = shadowAreaScale(size.width / density, size.height / density)
        val layers = spec.layerShadows(strength, areaScale)
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
                    /*
                     * 元素顶边以上不画：下移比例是半径的 0.75，模糊本身还会
                     * 往顶上溢出 1/4 半径。参考设计里上缘是干净的，所以在这里
                     * 裁掉——影子只从顶边往下、往两侧走。
                     */
                    clipRect(
                        left = -margin,
                        top = 0f,
                        right = size.width + margin,
                        bottom = size.height + margin,
                    ) {
                        clipPath(clip, ClipOp.Difference) {
                            painters.forEach { painter -> painter.run { draw(size) } }
                        }
                    }
                }
            }
            layer.translationX = -margin
            layer.translationY = -margin
            onDrawBehind { drawLayer(layer) }
        } else {
            onDrawBehind {
                // 同上：顶边以上不画，避免上缘出现一圈灰晕。
                clipRect(
                    left = -margin,
                    top = 0f,
                    right = size.width + margin,
                    bottom = size.height + margin,
                ) {
                    clipPath(clip, ClipOp.Difference) {
                        painters.forEach { painter -> painter.run { draw(size) } }
                    }
                }
            }
        }
    }
}

private fun Shadow.marginPx(density: androidx.compose.ui.unit.Density): Float = with(density) {
    (radius + spread).toPx() + max(abs(offset.x.toPx()), abs(offset.y.toPx()))
}
