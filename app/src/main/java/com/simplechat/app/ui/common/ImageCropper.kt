package com.simplechat.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import kotlin.math.max
import kotlin.math.min

/**
 * 裁剪比例。`ratio = 宽 / 高`；`null` = 跟随原图。
 *
 * **没有"自由比例"、没有"适配方式"**（docs/32 §32.6.5）：裁剪框永远被图片铺满，
 * 不存在露黑边或拉伸变形的状态；用户只在几个好用的比例里挑。
 */
data class CropAspect(val label: String, val ratio: Float?)

/** 附件图片的推荐比例：原图 / 1:1 / 4:3 / 3:4 / 16:9 / 9:16。 */
@Composable
fun attachmentCropAspects(): List<CropAspect> = listOf(
    CropAspect(stringResource(R.string.crop_aspect_original), null),
    CropAspect("1:1", 1f),
    CropAspect("4:3", 4f / 3f),
    CropAspect("3:4", 3f / 4f),
    CropAspect("16:9", 16f / 9f),
    CropAspect("9:16", 9f / 16f),
)

/**
 * 通用图片裁剪器：全屏、拖动平移、双指缩放、双击复位。
 *
 * - 图片**始终铺满**裁剪框：最小缩放 = 铺满所需的缩放，平移被钳在不露底的范围内。
 * - [preview] 画在裁剪框**内部**、图片之上：壁纸用它叠一层"聊天页实际效果"，
 *   附件不需要就传 null。
 * - 输出是原图像素坐标下的裁剪矩形，由调用方去真正裁切 / 落盘。
 *
 * @param aspects 可选比例；只有一项时不显示比例条（比如壁纸固定为屏幕比例）。
 */
@Composable
fun ImageCropper(
    image: ImageBitmap,
    aspects: List<CropAspect>,
    onDismiss: () -> Unit,
    onConfirm: (Rect) -> Unit,
    title: String = stringResource(R.string.crop_title),
    initialAspect: Int = 0,
    previewLabel: String? = null,
    preview: (@Composable (frame: Size) -> Unit)? = null,
) {
    var aspectIndex by remember { mutableStateOf(initialAspect.coerceIn(0, aspects.lastIndex)) }
    var showPreview by remember { mutableStateOf(preview != null) }
    val imageW = image.width.toFloat()
    val imageH = image.height.toFloat()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(CropperBackground)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // ── 顶栏：取消 / 标题 / 完成 ───────────────────
            var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.Screen, vertical = Space.Gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DarkPill(stringResource(R.string.action_cancel), onClick = onDismiss)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f).padding(horizontal = Space.Inline),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                DarkPill(stringResource(R.string.crop_done), primary = true, onClick = { confirm?.invoke() })
            }

            // ── 裁剪区 ───────────────────────────────────
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 放大后的图片不能盖到顶栏 / 比例条上
                    .clipToBounds()
                    .padding(Space.Section),
            ) {
                val density = LocalDensity.current
                val areaW = with(density) { maxWidth.toPx() }
                val areaH = with(density) { maxHeight.toPx() }
                val ratio = aspects.getOrNull(aspectIndex)?.ratio ?: (imageW / imageH)
                // 裁剪框：在可用区域内按比例尽量大、居中
                val frameW = min(areaW, areaH * ratio)
                val frameH = frameW / ratio
                val frame = Rect(Offset((areaW - frameW) / 2f, (areaH - frameH) / 2f), Size(frameW, frameH))

                // 铺满所需的最小缩放；用户最多再放大 5 倍
                val coverScale = max(frameW / imageW, frameH / imageH)
                var zoom by remember(aspectIndex, areaW, areaH) { mutableFloatStateOf(1f) }
                // 图片中心相对裁剪框中心的偏移（像素）
                var pan by remember(aspectIndex, areaW, areaH) { mutableStateOf(Offset.Zero) }

                fun clampPan(p: Offset, z: Float): Offset {
                    val s = coverScale * z
                    // 浮点误差会让"刚好铺满"时算出 -0.00006 这种负数，先钳到 ≥ 0
                    val maxX = ((imageW * s - frameW) / 2f).coerceAtLeast(0f)
                    val maxY = ((imageH * s - frameH) / 2f).coerceAtLeast(0f)
                    return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
                }

                LaunchedEffect(frame, coverScale, zoom, pan) {
                    confirm = {
                        val s = coverScale * zoom
                        // 裁剪框中心在原图中的位置
                        val cx = imageW / 2f - pan.x / s
                        val cy = imageH / 2f - pan.y / s
                        val w = (frameW / s).coerceAtMost(imageW)
                        val h = (frameH / s).coerceAtMost(imageH)
                        // 同理：铺满时 imageW - w 可能是极小的负数，区间上界先钳到 ≥ 0
                        val left = (cx - w / 2f).coerceIn(0f, (imageW - w).coerceAtLeast(0f))
                        val top = (cy - h / 2f).coerceIn(0f, (imageH - h).coerceAtLeast(0f))
                        onConfirm(Rect(left, top, left + w, top + h))
                    }
                }

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(aspectIndex, areaW, areaH) {
                            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                val newZoom = (zoom * zoomChange).coerceIn(1f, 5f)
                                val actual = newZoom / zoom
                                // 以手指中心为支点缩放：支点下的像素保持不动
                                val pivot = centroid - frame.center
                                val scaled = (pan - pivot) * actual + pivot
                                zoom = newZoom
                                pan = clampPan(scaled + panChange, newZoom)
                            }
                        }
                        .pointerInput(aspectIndex) {
                            detectTapGestures(onDoubleTap = {
                                zoom = 1f
                                pan = Offset.Zero
                            })
                        },
                ) {
                    val s = coverScale * zoom
                    val drawW = imageW * s
                    val drawH = imageH * s
                    val left = frame.center.x + pan.x - drawW / 2f
                    val top = frame.center.y + pan.y - drawH / 2f
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(left.toInt(), top.toInt()),
                        dstSize = IntSize(drawW.toInt(), drawH.toInt()),
                    )
                    // 框外压暗，框内保持原样
                    val hole = Path().apply {
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(frame, CornerRadius(4.dp.toPx())))
                    }
                    clipPath(hole, clipOp = ClipOp.Difference) {
                        drawRect(Color.Black.copy(alpha = 0.62f))
                    }
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.9f),
                        topLeft = frame.topLeft,
                        size = frame.size,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                    // 三分线：只在框内，很淡
                    for (i in 1..2) {
                        val x = frame.left + frame.width * i / 3f
                        val y = frame.top + frame.height * i / 3f
                        drawLine(Color.White.copy(alpha = 0.28f), Offset(x, frame.top), Offset(x, frame.bottom), 1f)
                        drawLine(Color.White.copy(alpha = 0.28f), Offset(frame.left, y), Offset(frame.right, y), 1f)
                    }
                }

                // 效果预览：画在裁剪框内部、图片之上，不吃手势
                if (preview != null && showPreview) {
                    val frameDpW = with(density) { frame.width.toDp() }
                    val frameDpH = with(density) { frame.height.toDp() }
                    Box(
                        Modifier
                            .offset { IntOffset(frame.left.toInt(), frame.top.toInt()) }
                            .size(frameDpW, frameDpH),
                    ) { preview(frame.size) }
                }
            }

            // ── 底栏：比例 / 预览开关 ───────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Space.Screen, vertical = Space.Inline),
                horizontalArrangement = Arrangement.spacedBy(Space.Gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (aspects.size > 1) aspects.forEachIndexed { index, aspect ->
                    DarkPill(aspect.label, selected = index == aspectIndex, onClick = { aspectIndex = index })
                }
                if (preview != null && previewLabel != null) {
                    DarkPill(previewLabel, selected = showPreview, onClick = { showPreview = !showPreview })
                }
            }
            Text(
                text = stringResource(R.string.crop_hint),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Space.Inline),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** 深色全屏页上的胶囊按钮（§15.3：带文字的按钮一律胶囊）。 */
@Composable
private fun DarkPill(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    primary: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val fill = when {
        primary -> accent
        selected -> Color.White
        else -> Color.White.copy(alpha = 0.12f)
    }
    val content = when {
        primary -> Color.White
        selected -> Color.Black
        else -> Color.White
    }
    Box(
        Modifier
            .clip(PillShape)
            .background(fill)
            .border(1.dp, if (primary || selected) Color.Transparent else Color.White.copy(alpha = 0.22f), PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

private val CropperBackground = Color(0xFF0B0B0C)

/** 按裁剪矩形切出位图（原图像素坐标）。 */
fun android.graphics.Bitmap.cropTo(rect: Rect): android.graphics.Bitmap {
    val x = rect.left.toInt().coerceIn(0, width - 1)
    val y = rect.top.toInt().coerceIn(0, height - 1)
    val w = rect.width.toInt().coerceIn(1, width - x)
    val h = rect.height.toInt().coerceIn(1, height - y)
    return android.graphics.Bitmap.createBitmap(this, x, y, w, h)
}
