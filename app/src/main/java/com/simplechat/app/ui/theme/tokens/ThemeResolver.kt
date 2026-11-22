package com.simplechat.app.ui.theme.tokens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.max
import kotlin.math.min

/**
 * 主题来源。当前只有内置（无主题文件）；以后的「导入主题文件」实现这个接口即可，
 * 上层（MainActivity → SimpleChatTheme）不用改。
 */
fun interface ThemeSource {
    fun observe(): Flow<ThemeSpec?>
}

/** 内置主题：不提供主题文件，全部取默认值。 */
object BuiltInThemeSource : ThemeSource {
    override fun observe(): Flow<ThemeSpec?> = flowOf(null)
}

/** 解析结果 + 被安全兜底改写过的项（用于导入时提示"这些设置因可读性被调整"）。 */
data class ResolvedTheme(val tokens: ThemeTokens, val warnings: List<String>)

/**
 * 把「内置默认值 ← 主题文件」合成最终 Token，并做安全兜底。
 *
 * 用户设置（主题色、壁纸、玻璃）是**再上面一层**，由调用方在解析后叠加 ——
 * 主题文件定义"这个主题长什么样"，用户设置表达"我在这个主题里的偏好"。
 */
object ThemeResolver {

    fun resolve(base: ThemeTokens, spec: ThemeSpec?): ResolvedTheme {
        if (spec == null) return ResolvedTheme(base, emptyList())
        val warnings = mutableListOf<String>()
        val palette = (if (base.dark) spec.dark else spec.light)?.colors
        var tokens = base

        if (palette != null) {
            val c = base.colors
            val s = base.scheme
            val text = palette.text.toColor() ?: s.onSurface
            val page = palette.pageBackground.toColor() ?: c.pageBackground
            val card = palette.card.toColor() ?: c.card
            // R3：正文与页面底 / 卡片底至少 4.5:1，否则整组退回默认
            val safe = contrast(text, page) >= 4.5f && contrast(text, card) >= 4.5f
            if (!safe) warnings += "colors.text/pageBackground/card: contrast below 4.5:1, reverted"
            val fg = if (safe) text else s.onSurface
            val pg = if (safe) page else c.pageBackground
            val cd = if (safe) card else c.card
            val primary = palette.primary.toColor() ?: s.primary
            tokens = tokens.copy(
                colors = c.copy(
                    pageBackground = pg,
                    settingsBackground = palette.settingsBackground.toColor() ?: c.settingsBackground,
                    card = cd,
                    menuBackground = palette.menuBackground.toColor() ?: c.menuBackground,
                    userBubble = palette.userBubble.toColor() ?: c.userBubble,
                    onUserBubble = palette.onUserBubble.toColor() ?: c.onUserBubble,
                    danger = palette.danger.toColor() ?: c.danger,
                    warning = palette.warning.toColor() ?: c.warning,
                    markdownCodeBlock = palette.codeBlock.toColor() ?: c.markdownCodeBlock,
                    markdownInlineCode = palette.inlineCode.toColor() ?: c.markdownInlineCode,
                    markdownQuoteBar = palette.quoteBar.toColor() ?: c.markdownQuoteBar,
                    // 前景层级一律从正文色派生（docs/32 §32.4.5）
                    processText = fg.copy(alpha = if (base.dark) 0.66f else 0.58f),
                    placeholder = fg.copy(alpha = if (base.dark) 0.45f else 0.38f),
                    processBar = fg.copy(alpha = if (base.dark) 0.21f else 0.16f),
                    outline = fg.copy(alpha = if (base.dark) 0.15f else 0.09f),
                    divider = fg.copy(alpha = if (base.dark) 0.11f else 0.06f),
                ),
                scheme = s.copy(
                    primary = primary,
                    onPrimary = palette.onPrimary.toColor() ?: s.onPrimary,
                    background = pg,
                    onBackground = fg,
                    surface = cd,
                    onSurface = fg,
                    onSurfaceVariant = fg.copy(alpha = if (base.dark) 0.60f else 0.56f),
                ),
            )
        }

        spec.shapes?.let { sh ->
            val b = base.shapes
            tokens = tokens.copy(
                shapes = b.copy(
                    soft = sh.soft.dpIn(0f, 28f) ?: b.soft,
                    menu = sh.menu.dpIn(0f, 32f) ?: b.menu,
                    container = sh.container.dpIn(0f, 36f) ?: b.container,
                    bubble = sh.bubble.dpIn(0f, 28f) ?: b.bubble,
                    bubbleTail = sh.bubbleTail.dpIn(0f, 28f) ?: b.bubbleTail,
                ),
            )
        }
        spec.space?.let { sp ->
            val b = base.space
            tokens = tokens.copy(
                space = b.copy(
                    screen = sp.screen.dpIn(8f, 32f) ?: b.screen,
                    inline = sp.inline.dpIn(4f, 24f) ?: b.inline,
                    gap = sp.gap.dpIn(2f, 16f) ?: b.gap,
                    section = sp.section.dpIn(8f, 48f) ?: b.section,
                ),
            )
        }
        spec.surface?.let { su ->
            val b = base.surface
            fun floor(v: Float?, d: Float, key: String): Float {
                if (v == null) return d
                if (v < SurfaceTokens.SafetyFloor) warnings += "surface.$key below ${SurfaceTokens.SafetyFloor}, clamped"
                return v.coerceIn(SurfaceTokens.SafetyFloor, 1f)
            }
            tokens = tokens.copy(
                surface = b.copy(
                    drawerFloor = floor(su.drawerFloor, b.drawerFloor, "drawerFloor"),
                    userBubbleFloor = floor(su.userBubbleFloor, b.userBubbleFloor, "userBubbleFloor"),
                    rowFloor = floor(su.rowFloor, b.rowFloor, "rowFloor"),
                    floatingFloorBlurred = floor(su.floatingFloorBlurred, b.floatingFloorBlurred, "floatingFloorBlurred"),
                    floatingFloorPlain = floor(su.floatingFloorPlain, b.floatingFloorPlain, "floatingFloorPlain"),
                    floatingMaxBlur = su.floatingMaxBlur?.let { min(it, SurfaceTokens.MaxBlurCap.value).coerceAtLeast(0f).dp }
                        ?: b.floatingMaxBlur,
                    accentTint = su.accentTint?.coerceIn(0f, SurfaceTokens.MaxAccentTint) ?: b.accentTint,
                ),
            )
        }
        return ResolvedTheme(tokens, warnings)
    }

    /** WCAG 相对亮度对比度。前景带透明度时先按背景合成。 */
    fun contrast(fg: Color, bg: Color): Float {
        val a = fg.alpha
        val composed = Color(
            red = fg.red * a + bg.red * (1 - a),
            green = fg.green * a + bg.green * (1 - a),
            blue = fg.blue * a + bg.blue * (1 - a),
        )
        val l1 = composed.luminance()
        val l2 = bg.luminance()
        return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
    }

    private fun String?.toColor(): Color? {
        val hex = this?.trim()?.removePrefix("#") ?: return null
        val value = hex.toLongOrNull(16) ?: return null
        return when (hex.length) {
            6 -> Color(0xFF000000 or value)
            8 -> Color(value)
            else -> null
        }
    }

    private fun Float?.dpIn(lo: Float, hi: Float) = this?.coerceIn(lo, hi)?.dp
}
