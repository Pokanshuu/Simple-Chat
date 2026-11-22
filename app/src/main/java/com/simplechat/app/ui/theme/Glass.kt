package com.simplechat.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.simplechat.app.ui.theme.tokens.AppTheme
import kotlin.math.max

/**
 * 壁纸玻璃作用的表面层（docs/32 §32.3.2）。每层的最透下限由
 * [com.simplechat.app.ui.theme.tokens.SurfaceTokens] 提供，主题文件可调（但不低于安全线）。
 *
 * 悬浮控件（输入栏 / 输入面板）不在这里：它们走 FloatingGlass（实时模糊 + 独立滑块）。
 */
enum class GlassLayer { DRAWER, USER_BUBBLE, ROW, SEGMENTED_TRACK, SEGMENTED_INDICATOR }

/**
 * 某一层在壁纸玻璃下的表面色。**组件只调这个**，不要自己 `copy(alpha)` / `coerceAtLeast`。
 *
 * - 没有壁纸或没开玻璃（`LocalChatGlassAlpha == 0`）→ 原样返回 [base]（R1：不透明、不染色）；
 * - 否则 alpha = max(用户的面板不透明度, 该层下限)，按 token 的 accentTint 掺主色（默认 0）。
 */
@Composable
fun glassSurface(base: Color, layer: GlassLayer): Color {
    val glass = LocalChatGlassAlpha.current
    if (glass <= 0f) return base
    val surface = AppTheme.tokens.surface
    val floor = when (layer) {
        GlassLayer.DRAWER -> surface.drawerFloor
        GlassLayer.USER_BUBBLE -> surface.userBubbleFloor
        GlassLayer.ROW -> surface.rowFloor
        GlassLayer.SEGMENTED_TRACK -> surface.segmentedTrackFloor
        GlassLayer.SEGMENTED_INDICATOR -> surface.segmentedIndicatorFloor
    }
    val alpha = base.alpha * max(glass, floor)
    return glassTintedSurface(base, MaterialTheme.colorScheme.primary, alpha, surface.accentTint)
}

/**
 * 玻璃表面色：`base × alpha`，可选掺入 [strength] 的主题色。
 *
 * 默认不掺（docs/32 B7）：表面染色是 §7.2 已否定的做法；需要时由主题文件的
 * `surface.accentTint` 打开，上限 0.12。
 */
fun glassTintedSurface(
    base: Color,
    tint: Color,
    alpha: Float,
    strength: Float = 0f,
): Color {
    val plain = base.copy(alpha = alpha)
    if (strength <= 0f) return plain
    return lerp(plain, tint.copy(alpha = alpha), strength)
}
