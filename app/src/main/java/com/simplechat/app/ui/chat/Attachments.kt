package com.simplechat.app.ui.chat

import android.graphics.BitmapFactory
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.simplechat.app.R
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.AttachmentFiles
import com.simplechat.app.data.Attachments
import com.simplechat.app.data.copyOriginalImage
import com.simplechat.app.data.originalImageMime
import com.simplechat.app.data.originalImageName
import com.simplechat.app.data.saveOriginalImageToGallery
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 附件在界面上的三种样子。
 *
 * 全都**不引图片库**：附件就在数据库里躺着 base64，解码两步就完事，
 * 为一个"显示一张本地图"的需求拖进 Coil 不值得（设计文档 §8）。
 * 关键是**按显示尺寸采样解码** —— 直接解一张 1600×1600 的位图是 10MB 内存，
 * 在一屏聊天里滚两下就 OOM 了。
 */

/** 缩略图边长。 */
private val ThumbSize = 64.dp

/** 消息里图片的最大宽度 / 高度。 */
private val BubbleImageWidth = 220.dp
private val BubbleImageMaxHeight = 300.dp

// ══════════════════════════════════════════════════════════
//  解码
// ══════════════════════════════════════════════════════════

/**
 * 按需解码，**按显示尺寸采样**。
 *
 * 在 IO 线程做；解码期间返回 null，调用方显示占位。
 */
@Composable
fun rememberAttachmentImage(attachment: Attachment, targetPx: Int): ImageBitmap? {
    val context = LocalContext.current
    /*
     * key 里带上 `path`：落盘附件的 `data` 是空串，只用 data 当 key 会让
     * "换了一张图但 key 没变"的情况（同一消息里两张落盘图）命中旧结果。
     */
    val state by produceState<ImageBitmap?>(
        initialValue = null,
        attachment.data,
        attachment.path,
        targetPx,
    ) {
        value = withContext(Dispatchers.IO) {
            // 落盘的从文件读，内联的走 base64 —— 渲染层只认字节，不关心存哪
            val stored = if (attachment.isStored) AttachmentFiles.read(context, attachment.path) else null
            val bytes = stored ?: runCatching { Base64.decode(attachment.data, Base64.NO_WRAP) }.getOrNull()
            bytes?.let { decodeSampled(it, targetPx.coerceAtLeast(1)) }
        }
    }
    return state
}

private fun decodeSampled(bytes: ByteArray, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

    var sample = 1
    var longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / 2 >= targetPx) {
        sample *= 2
        longest /= 2
    }

    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

// ══════════════════════════════════════════════════════════
//  待发送区（输入栏上方）
// ══════════════════════════════════════════════════════════

/** 待发送附件。图片是缩略图，文本文件是一条小卡片。 */
@Composable
fun PendingAttachment(spec: Attachment, onRemove: () -> Unit, onOpen: () -> Unit) {
    Box {
        AttachmentSurface(inUserBubble = false) {
            when (spec.kind) {
                Attachment.Kind.IMAGE -> ImageThumb(spec, ThumbSize, onClick = onOpen)
                Attachment.Kind.TEXT -> FileChip(spec, onClick = onOpen)
            }
        }
        RemoveBadge(onRemove, Modifier.align(Alignment.TopEnd))
    }
}

/** 输入栏上方那一排：横向可滚，附件多了也不会把输入栏撑高。 */
@Composable
fun PendingAttachmentsRow(
    attachments: List<Attachment>,
    onRemove: (Attachment) -> Unit,
    onOpen: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { spec ->
            PendingAttachment(
                spec = spec,
                onRemove = { onRemove(spec) },
                onOpen = { onOpen(spec) },
            )
        }
    }
}

/** 右上角的小 ✕。深色圆底 + 白叉，压在任何底图上都看得清。 */
@Composable
private fun RemoveBadge(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(2.dp)
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.66f))
            .clickable(onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Close,
            contentDescription = stringResource(R.string.attach_remove),
            tint = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.size(11.dp),
        )
    }
}

@Composable
private fun ImageThumb(spec: Attachment, size: Dp, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val surface = LocalAttachmentSurface.current
    val density = LocalDensity.current
    val target = with(density) { size.roundToPx() }
    val bitmap = rememberAttachmentImage(spec, target)

    Box(
        modifier = Modifier
            .size(size)
            .clip(SoftShape)
            .background(surface.background)
            .border(1.dp, surface.border, SoftShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = spec.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = AppIcons.Image,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun FileChip(spec: Attachment, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val surface = LocalAttachmentSurface.current
    Row(
        modifier = Modifier
            .heightIn(min = ThumbSize)
            .widthIn(max = 200.dp)
            .clip(SoftShape)
            .background(surface.background)
            .border(1.dp, surface.border, SoftShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = AppIcons.FileText,
            contentDescription = null,
            tint = colors.processText,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(
                text = spec.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (spec.fileId != null) stringResource(R.string.service_uploaded)
                    else stringResource(R.string.attach_char_count, spec.data.length),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                maxLines = 1,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  消息里
// ══════════════════════════════════════════════════════════

/**
 * 一条消息携带的附件（用户气泡里）。
 *
 * 图片按原始比例占位（用存下来的宽高先撑住），**避免解码完成后高度跳一下** ——
 * 聊天流里跳高度会连带把滚动位置顶掉。
 */
@Composable
fun MessageAttachments(
    attachments: List<Attachment>,
    onOpenImage: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
    /** 在用户气泡里时换一套配色 —— 灰底灰框压在蓝气泡上很脏。 */
    inUserBubble: Boolean = false,
) {
    if (attachments.isEmpty()) return
    val images = attachments.filter { it.isImage }
    val files = attachments.filter { !it.isImage }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        images.forEach { spec ->
            AttachmentSurface(inUserBubble = inUserBubble) {
                BubbleImage(spec, onClick = { onOpenImage(spec) })
            }
        }
        files.forEach { spec ->
            AttachmentSurface(inUserBubble = inUserBubble) {
                FileChip(spec, onClick = {})
            }
        }
    }
}

/**
 * 附件的底板配色。
 *
 * 两种场合各一套：
 * - 常规（输入栏上方、AI 侧）：浅灰底 + 描边，跟页面其它卡片一致
 * - **用户气泡内**：用正文色的低透明度去压蓝底。灰底灰框在蓝气泡上像贴了张
 *   打了补丁的纸，而低透明度黑既保持了气泡的整体感，又仍然分得出"这是一块内容"。
 */
@Composable
private fun AttachmentSurface(
    inUserBubble: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = LocalChatColors.current
    val background = if (inUserBubble) colors.onUserBubble.copy(alpha = 0.06f) else colors.fieldBackground
    val border = if (inUserBubble) colors.onUserBubble.copy(alpha = 0.14f) else colors.outline
    CompositionLocalProvider(LocalAttachmentSurface provides AttachmentSurfaceColors(background, border)) {
        content()
    }
}

private data class AttachmentSurfaceColors(val background: Color, val border: Color)

private val LocalAttachmentSurface = staticCompositionLocalOf {
    AttachmentSurfaceColors(Color.Unspecified, Color.Unspecified)
}

@Composable
private fun BubbleImage(spec: Attachment, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val surface = LocalAttachmentSurface.current
    val density = LocalDensity.current
    val target = with(density) { BubbleImageWidth.roundToPx() }
    val bitmap = rememberAttachmentImage(spec, target)

    val ratio = if (spec.width > 0 && spec.height > 0) {
        spec.width.toFloat() / spec.height.toFloat()
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .widthIn(max = BubbleImageWidth)
            .heightIn(max = BubbleImageMaxHeight)
            .aspectRatio(ratio)
            .clip(SoftShape)
            .background(surface.background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = spec.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = AppIcons.Image,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
//  附件面板
// ══════════════════════════════════════════════════════════

/**
 * 点 `⊕` 展开的附件面板内容。
 *
 * ⚠️ 它**不是** `ModalBottomSheet`，而是和思考档位 / 统计 / 对话设置
 * 完全同一个容器（`InputPanel`）：浮在输入栏上方的一块圆角卡片，
 * 左右留 16dp、和输入栏同宽、共用同一层阴影与点击外部关闭。
 *
 * 一开始用了 Material 的 `ModalBottomSheet` —— 它自带满宽、自带蒙版、自带
 * 拖拽把手，摆在应用里像个外来的东西：左右顶到屏幕边、和输入栏对不齐，
 * 与下面那几个面板是两套语言。**同一件事只该有一套容器。**
 */
@Composable
fun AttachmentPanelContent(
    onCapture: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
    onGenerateImage: (() -> Unit)? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AttachmentTile(stringResource(R.string.attach_camera), AppIcons.Camera, onCapture, Modifier.weight(1f))
        AttachmentTile(stringResource(R.string.attach_gallery), AppIcons.Image, onPickImage, Modifier.weight(1f))
        AttachmentTile(stringResource(R.string.attach_file), AppIcons.FileText, onPickFile, Modifier.weight(1f))
        if (onGenerateImage != null) {
            AttachmentTile(stringResource(R.string.attach_generate_image), AppIcons.ImageEdit,
                onGenerateImage, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun ImageOptionRows(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    showAspectRatioIcons: Boolean = false,
    onSelect: (String) -> Unit,
) {
    PanelSectionTitle(title)
    Spacer(Modifier.size(8.dp))
    options.forEach { (id, label) ->
        val current = id == selected
        Row(
            Modifier.fillMaxWidth().clip(SoftShape)
                .background(if (current) LocalChatColors.current.capsuleSelected else Color.Transparent)
                .clickable { onSelect(id) }.padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            if (showAspectRatioIcons) {
                val width = id.substringBefore(':').toFloatOrNull() ?: 1f
                val height = id.substringAfter(':').toFloatOrNull() ?: 1f
                Canvas(Modifier.size(22.dp)) {
                    val extent = size.minDimension - 4.dp.toPx()
                    val scale = extent / maxOf(width, height)
                    val frame = androidx.compose.ui.geometry.Size(width * scale, height * scale)
                    drawRoundRect(
                        tint, topLeft = Offset((size.width - frame.width) / 2, (size.height - frame.height) / 2),
                        size = frame, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                color = tint,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (current) Icon(AppIcons.Check, stringResource(R.string.model_current),
                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun GeneratedImageContent(
    attachment: Attachment?,
    requestedRatio: Float?,
    stopped: Boolean,
    failed: Boolean,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (Attachment) -> Unit,
) {
    val density = LocalDensity.current
    val bitmap = attachment?.let { rememberAttachmentImage(it, with(density) { BubbleImageMaxHeight.roundToPx() }) }
    val actualRatio = attachment?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height }
    val ratio by animateFloatAsState(
        targetValue = (if (bitmap != null) actualRatio ?: requestedRatio else requestedRatio ?: actualRatio) ?: 1f,
        animationSpec = tween(220), label = "generatedImageRatio",
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Crossfade(
            targetState = bitmap,
            modifier = Modifier.widthIn(max = BubbleImageWidth).heightIn(max = BubbleImageMaxHeight)
                .aspectRatio(ratio).clip(SoftShape),
            animationSpec = tween(240),
            label = "generatedImageReveal",
        ) { image ->
            if (image == null) {
                ImageGenerationGrid(running = !stopped && !failed)
            } else {
                Image(image, attachment?.name, Modifier.fillMaxSize().clickable { attachment?.let(onOpen) },
                    contentScale = ContentScale.Fit)
            }
        }
        if (attachment == null) Row(
            Modifier.widthIn(max = BubbleImageWidth).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(when {
                failed -> R.string.image_generate_failed
                stopped -> R.string.image_generate_stopped
                else -> R.string.image_generating
            }), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (failed || stopped) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.image_generate_retry)) }
            } else {
                TextButton(onClick = onStop) { Text(stringResource(R.string.image_generate_stop)) }
            }
        }
    }
}

@Composable
private fun ImageGenerationGrid(running: Boolean) {
    val color = MaterialTheme.colorScheme.onSurface
    val background = LocalChatColors.current.fieldBackground
    val phase = if (running) rememberInfiniteTransition(label = "imageGrid").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart), label = "imageGridPhase",
    ) else null
    Canvas(Modifier.fillMaxSize().background(background)) {
        val step = 8.dp.toPx()
        val columns = (size.width / step).toInt().coerceAtLeast(1)
        val rows = (size.height / step).toInt().coerceAtLeast(1)
        val insetX = (size.width - (columns - 1) * step) / 2f
        val insetY = (size.height - (rows - 1) * step) / 2f
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = insetX + column * step
            val y = insetY + row * step
            val u = x / size.width
            val v = y / size.height
            val wave = ((1 + kotlin.math.cos(2 * Math.PI * ((phase?.value ?: .3f) - .85 * u - .55 * v))) / 2)
                .toFloat().let { it * it }
            val edge = .35f + .65f * minOf(1f, minOf(u, v, 1 - u, 1 - v) * 6)
            drawCircle(color.copy(alpha = (.10f + .28f * wave) * edge), (.55.dp.toPx() + .90.dp.toPx() * wave), Offset(x, y))
        }
    }
}

@Composable
private fun AttachmentTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    Column(
        modifier = modifier
            .clip(SoftShape)
            .background(colors.fieldBackground)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.processText,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ══════════════════════════════════════════════════════════
//  全屏查看
// ══════════════════════════════════════════════════════════

/**
 * 点开看大图 —— **全屏**，纯查看，不做保存/分享（那是系统相册的事）。
 *
 * ### 为什么是整屏而不是对话框
 *
 * 先前是个居中的圆角卡片：图片被塞进固定高度的盒子里（min 160 / max 520），
 * 竖图两侧、横图上下都会留出一大块底色 —— 那块空白既不是图片也不是背景，
 * 看着就像"图没加载完"。看图这件事本来就是"尽量占满"，
 * 所以改成整屏铺满：图片按 `Fit` 缩放居中，点任意处关闭，
 * 多张时左右可翻。
 *
 * 背景用近黑而不是主题色：图片本身可能是浅底，深底才看得清边界。
 */
@Composable
fun AttachmentViewerDialog(
    images: List<Attachment>,
    initial: Attachment,
    onDismiss: () -> Unit,
    initialIndex: Int? = null,
    /** 非空 = 还没发出去的附件，顶栏显示「裁剪」（docs/32 §32.6.5）。 */
    onCrop: ((Attachment) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    // 全屏尺寸解码：屏幕最长边的像素数，够清楚又不至于把原图解进内存
    val target = with(density) { 1080.dp.roundToPx() }

    val pages = images.ifEmpty { listOf(initial) }
    val startIndex = initialIndex?.takeIf { it in pages.indices } ?: pages.indexOf(initial).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex) { pages.size }
    val current = pages.getOrNull(pagerState.currentPage) ?: initial
    val scope = rememberCoroutineScope()
    var menuPoint by remember { mutableStateOf<IntOffset?>(null) }
    var menuImage by remember { mutableStateOf<Attachment?>(null) }
    var pendingExport by remember { mutableStateOf<Attachment?>(null) }
    var saving by remember { mutableStateOf(false) }
    val saveLabel = stringResource(R.string.image_save_original)
    fun save(attachment: Attachment, target: android.net.Uri? = null) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (target != null) copyOriginalImage(context, attachment, target)
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveOriginalImageToGallery(context, attachment)
                }
                Toast.makeText(context, R.string.image_original_saved, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, R.string.image_original_save_failed, Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val attachment = pendingExport
        pendingExport = null
        result.data?.data?.let { target -> if (attachment != null) save(attachment, target) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // 自己处理系统栏：整屏看图不该被默认的 insets 再缩一圈
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ViewerBackground)
                .pointerInput(current, onDismiss) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onLongPress = { point ->
                            menuPoint = IntOffset(point.x.roundToInt(), point.y.roundToInt())
                            menuImage = current
                        },
                    )
                }
                .semantics {
                    onClick { onDismiss(); true }
                    onLongClick(label = saveLabel) {
                        menuPoint = null
                        menuImage = current
                        true
                    }
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val attachment = pages[page]
                // 每页各解各的：Pager 只组合当前页与左右邻页，不会把整组图读进内存
                val pageBitmap = rememberAttachmentImage(attachment, target)
                var zoom by remember(page) { mutableStateOf(1f) }
                var pan by remember(page) { mutableStateOf(Offset.Zero) }
                Box(
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (pageBitmap != null) {
                        Image(
                            bitmap = pageBitmap,
                            contentDescription = attachment.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp)
                                // Keep pointer coordinates in the stationary viewport.
                                .pointerInput(page, pageBitmap) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        var transforming = false
                                        do {
                                            val event = awaitPointerEvent()
                                            if (event.changes.any { it.isConsumed }) break
                                            val twoFingers = event.changes.count { it.pressed } >= 2
                                            val movement = event.calculatePan()
                                            if (twoFingers || (zoom > 1f && movement != Offset.Zero)) {
                                                transforming = true
                                            }
                                            if (transforming) {
                                                val nextZoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                                                val ratio = nextZoom / zoom
                                                val center = Offset(size.width / 2f, size.height / 2f)
                                                val focus = event.calculateCentroid(useCurrent = false)
                                                if (focus != Offset.Unspecified) {
                                                    val fit = minOf(
                                                        size.width.toFloat() / pageBitmap.width,
                                                        size.height.toFloat() / pageBitmap.height,
                                                    )
                                                    val maxX = ((pageBitmap.width * fit * nextZoom - size.width) / 2f).coerceAtLeast(0f)
                                                    val maxY = ((pageBitmap.height * fit * nextZoom - size.height) / 2f).coerceAtLeast(0f)
                                                    val nextPan = pan * ratio + (focus - center) * (1f - ratio) + movement
                                                    zoom = nextZoom
                                                    pan = Offset(nextPan.x.coerceIn(-maxX, maxX), nextPan.y.coerceIn(-maxY, maxY))
                                                }
                                                event.changes.forEach { it.consume() }
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                }
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = pan.x
                                    translationY = pan.y
                                },
                        )
                    } else {
                        Icon(
                            imageVector = AppIcons.Image,
                            contentDescription = null,
                            tint = ViewerTextDim,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }

            AppMenu(
                expanded = menuImage != null,
                onDismissRequest = { menuImage = null },
                touchPoint = menuPoint,
            ) {
                AppMenuItem(label = saveLabel, icon = AppIcons.Download) {
                    val attachment = menuImage
                    menuImage = null
                    if (attachment != null && !saving && pendingExport == null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) save(attachment)
                        else {
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = attachment.originalImageMime()
                                putExtra(Intent.EXTRA_TITLE, attachment.originalImageName())
                            }
                            pendingExport = attachment
                            try {
                                export.launch(intent)
                            } catch (_: android.content.ActivityNotFoundException) {
                                pendingExport = null
                                Toast.makeText(context, R.string.image_original_save_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            // ── 顶栏：关闭 + 计数 ──────────────────────────
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = ViewerText,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (pages.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1}/${pages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ViewerText,
                    )
                    Spacer(Modifier.size(12.dp))
                }
                if (onCrop != null) {
                    Box(
                        modifier = Modifier
                            .clip(com.simplechat.app.ui.common.PillShape)
                            .background(ViewerText.copy(alpha = 0.14f))
                            .clickable { onCrop(current) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.crop_attachment),
                            style = MaterialTheme.typography.labelLarge,
                            color = ViewerText,
                        )
                    }
                }
            }

            // ── 底栏：文件名 · 尺寸 · 大小 ─────────────────
            Text(
                text = buildString {
                    append(current.name)
                    if (current.width > 0) append(" · ${current.width}×${current.height}")
                    // 落盘的附件 `data` 是空的，字节数要问文件系统（否则显示成 0 B）
                    append(" · ${Attachments.formatSize(AttachmentFiles.sizeOf(context, current))}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = ViewerTextDim,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 全屏看图的底色：近黑。图片可能是浅底，深底才看得清边界。 */
private val ViewerBackground = Color(0xF2000000)

/** 顶栏 / 底栏文字。 */
private val ViewerText = Color(0xFFEDEDED)

/** 次要文字（尺寸、大小）。 */
private val ViewerTextDim = ViewerText.copy(alpha = 0.62f)
