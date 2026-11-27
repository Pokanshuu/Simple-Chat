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
)
