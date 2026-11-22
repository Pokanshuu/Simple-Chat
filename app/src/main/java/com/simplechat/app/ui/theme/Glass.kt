package com.simplechat.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 玻璃表面色：在「卡片色 × alpha」里混入一点主题色。
 *
 * 纯白半透明压在亮壁纸上会发灰、发白（用户原话"太白了"），
 * 混 12% 的主题色之后整片玻璃带上主题色相，和侧栏/输入栏/头像圈形成同一套色调，
 * 也顺手把纯白的"塑料感"去掉了。
 *
 * @param base 卡片底色（亮色主题是白、暗色主题是深灰）
 * @param tint 主题色（MaterialTheme.colorScheme.primary）
 * @param alpha 玻璃不透明度（0..1）
 * @param strength 主题色混入比例，默认 0.12（再高就会盖住文字的对比度）
 */
fun glassTintedSurface(
    base: Color,
    tint: Color,
    alpha: Float,
    strength: Float = 0.12f,
): Color {
    val tinted = tint.copy(alpha = alpha)
    return lerp(base.copy(alpha = alpha), tinted, strength)
}
