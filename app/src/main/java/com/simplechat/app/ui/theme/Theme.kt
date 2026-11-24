package com.simplechat.app.ui.theme

import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.simplechat.app.ui.theme.tokens.ThemeTokens
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import com.simplechat.app.data.FontSlot
import com.simplechat.app.data.TypographyDraft
import com.simplechat.app.data.TypographySettings
import com.simplechat.app.R
import com.simplechat.app.data.Res

/** 主题模式，对应设置页的「外观」三项。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 主题色。设置页「个性化 → 主题色」。
 *
 * 只影响**品牌色那一族**：主色（按钮 / 链接 / 选中态 / 发送键 / 进度环）、
 * 选中态的浅底、以及用户气泡。页面底色、正文颜色、功能色（危险 / 警告）都不动 ——
 * 换主题色不该把"有错误"这类语义色也换掉。
 *
 * 取值存的是 `name`；认不出来就回落到 [BLUE]（见 `MainActivity`）。
 */
enum class AccentColor(@StringRes private val labelRes: Int) {
    BLUE(R.string.theme_color_blue),
    GREEN(R.string.theme_color_green),
    RED(R.string.theme_color_red),
    PINK(R.string.theme_color_pink),
    PURPLE(R.string.theme_color_purple),
    BROWN(R.string.theme_color_brown),
    GRAY(R.string.theme_color_gray),
    ;

    /** 颜色名，随界面语言取（`theme_color_*`）。 */
    val label: String get() = Res.get(labelRes)
}

/** 该主题色在指定明暗下的**主色**。 */
fun AccentColor.primary(dark: Boolean): Color = when (this) {
    AccentColor.BLUE -> if (dark) BrandBlueDark else BrandBlue
    AccentColor.GREEN -> if (dark) AccentGreenDark else AccentGreenLight
    AccentColor.RED -> if (dark) AccentRedDark else AccentRedLight
    AccentColor.PINK -> if (dark) AccentPinkDark else AccentPinkLight
    AccentColor.PURPLE -> if (dark) AccentPurpleDark else AccentPurpleLight
    AccentColor.BROWN -> if (dark) AccentBrownDark else AccentBrownLight
    AccentColor.GRAY -> if (dark) AccentGrayDark else AccentGrayLight
}

/**
 * 由主色**派生**出来的浅色底：选中态填充 + 用户气泡。
 *
 * 刻意不逐个手写色值 —— 7 个主题色 × 2 档明暗 = 28 个手调颜色，
 * 既难保证它们跟主色"是同一个色相"，改一个还要跟着改一片。
 * 这里按固定比例把主色掺进页面底色，整套自动跟着主色走。
 *
 * 比例（浅 0.16 / 深 0.22）是照原来那版蓝色反推的：算出来与
 * `#E8EDFF` / `#243056` 基本重合，所以**默认的蓝色观感不变**。
 */
private fun tintOf(primary: Color, dark: Boolean): Color =
    lerp(if (dark) PageBgDark else Color.White, primary, if (dark) 0.22f else 0.16f)

/**
 * Material3 ColorScheme 之外的扩展语义色。
 * 这些是 DeepSeek 观感的关键，Material 没有对应槽位。
 */
@Immutable
data class ChatColors(
    val userBubble: Color,
    val onUserBubble: Color,
    val processText: Color,
    val processBar: Color,
    val placeholder: Color,
    val pageBackground: Color,
    val settingsBackground: Color,
    val card: Color,
    val outline: Color,
    val divider: Color,
    val danger: Color,
    /** 上下文接近阈值这类「注意」型状态，不是错误。 */
    val warning: Color,
    val capsuleSelected: Color,
    val fieldBackground: Color,
    /** 长按菜单等浮层表面。 */
    val menuBackground: Color,
    /**
     * Markdown 专用配色（对齐官方 Web 端的 token，见 [MarkdownCodeBgLight]）。
     *
     * 与 [fieldBackground] 分开是刻意的：输入框底色偏深一档才压得住，
     * 而代码块/行内代码要更浅 —— 直接复用会让整段代码"发灰"。
     */
    val markdownCodeBlock: Color,
    val markdownInlineCode: Color,
    val markdownQuoteBar: Color,
    val markdownRule: Color,
    /**
     * 抽屉 / 弹层背后的遮罩。
     *
     * 放在这里而不是用 Material 的 `colorScheme.scrim`：那个值在两套主题下
     * 都是同一个黑，**深色模式下盖在近黑底上等于没有**。这里按主题分别取值。
     */
    val scrim: Color,
)

private val LightChatColors = ChatColors(
    userBubble = UserBubbleLight,
    onUserBubble = TextPrimaryLight,
    processText = ProcessTextLight,
    processBar = ProcessBarLight,
    placeholder = PlaceholderLight,
    pageBackground = PageBgLight,
    settingsBackground = SettingsBgLight,
    card = CardLight,
    outline = OutlineLight,
    divider = DividerLight,
    danger = DangerRed,
    warning = WarningAmber,
    capsuleSelected = CapsuleSelectedLight,
    fieldBackground = FieldBgLight,
    menuBackground = MenuBgLight,
    markdownCodeBlock = MarkdownCodeBgLight,
    markdownInlineCode = MarkdownInlineCodeBgLight,
    markdownQuoteBar = MarkdownQuoteBarLight,
    markdownRule = MarkdownRuleLight,
    scrim = Color.Black.copy(alpha = 0.45f),
)

private val DarkChatColors = ChatColors(
    userBubble = UserBubbleDark,
    onUserBubble = TextPrimaryDark,
    processText = ProcessTextDark,
    processBar = ProcessBarDark,
    placeholder = PlaceholderDark,
    pageBackground = PageBgDark,
    settingsBackground = SettingsBgDark,
    card = CardDark,
    outline = OutlineDark,
    divider = DividerDark,
    danger = DangerRedDark,
    warning = WarningAmberDark,
    capsuleSelected = CapsuleSelectedDark,
    fieldBackground = FieldBgDark,
    menuBackground = MenuBgDark,
    markdownCodeBlock = MarkdownCodeBgDark,
    markdownInlineCode = MarkdownInlineCodeBgDark,
    markdownQuoteBar = MarkdownQuoteBarDark,
    markdownRule = MarkdownRuleDark,
    scrim = Color.Black.copy(alpha = 0.62f),
)

val LocalChatColors = staticCompositionLocalOf { LightChatColors }

/**
 * 把主题色套到基础色板上。
 *
 * 只换两处"品牌色表面"：**选中态填充**与**用户气泡**。
 * 它们原来是写死的淡蓝（`CapsuleSelectedLight` / `UserBubbleLight`），
 * 换成绿色主题后若还留着蓝，一眼就是"没换干净"。
 */
fun themePageBackground(dark: Boolean, accent: AccentColor): Color = chatColors(dark, accent).pageBackground

private fun chatColors(dark: Boolean, accent: AccentColor): ChatColors {
    val tint = tintOf(accent.primary(dark), dark)
    return (if (dark) DarkChatColors else LightChatColors).copy(
        userBubble = tint,
        // 选中态浅底用「主色 × alpha」而不是与页面底色混出的实色：
        // 压在壁纸 / 玻璃上时不会变成一块不透明的色块（docs/32 §32.4.5）。
        // 比例与 tintOf 相同，纯色底上观感不变。
        capsuleSelected = accent.primary(dark).copy(alpha = if (dark) 0.22f else 0.16f),
    )
}

/**
 * 当前是不是深色。
 *
 * 存在的理由只有一个：**系统栏（状态栏图标 / 导航栏「小白条」）要跟 App 的主题走，
 * 而不是跟系统的**。设置页可以强制浅色或深色，系统的深浅完全可能是另一个值 ——
 * 不看着这个值，就会出现「深色 App 顶着一排看不见的状态栏图标 / 底下一条小白条」。
 *
 * 见 [ApplySystemBarAppearance]。
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }
val LocalAccentColor = staticCompositionLocalOf { AccentColor.BLUE }

/** Persisted typography settings. Null keeps the pre-category runtime exactly unchanged. */
val LocalTypographySettings = staticCompositionLocalOf<TypographySettings?> { null }

/** Fonts resolved by the caller from stored font IDs. */
@Immutable
data class TypographyFonts(val ui: FontFamily? = null, val user: FontFamily? = null, val assistant: FontFamily? = null) {
    operator fun get(slot: FontSlot): FontFamily? = when (slot) {
        FontSlot.USER -> user
        FontSlot.ASSISTANT -> assistant
        FontSlot.UI -> ui
    }
}

val LocalTypographyFonts = staticCompositionLocalOf { TypographyFonts() }
val LocalTypographyGlobalScale = staticCompositionLocalOf { 1f }
val LocalTypographyTextColor = staticCompositionLocalOf<Color?> { null }
internal val LocalSystemTextScale = staticCompositionLocalOf<Float?> { null }
internal val LocalBaseTextScheme = staticCompositionLocalOf<ColorScheme?> { null }
internal val LocalBaseChatColors = staticCompositionLocalOf<ChatColors?> { null }

internal fun resolveCategoryFontScale(
    settings: com.simplechat.app.data.CategoryTypographySettings,
    globalScale: Float,
    systemScale: Float,
): Float = if (settings.overrideFontSizeEnabled && settings.fontScale?.let { it.isFinite() && it > 0f } == true) {
    (systemScale * checkNotNull(settings.fontScale)).takeIf { it.isFinite() && it > 0f } ?: globalScale
} else globalScale

/** How much of the accent's saturation survives in bubble text. */
private const val BubbleThemeChroma = 0.7f

/**
 * Accent color used for user-bubble text.
 *
 * The raw brand accent is tuned for small, high-chroma hits (buttons, links, the send
 * key). Used as the color of a whole paragraph it reads as shouting, so chroma is pulled
 * back while the hue stays — the bubble still reads as "theme colored", just calmer.
 */
fun bubbleThemeTextColor(accent: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    return Color.hsv(hsv[0], (hsv[1] * BubbleThemeChroma).coerceIn(0f, 1f), hsv[2])
}

/** Scope children to one typography category, applying its saved size and optional color. */
@Composable
fun TypographyCategory(slot: FontSlot, content: @Composable () -> Unit) {
    val settings = LocalTypographySettings.current ?: return content()
    val category = settings[slot]
    val density = LocalDensity.current
    val globalScale = LocalTypographyGlobalScale.current
    val systemScale = LocalSystemTextScale.current ?: density.fontScale
    val categoryScale = resolveCategoryFontScale(category, globalScale, systemScale)
    val baseScheme = LocalBaseTextScheme.current ?: MaterialTheme.colorScheme
    val baseColors = LocalBaseChatColors.current ?: LocalChatColors.current
    val defaultColor = when (slot) {
        FontSlot.USER -> baseColors.onUserBubble
        FontSlot.ASSISTANT -> baseScheme.onBackground
        FontSlot.UI -> baseScheme.onBackground
    }
    val themeColor = when (slot) {
        // A whole paragraph in the raw accent shouts; dampen it for body copy.
        FontSlot.USER -> bubbleThemeTextColor(baseScheme.primary)
        FontSlot.UI -> if (category.useThemeColor) baseScheme.primary else null
        FontSlot.ASSISTANT -> null
    }
    val resolved = category.effectiveTextColor(
        slot = slot,
        isDark = LocalIsDarkTheme.current,
        defaultArgb = defaultColor.toArgb(),
        themeArgb = themeColor?.toArgb(),
    )
    val effectiveColor = Color(resolved).takeIf {
        category.customColorEnabled || (slot != FontSlot.ASSISTANT && category.useThemeColor)
    }
    val baseTypography = SimpleChatTypography
    val explicitFont = LocalTypographyFonts.current[slot]
    val legacyFont = when (slot) {
        FontSlot.USER -> LocalUserFontFamily.current
        FontSlot.ASSISTANT -> LocalAssistantFontFamily.current
        FontSlot.UI -> null
    }
    // User bubbles historically inherited the UI font when no user font was selected.
    val chosenFont = explicitFont ?: legacyFont ?: LocalTypographyFonts.current.ui.takeIf { slot == FontSlot.USER }
    val effectiveScheme = when {
        effectiveColor == null -> baseScheme
        slot == FontSlot.USER -> baseScheme
        else -> baseScheme.copy(
            onBackground = effectiveColor,
            onSurface = effectiveColor,
            onSurfaceVariant = effectiveColor.copy(alpha = 0.78f),
        )
    }
    val effectiveChatColors = when {
        effectiveColor == null -> baseColors
        slot == FontSlot.USER -> baseColors.copy(onUserBubble = effectiveColor)
        slot == FontSlot.ASSISTANT -> baseColors.copy(processText = effectiveColor.copy(alpha = 0.78f))
        else -> baseColors.copy(
            processText = effectiveColor.copy(alpha = 0.78f),
            placeholder = effectiveColor.copy(alpha = 0.55f),
        )
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, categoryScale),
        LocalTypographyTextColor provides effectiveColor,
        LocalChatColors provides effectiveChatColors,
    ) {
        val scopedTypography = when (slot) {
            FontSlot.USER, FontSlot.ASSISTANT -> baseTypography.copy(
                bodyLarge = chosenFont?.let { baseTypography.bodyLarge.copy(fontFamily = it) } ?: baseTypography.bodyLarge,
                bodyMedium = chosenFont?.let { baseTypography.bodyMedium.copy(fontFamily = it) } ?: baseTypography.bodyMedium,
            )
            FontSlot.UI -> {
                val uiFont = LocalTypographyFonts.current.ui
                if (uiFont != null) baseTypography.withFontFamily(uiFont) else baseTypography
            }
        }
        MaterialTheme(colorScheme = effectiveScheme, typography = scopedTypography) {
            CompositionLocalProvider(
                LocalContentColor provides (effectiveColor ?: effectiveScheme.onBackground),
                content = content,
            )
        }
    }
}

/** Preview the exact runtime typography contract from an editor draft without mutating stored state. */
@Composable
fun TypographyPreview(
    draft: TypographyDraft,
    fonts: TypographyFonts,
    dark: Boolean,
    systemFontScale: Float? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val systemScale = systemFontScale?.takeIf { it.isFinite() && it > 0f }
        ?: LocalSystemTextScale.current ?: density.fontScale
    val requestedGlobalScale = if (draft.followSystemFont) systemScale else draft.fontScale
    val globalScale = requestedGlobalScale.takeIf { it.isFinite() && it > 0f } ?: systemScale
    val themeColor = LocalAccentColor.current
    val previewTokens = remember(dark, themeColor) {
        com.simplechat.app.ui.theme.tokens.ThemeTokens(dark = dark, colors = chatColors(dark, themeColor), scheme = staticScheme(dark, themeColor))
    }
    CompositionLocalProvider(
        com.simplechat.app.ui.theme.tokens.LocalThemeTokens provides previewTokens,
        LocalChatColors provides previewTokens.colors,
        LocalBaseTextScheme provides previewTokens.scheme,
        LocalBaseChatColors provides previewTokens.colors,
        LocalAccentColor provides themeColor,
        LocalIsDarkTheme provides dark,
        LocalTypographySettings provides draft.typography,
        LocalTypographyFonts provides fonts,
        LocalUserFontFamily provides fonts.user,
        LocalAssistantFontFamily provides fonts.assistant,
        LocalTypographyGlobalScale provides globalScale,
        LocalSystemTextScale provides systemScale,
        LocalDensity provides Density(density.density, globalScale),
    ) {
        TypographyCategory(FontSlot.UI, content)
    }
}

@Composable
fun TypographyCategoryPreview(
    draft: TypographyDraft,
    fonts: TypographyFonts,
    dark: Boolean,
    slot: FontSlot,
    systemFontScale: Float? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val systemScale = systemFontScale?.takeIf { it.isFinite() && it > 0f }
        ?: LocalSystemTextScale.current ?: density.fontScale
    val requestedGlobalScale = if (draft.followSystemFont) systemScale else draft.fontScale
    val globalScale = requestedGlobalScale.takeIf { it.isFinite() && it > 0f } ?: systemScale
    val themeColor = LocalAccentColor.current
    val previewTokens = remember(dark, themeColor) {
        com.simplechat.app.ui.theme.tokens.ThemeTokens(
            dark = dark,
            colors = chatColors(dark, themeColor),
            scheme = staticScheme(dark, themeColor),
        )
    }
    CompositionLocalProvider(
        com.simplechat.app.ui.theme.tokens.LocalThemeTokens provides previewTokens,
        LocalChatColors provides previewTokens.colors,
        LocalBaseTextScheme provides previewTokens.scheme,
        LocalBaseChatColors provides previewTokens.colors,
        LocalAccentColor provides themeColor,
        LocalIsDarkTheme provides dark,
        LocalTypographySettings provides draft.typography,
        LocalTypographyFonts provides fonts,
        LocalUserFontFamily provides fonts.user,
        LocalAssistantFontFamily provides fonts.assistant,
        LocalTypographyGlobalScale provides globalScale,
        LocalSystemTextScale provides systemScale,
        LocalDensity provides Density(density.density, globalScale),
    ) {
        TypographyCategory(slot, content)
    }
}

/**
 * 品牌色色板。
 *
 * **刻意不做动态取色（Monet / 莫奈取色）。**
 *
 * 动态取色会把背景、表面、描边全部染上壁纸的颜色 —— 阅读类应用里
 * 正文底色的色偏直接影响长时间阅读的舒适度，而"跟随壁纸"并不带来
 * 任何功能价值，只是让界面看起来像别人的应用。
 *
 * 这里要的是**稳定的中性底 + 一个**（由用户在设置里选定的）**品牌色**：
 * 正文区永远是干净的近白 / 近黑，品牌色永远可辨认 —— 换的只是**色相**，
 * 而不是"跟着壁纸跑"。见 [AccentColor]。
 */
private fun staticScheme(dark: Boolean, accent: AccentColor): ColorScheme {
    val primary = accent.primary(dark)
    val tint = tintOf(primary, dark)
    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = tint,
            onPrimaryContainer = primary,
            background = PageBgDark,
            onBackground = TextPrimaryDark,
            surface = CardDark,
            onSurface = TextPrimaryDark,
            surfaceVariant = FieldBgDark,
            onSurfaceVariant = TextSecondaryDark,
            outline = OutlineDark,
            outlineVariant = DividerDark,
            error = DangerRedDark,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = tint,
            onPrimaryContainer = primary,
            background = PageBgLight,
            onBackground = TextPrimaryLight,
            surface = CardLight,
            onSurface = TextPrimaryLight,
            surfaceVariant = FieldBgLight,
            onSurfaceVariant = TextSecondaryLight,
            outline = OutlineLight,
            outlineVariant = DividerLight,
            error = DangerRed,
            onError = Color.White,
        )
    }
}

/**
 * @param accent 主题色（设置页「个性化 → 主题色」）。只影响品牌色那一族，
 *   页面底色与功能色不动 —— 见 [AccentColor]。
 * @param fontScale 全局字号倍率（设置页「字体大小」）。
 *
 * 用 `LocalDensity.fontScale` 实现，而不是逐个改 `TextStyle`：
 * `sp` 换算受影响、`dp` 布局不受影响 —— 正是"只调字号"的语义，
 * 且对 `MarkdownBodyStyle`、`CodeTextStyle` 这些散落的样式一并生效，
 * 不会漏改。
 */
@Composable
fun SimpleChatTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accent: AccentColor = AccentColor.BLUE,
    fontScale: Float = 1f,
    /** 软件 UI 字体层；null = 系统字体。不影响用户发言与模型回复（那两层各自覆盖）。 */
    uiFontFamily: FontFamily? = null,
    typographySettings: TypographySettings? = null,
    typographyFonts: TypographyFonts = TypographyFonts(ui = uiFontFamily),
    /**
     * 主题文件（docs/33）。null = 内置主题。解析顺序：内置默认值 ← 主题文件 ← 用户设置（accent 等），
     * 最后由 [com.simplechat.app.ui.theme.tokens.ThemeResolver] 做可读性兜底。
     */
    themeSpec: com.simplechat.app.ui.theme.tokens.ThemeSpec? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val tokens = androidx.compose.runtime.remember(dark, accent, themeSpec) {
        val base = com.simplechat.app.ui.theme.tokens.ThemeTokens(
            dark = dark,
            colors = chatColors(dark, accent),
            scheme = staticScheme(dark, accent),
        )
        com.simplechat.app.ui.theme.tokens.ThemeResolver.resolve(base, themeSpec).tokens
    }
    val scheme = tokens.scheme

    val baseDensity = LocalDensity.current

    val systemTextScale = baseDensity.fontScale
    val typographyGlobalScale = systemTextScale * fontScale
    CompositionLocalProvider(
        com.simplechat.app.ui.theme.tokens.LocalThemeTokens provides tokens,
        // 旧入口保留：LocalChatColors 与 MaterialTheme.colorScheme 都来自同一份 tokens，
        // 存量组件不用改就能吃到主题文件（迁移计划见 docs/33 §33.7）。
        LocalChatColors provides tokens.colors,
        LocalIsDarkTheme provides dark,
        LocalAccentColor provides accent,
        LocalTypographySettings provides typographySettings,
        LocalTypographyFonts provides typographyFonts,
        LocalTypographyGlobalScale provides typographyGlobalScale,
        LocalSystemTextScale provides systemTextScale,
        LocalBaseTextScheme provides scheme,
        LocalBaseChatColors provides tokens.colors,
        LocalDensity provides Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * fontScale,
        ),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = if (uiFontFamily != null) SimpleChatTypography.withFontFamily(uiFontFamily) else SimpleChatTypography,
        ) {
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
                TypographyCategory(FontSlot.UI, content)
            }
        }
    }
}
