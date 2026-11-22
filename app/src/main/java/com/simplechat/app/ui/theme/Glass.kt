package com.simplechat.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.simplechat.app.data.InterfaceControl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** 主题色在面板底色上的独立混色比例。 */
val LocalPanelAccentOpacity = staticCompositionLocalOf { 0f }

/** 保留组件的底色不透明度，只改变主题色所占的比例。 */
@Composable
internal fun panelTint(base: Color): Color = glassTintedSurface(
    base, MaterialTheme.colorScheme.primary, base.alpha, LocalPanelAccentOpacity.current,
)

internal fun glassContrastForeground(surface: Color, backdrop: Color, preferred: Color): Color {
    val luminance = surface.compositeOver(backdrop).luminance()
    fun contrast(foreground: Color): Float {
        val foregroundLuminance = foreground.luminance()
        return (maxOf(luminance, foregroundLuminance) + 0.05f) / (minOf(luminance, foregroundLuminance) + 0.05f)
    }
    if (contrast(preferred) >= 4.5f) return preferred
    return if (contrast(Color.White) >= contrast(Color.Black)) Color.White else Color.Black
}

private data class PanelBasePalette(val scheme: ColorScheme, val colors: ChatColors)
private val LocalPanelBasePalette = staticCompositionLocalOf<PanelBasePalette?> { null }

@Composable
internal fun GlassForeground(
    control: InterfaceControl,
    surface: Color = floatingPanelSurface(control),
    content: @Composable () -> Unit,
) {
    val inherited = LocalPanelBasePalette.current
    val base = inherited ?: PanelBasePalette(MaterialTheme.colorScheme, LocalChatColors.current)
    val scheme = base.scheme
    val colors = base.colors
    val tinted = LocalControlEffects.current.usesAccent(control) && LocalPanelAccentOpacity.current > 0f
    val foreground = if (tinted) glassContrastForeground(surface, colors.pageBackground, scheme.onSurface) else scheme.onSurface
    // Keep the content at the same composition slot when a surface crosses
    // the contrast threshold; replacing it cancels in-flight gestures.
    val adapted = foreground != scheme.onSurface
    val secondary = foreground.copy(alpha = 0.78f)
    val outline = foreground.copy(alpha = 0.4f)
    MaterialTheme(colorScheme = if (!adapted) scheme else scheme.copy(
        onSurface = foreground, onBackground = foreground, onSurfaceVariant = secondary,
        outline = outline, outlineVariant = outline,
    )) {
        CompositionLocalProvider(
            LocalPanelBasePalette provides base,
            LocalContentColor provides foreground,
            LocalChatColors provides if (!adapted) colors else colors.copy(
                placeholder = secondary, processText = secondary, processBar = outline, outline = outline, divider = outline,
            ),
            content = content,
        )
    }
}

/** 玻璃表面色：按 [strength] 混入主题色，保留传入的 alpha。 */
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
