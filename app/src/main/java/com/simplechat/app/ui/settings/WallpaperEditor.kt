package com.simplechat.app.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppSlider
import com.simplechat.app.ui.common.BubbleShapeOut
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.WallpaperState
import com.simplechat.app.ui.theme.wallpaperPaint
import kotlin.math.max

/*
 * 壁纸编辑器（docs/32 §32.6）：交互参照系统相册的编辑页。
 *
 *   ┌ ✕          编辑壁纸           保存 ┐
 *   │                                   │
 *   │   最终效果全屏预览（屏幕比例）       │  ← 壁纸 + 聊天界面示意，所见即所得
 *   │                                   │
 *   ├ [ 当前参数的滑块 ]          [对比] ┤  ← 按住「对比」看原图
 *   ├ [显眼度][暗化][模糊][亮度]…        ┤  ← 当前分类下的参数
 *   └    ( 裁切 )  ( 调节 )  ( 面板 )     ┘  ← 分类
 *
 * - 裁切：画幅固定为屏幕比例，图片始终铺满（没有露黑边 / 拉伸的选项），拖动 + 双指缩放。
 * - 模糊只有四档：聊天页用降采样近似模糊，本来就只有这几档真实效果，连续滑块是骗人的。
 * - 所有改动都只在编辑器里预览，点「保存」才一次性落盘。
 */

/** 编辑器里的全部可调值。 */
data class WallpaperEdit(
    val opacity: Float,
    val dim: Float,
    val blurLevel: Int,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val flipH: Boolean,
    val glass: Boolean,
    val panelAlpha: Float,
)

/** 模糊四档对应写进设置的值；与聊天页的降采样档位一一对应（1 / 2 / 4 / 8）。 */
val WallpaperBlurLevels = listOf(0f, 3f, 10f, 20f)
private val BlurSamples = listOf(1, 2, 4, 8)

fun blurLevelOf(blur: Float): Int = when {
    blur < 1f -> 0
    blur < 6f -> 1
    blur < 14f -> 2
    else -> 3
}

/** 参数区高度：滑块（含刻度文案）+ 参数卡片。 */
private val EditorControlsHeight = 196.dp

private enum class EditTab { CROP, ADJUST, PANEL }

private enum class Tool { OPACITY, DIM, BLUR, BRIGHTNESS, CONTRAST, SATURATION, GLASS, PANEL_ALPHA }

/**
 * @param source 裁剪原图（不是上一次裁过的结果）。
 * @param onSave 原图像素坐标下的裁剪框 + 调好的参数。
 */
@Composable
fun WallpaperEditor(
    source: Bitmap,
    initial: WallpaperEdit,
    onDismiss: () -> Unit,
    onSave: (Rect, WallpaperEdit) -> Unit,
) {
    val colors = LocalChatColors.current
    val window = LocalWindowInfo.current.containerSize
    // 画幅 = 屏幕比例：聊天页的壁纸就是铺满整屏的
    val screenRatio = if (window.height > 0) window.width.toFloat() / window.height else 9f / 19.5f

    var edit by remember { mutableStateOf(initial) }
    var tab by remember { mutableStateOf(EditTab.CROP) }
    var tool by remember { mutableStateOf(Tool.OPACITY) }
    var comparing by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // 四档模糊的降采样副本：打开时算一次，拖滑块时只是换一张图来画，零解码。
    val levels = remember(source) {
        BlurSamples.map { sample ->
            if (sample == 1) source.asImageBitmap()
            else Bitmap.createScaledBitmap(
                source,
                (source.width / sample).coerceAtLeast(1),
                (source.height / sample).coerceAtLeast(1),
                true,
            ).asImageBitmap()
        }
    }
    val imageW = source.width.toFloat()
    val imageH = source.height.toFloat()
    var frameSize by remember { mutableStateOf(Size.Zero) }

    fun coverScale(frame: Size) = max(frame.width / imageW, frame.height / imageH)
    fun clampPan(p: Offset, z: Float, frame: Size): Offset {
        val s = coverScale(frame) * z
        val maxX = ((imageW * s - frame.width) / 2f).coerceAtLeast(0f)
        val maxY = ((imageH * s - frame.height) / 2f).coerceAtLeast(0f)
        return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
    }
    fun cropRect(): Rect {
        val frame = frameSize
        val pan = clampPan(pan, zoom, frame)
        val s = coverScale(frame) * zoom
        val cx = imageW / 2f - pan.x / s
        val cy = imageH / 2f - pan.y / s
        val w = frame.width / s
        val h = frame.height / s
        val left = (cx - w / 2f).coerceIn(0f, (imageW - w).coerceAtLeast(0f))
        val top = (cy - h / 2f).coerceIn(0f, (imageH - h).coerceAtLeast(0f))
        return Rect(left, top, (left + w).coerceAtMost(imageW), (top + h).coerceAtMost(imageH))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.settingsBackground)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // ── 顶栏 ─────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.Inline, vertical = Space.Gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircleButton(
                    icon = AppIcons.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                )
                Text(
                    text = stringResource(R.string.wallpaper_editor_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .clip(PillShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { if (frameSize.width > 0f) onSave(cropRect(), edit) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text(
                        stringResource(R.string.action_save),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            // ── 最终效果预览 ─────────────────────────────
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Space.Section, vertical = Space.Gap),
                contentAlignment = Alignment.Center,
            ) {
                val density = LocalDensity.current
                val areaW = with(density) { maxWidth.toPx() }
                val areaH = with(density) { maxHeight.toPx() }
                val frameW = minOf(areaW, areaH * screenRatio)
                val frameH = frameW / screenRatio
                val frame = Size(frameW, frameH)
                frameSize = frame
                val frameDpW = with(density) { frameW.toDp() }
                val frameDpH = with(density) { frameH.toDp() }

                Box(
                    Modifier
                        .size(frameDpW, frameDpH)
                        .clip(SoftShape)
                        .pointerInput(tab, frame) {
                            if (tab != EditTab.CROP) return@pointerInput
                            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                val newZoom = (zoom * zoomChange).coerceIn(1f, 5f)
                                val pivot = centroid - Offset(frameW / 2f, frameH / 2f)
                                // 翻转后屏幕上的横向拖动与原图方向相反
                                val signedPan = if (edit.flipH) Offset(-panChange.x, panChange.y) else panChange
                                val signedPivot = if (edit.flipH) Offset(-pivot.x, pivot.y) else pivot
                                val scaled = (pan - signedPivot) * (newZoom / zoom) + signedPivot
                                zoom = newZoom
                                pan = clampPan(scaled + signedPan, newZoom, frame)
                            }
                        }
                        .pointerInput(tab) {
                            if (tab != EditTab.CROP) return@pointerInput
                            detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero })
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        // 画幅尺寸变了（旋转 / 分屏）也要保证铺满：绘制时再钳一次
                        val pan = clampPan(pan, zoom, frame)
                        val s = coverScale(frame) * zoom
                        val drawW = imageW * s
                        val drawH = imageH * s
                        val left = size.width / 2f + pan.x - drawW / 2f
                        val top = size.height / 2f + pan.y - drawH / 2f
                        if (!comparing) drawRect(colors.pageBackground)
                        val state = if (comparing) WallpaperState(opacity = 1f) else WallpaperState(
                            opacity = edit.opacity,
                            brightness = edit.brightness,
                            contrast = edit.contrast,
                            saturation = edit.saturation,
                        )
                        val bitmap = levels[if (comparing) 0 else edit.blurLevel.coerceIn(0, 3)]
                        val paint = wallpaperPaint(state)
                        clipRect {
                            scale(if (edit.flipH && !comparing) -1f else 1f, 1f) {
                                drawIntoCanvas { canvas ->
                                    canvas.nativeCanvas.drawBitmap(
                                        bitmap.asAndroidBitmap(),
                                        null,
                                        android.graphics.RectF(left, top, left + drawW, top + drawH),
                                        paint,
                                    )
                                }
                            }
                            if (!comparing && edit.dim > 0f) drawRect(Color.Black.copy(alpha = edit.dim))
                        }
                    }
                    if (!comparing) {
                        // 按屏幕比例缩小渲染聊天界面示意：字号、间距与真实页面成比例
                        val shrink = frameW / window.width.coerceAtLeast(1)
                        CompositionLocalProvider(
                            LocalDensity provides Density(density.density * shrink, density.fontScale),
                        ) { ChatMock(edit) }
                    }
                    if (tab == EditTab.CROP && !comparing) CropGuides()
                }
            }

            // ── 参数区 ───────────────────────────────────
            val tools = when (tab) {
                EditTab.CROP -> emptyList()
                EditTab.ADJUST -> listOf(Tool.OPACITY, Tool.DIM, Tool.BLUR, Tool.BRIGHTNESS, Tool.CONTRAST, Tool.SATURATION)
                EditTab.PANEL -> listOf(Tool.GLASS, Tool.PANEL_ALPHA)
            }
            if (tool !in tools && tools.isNotEmpty()) tool = tools.first()

            // 参数区**固定高度**：切换分类时预览画幅不跳动
            Column(Modifier.fillMaxWidth().height(EditorControlsHeight), verticalArrangement = Arrangement.Center) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = Space.Screen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tab == EditTab.CROP) {
                    EditorPill(stringResource(R.string.wallpaper_flip_h), selected = edit.flipH) {
                        edit = edit.copy(flipH = !edit.flipH)
                    }
                    Spacer(Modifier.weight(1f))
                    EditorPill(stringResource(R.string.wallpaper_editor_reset_crop)) { zoom = 1f; pan = Offset.Zero }
                } else {
                    Box(Modifier.weight(1f)) { ToolSlider(tool, edit) { edit = it } }
                    Spacer(Modifier.size(Space.Inline))
                    CompareButton { comparing = it }
                }
            }

            if (tools.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Space.Screen, vertical = Space.Gap),
                    horizontalArrangement = Arrangement.spacedBy(Space.Gap),
                ) {
                    tools.forEach { t ->
                        ToolTile(
                            label = stringResource(t.label()),
                            value = t.display(edit),
                            selected = t == tool,
                            onClick = {
                                if (t == Tool.GLASS) edit = edit.copy(glass = !edit.glass)
                                tool = t
                            },
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.crop_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Space.Inline),
                )
            }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.Screen, vertical = Space.Inline),
                horizontalArrangement = Arrangement.spacedBy(Space.Inline, Alignment.CenterHorizontally),
            ) {
                EditTab.entries.forEach { t ->
                    EditorPill(
                        text = stringResource(
                            when (t) {
                                EditTab.CROP -> R.string.wallpaper_editor_tab_crop
                                EditTab.ADJUST -> R.string.wallpaper_editor_tab_adjust
                                EditTab.PANEL -> R.string.wallpaper_editor_tab_panel
                            },
                        ),
                        selected = t == tab,
                        filled = true,
                    ) { tab = t }
                }
            }
        }
    }
}

private fun Tool.label(): Int = when (this) {
    Tool.OPACITY -> R.string.wallpaper_editor_opacity
    Tool.DIM -> R.string.wallpaper_dim
    Tool.BLUR -> R.string.wallpaper_blur
    Tool.BRIGHTNESS -> R.string.wallpaper_brightness
    Tool.CONTRAST -> R.string.wallpaper_contrast
    Tool.SATURATION -> R.string.wallpaper_saturation
    Tool.GLASS -> R.string.wallpaper_glass_panel
    Tool.PANEL_ALPHA -> R.string.wallpaper_panel_alpha
}

@Composable
private fun Tool.display(e: WallpaperEdit): String = when (this) {
    Tool.OPACITY -> "${(e.opacity * 100).toInt()}%"
    Tool.DIM -> "${(e.dim * 100).toInt()}%"
    Tool.BLUR -> stringResource(blurLabels[e.blurLevel.coerceIn(0, 3)])
    Tool.BRIGHTNESS -> signed(e.brightness)
    Tool.CONTRAST -> signed(e.contrast)
    Tool.SATURATION -> signed(e.saturation)
    Tool.GLASS -> stringResource(if (e.glass) R.string.value_on else R.string.value_off)
    Tool.PANEL_ALPHA -> "${(e.panelAlpha * 100).toInt()}%"
}

private val blurLabels = listOf(
    R.string.wallpaper_blur_none,
    R.string.wallpaper_blur_light,
    R.string.wallpaper_blur_medium,
    R.string.wallpaper_blur_strong,
)

/** 1.0 为中点，显示成 −40 … +40 这种相册式的相对值。 */
private fun signed(v: Float): String {
    val n = ((v - 1f) * 100).toInt()
    return if (n > 0) "+$n" else "$n"
}

@Composable
private fun ToolSlider(tool: Tool, e: WallpaperEdit, onChange: (WallpaperEdit) -> Unit) {
    fun snapMid(v: Float) = if (kotlin.math.abs(v - 1f) < 0.03f) 1f else v
    when (tool) {
        Tool.OPACITY -> AppSlider(e.opacity, { onChange(e.copy(opacity = it)) }, valueRange = 0.1f..1f)
        Tool.DIM -> AppSlider(e.dim, { onChange(e.copy(dim = it)) }, valueRange = 0f..0.6f)
        Tool.BLUR -> AppSlider(
            value = e.blurLevel.toFloat(),
            onValueChange = { onChange(e.copy(blurLevel = it.roundToIntSafe().coerceIn(0, 3))) },
            valueRange = 0f..3f,
            intervals = 3,
            tickLabels = blurLabels.map { stringResource(it) },
            selectedTick = e.blurLevel,
        )
        Tool.BRIGHTNESS -> AppSlider(e.brightness, { onChange(e.copy(brightness = snapMid(it))) }, valueRange = 0.6f..1.4f)
        Tool.CONTRAST -> AppSlider(e.contrast, { onChange(e.copy(contrast = snapMid(it))) }, valueRange = 0.6f..1.4f)
        Tool.SATURATION -> AppSlider(e.saturation, { onChange(e.copy(saturation = snapMid(it))) }, valueRange = 0f..1.6f)
        Tool.PANEL_ALPHA -> AppSlider(
            value = e.panelAlpha,
            onValueChange = { onChange(e.copy(panelAlpha = it)) },
            valueRange = 0.45f..0.95f,
            enabled = e.glass,
        )
        Tool.GLASS -> Text(
            text = stringResource(R.string.wallpaper_editor_glass_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Float.roundToIntSafe(): Int = kotlin.math.round(this).toInt()

/** 按住看原图（不带调色、不带界面），松手恢复。 */
@Composable
private fun CompareButton(onComparing: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(PillShape)
            .background(LocalChatColors.current.card)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onComparing(true)
                    tryAwaitRelease()
                    onComparing(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.Contrast,
            contentDescription = stringResource(R.string.wallpaper_editor_compare),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ToolTile(label: String, value: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val fill = if (selected) MaterialTheme.colorScheme.primary else colors.card
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .widthIn(min = 76.dp)
            .clip(SoftShape)
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.Inline, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.72f), maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

@Composable
private fun EditorPill(
    text: String,
    selected: Boolean = false,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val fill = when {
        selected && filled -> MaterialTheme.colorScheme.primary
        selected -> colors.capsuleSelected
        else -> colors.card
    }
    val content = when {
        selected && filled -> MaterialTheme.colorScheme.onPrimary
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        Modifier
            .clip(PillShape)
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** 裁切时的三分线，便于构图。 */
@Composable
private fun CropGuides() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(c, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            drawLine(c, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        }
    }
}

/**
 * 聊天界面示意：一条用户气泡 + 一段回复 + 输入栏。
 * 用户气泡与输入栏的不透明度按「面板」里的设置来，和真实页面同一套规则。
 */
@Composable
private fun ChatMock(e: WallpaperEdit) {
    val colors = LocalChatColors.current
    val glassAlpha = if (e.glass) e.panelAlpha else 1f
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 56.dp, bottom = 28.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = stringResource(R.string.wallpaper_preview_user),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onUserBubble,
                modifier = Modifier
                    .clip(BubbleShapeOut)
                    .background(colors.userBubble.copy(alpha = (glassAlpha + 0.30f).coerceIn(0.70f, 1f)))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.wallpaper_preview_reply),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .background(colors.card.copy(alpha = glassAlpha.coerceAtLeast(0.35f)))
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                stringResource(R.string.wallpaper_preview_input),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.placeholder,
            )
        }
    }
}
