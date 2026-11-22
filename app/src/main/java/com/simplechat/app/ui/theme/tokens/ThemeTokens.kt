package com.simplechat.app.ui.theme.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.simplechat.app.ui.theme.ChatColors

/*
 * 主题 Token（docs/33）：全应用**唯一**的视觉取值入口。
 *
 *   ThemeSpec（主题文件，可选）──┐
 *   用户设置（主题色 / 壁纸…）──┼─▶ ThemeResolver ─▶ ThemeTokens ─▶ LocalThemeTokens ─▶ 组件
 *   内置默认值 ────────────────┘        └─ 安全兜底（对比度 / 取值范围）
 *
 * 规则：
 * - 组件只读 `AppTheme.tokens.xxx`，不写颜色 / 圆角 / 间距 / 透明度字面量；
 * - 新增可美化的视觉属性 = 在这里加字段 + 在 ThemeSpec 里加同名可选字段 + 写进 docs/33 §33.4；
 * - Token 只描述"是什么"（语义），不描述"在哪用"之外的实现细节。
 */

/** 解析后的完整主题。由 [ThemeResolver] 产出，组件不要自己构造。 */
@Immutable
data class ThemeTokens(
    val dark: Boolean,
    val colors: ChatColors,
    val scheme: ColorScheme,
    val shapes: ShapeTokens = ShapeTokens(),
    val space: SpaceTokens = SpaceTokens(),
    val shadow: ShadowTokens = ShadowTokens(),
    val surface: SurfaceTokens = SurfaceTokens(),
)

/** 圆角（§15.2 只有四档 + 气泡）。主题文件只能改数值，不能新增档位。 */
@Immutable
data class ShapeTokens(
    val soft: Dp = 14.dp,
    val menu: Dp = 18.dp,
    val container: Dp = 24.dp,
    val bubble: Dp = 18.dp,
    /** 气泡靠近发出侧的那个角。 */
    val bubbleTail: Dp = 6.dp,
) {
    val pillShape get() = RoundedCornerShape(percent = 50)
    val softShape get() = RoundedCornerShape(soft)
    val menuShape get() = RoundedCornerShape(menu)
    val containerShape get() = RoundedCornerShape(container)
    val bubbleOut get() = RoundedCornerShape(bubble, bubble, bubbleTail, bubble)
    val bubbleIn get() = RoundedCornerShape(bubble, bubble, bubble, bubbleTail)
}

/** 网格（§15.1）。 */
@Immutable
data class SpaceTokens(
    val screen: Dp = 16.dp,
    val card: Dp = 16.dp,
    val inline: Dp = 12.dp,
    val gap: Dp = 8.dp,
    val section: Dp = 24.dp,
    val row: Dp = 46.dp,
    val rowPadY: Dp = 12.dp,
)

/** 阴影（§15.4）：范围（elevation）与浓淡（alpha）分开。 */
@Immutable
data class ShadowTokens(
    val panelElevation: Dp = 8.dp,
    val panelAmbient: Color = Color.Black.copy(alpha = 0.08f),
    val panelSpot: Color = Color.Black.copy(alpha = 0.14f),
    val raisedElevation: Dp = 16.dp,
    val raisedAmbient: Color = Color.Black.copy(alpha = 0.18f),
    val raisedSpot: Color = Color.Black.copy(alpha = 0.26f),
)

/**
 * 表面 / 玻璃（docs/32 §32.3）。下限是**可读性红线**：主题文件可以调高，
 * 调低会被 [ThemeResolver] 钳回 [SurfaceTokens.SafetyFloor]。
 */
@Immutable
data class SurfaceTokens(
    /** 壁纸玻璃：抽屉 / 宽屏侧栏的最透下限。 */
    val drawerFloor: Float = 0.80f,
    /** 壁纸玻璃：用户气泡的最透下限。 */
    val userBubbleFloor: Float = 0.78f,
    /** 壁纸玻璃：会话行（当前 / 选中）的最透下限；普通行不画底。 */
    val rowFloor: Float = 0.55f,
    /** 壁纸玻璃：分段控件轨道 / 指示器。 */
    val segmentedTrackFloor: Float = 0.55f,
    val segmentedIndicatorFloor: Float = 0.70f,
    /** 悬浮玻璃：有实时模糊时的最透下限。 */
    val floatingFloorBlurred: Float = 0.58f,
    /** 悬浮玻璃：没有模糊（Android 12 以下 / 省电）时的最透下限。 */
    val floatingFloorPlain: Float = 0.86f,
    /** 悬浮玻璃：模糊半径上限（性能红线，主题文件不能调高）。 */
    val floatingMaxBlur: Dp = 24.dp,
    /** 玻璃表面掺主题色的比例。默认 0：表面不染色（§7.2）。 */
    val accentTint: Float = 0f,
) {
    companion object {
        /** 任何玻璃下限都不能低于它（R3：正文可读性有硬下限）。 */
        const val SafetyFloor = 0.45f
        /** 模糊半径的绝对上限。 */
        val MaxBlurCap: Dp = 24.dp
        /** 表面掺色比例上限：再高就会盖住文字对比度。 */
        const val MaxAccentTint = 0.12f
    }
}

val LocalThemeTokens = staticCompositionLocalOf<ThemeTokens> {
    error("ThemeTokens not provided: wrap content in SimpleChatTheme")
}

/** 组件读取 Token 的唯一入口：`AppTheme.tokens.surface.drawerFloor`。 */
object AppTheme {
    val tokens: ThemeTokens
        @Composable @ReadOnlyComposable
        get() = LocalThemeTokens.current
}
