package com.simplechat.app.ui.chat

import com.simplechat.app.data.TypographySettings
import com.simplechat.app.ui.theme.AccentColor
import com.simplechat.app.ui.theme.TypographyFonts
import com.simplechat.app.ui.theme.WallpaperState

/**
 * 导出图片时使用的完整外观快照。
 *
 * 只在「使用个性化样式」勾选时创建；null 表示沿用 v1.2.6 的浅色纯底外观。
 * 壁纸为 null 时只使用主题色 / 字体 / 深浅色。
 */
internal data class ShareImageStyle(
    val dark: Boolean,
    val accent: AccentColor,
    val typography: TypographySettings?,
    val typographyFonts: TypographyFonts,
    val fontScale: Float,
    val systemFontScale: Float,
    val wallpaper: WallpaperState?,
    /** 品牌行是否画 Simple Cat 图标（跟随「使用 Simple Cat 图标」设置）。 */
    val showCatMark: Boolean = true,
)

/**
 * 导出时壁纸保护层的实际不透明度（§49.6.5）。
 *
 * 公式：`max(1 - 用户 opacity, floor)`，floor 浅色 0.55 / 深色 0.65 ——
 * 用户把壁纸调得再透，正文也不会直接压在照片上。
 */
internal fun shareWallpaperVeil(opacity: Float, dark: Boolean): Float =
    maxOf(1f - opacity.coerceIn(0f, 1f), if (dark) 0.65f else 0.55f)
