package com.simplechat.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File

/**
 * 聊天背景壁纸的当前状态。
 *
 * 只把**文件**和全部调节参数传下去，不传 Bitmap：解码放在用的地方
 * （聊天页），设置页预览自己解一份 —— 两边各自持有生命周期。
 */
data class WallpaperState(
    val file: File? = null,
    /** 叠在背景色上的不透明度；0 = 完全看不见。 */
    val opacity: Float = 0.35f,
    /** 模糊半径（dp）；0 = 不模糊。低版本用降采样近似。 */
    val blur: Float = 0f,
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    /** 画面位置，0..1，0.5 = 居中。 */
    val offsetX: Float = 0.5f,
    val offsetY: Float = 0.5f,
    val scale: Float = 1f,
    /** 额外压暗（叠一层黑）。 */
    val dim: Float = 0f,
    /** COVER / FILL / CENTER / STRETCH。 */
    val fit: String = "COVER",
    val flipH: Boolean = false,
) {
    val active: Boolean get() = file != null

    /** 有没有需要走 ColorMatrix 的画面调节（没有就省掉一次 Paint 构造）。 */
    val hasColorTuning: Boolean
        get() = brightness != 1f || contrast != 1f || saturation != 1f
}

/** 壁纸（设置 → 外观 → 壁纸）；null = 纯色背景。 */
val LocalWallpaper = staticCompositionLocalOf { WallpaperState() }

/**
 * 聊天界面的玻璃不透明度（输入栏 / 侧栏 / 顶栏卡片）。
 *
 * `0` = 不透明（关掉玻璃），否则作为卡片底色的 alpha —— 壁纸本身已经按用户的
 * 模糊参数画过，半透明卡片直接透出那张模糊图，就是"毛玻璃"。
 * 可读性下限由 UI 层保证（见各使用点的 coerceAtLeast）。
 */
val LocalChatGlassAlpha = staticCompositionLocalOf { 0f }
