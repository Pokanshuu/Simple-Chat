package com.simplechat.app.ui.chat

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.simplechat.app.R
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.Attachments
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.Dispatchers
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
    val state by produceState<ImageBitmap?>(initialValue = null, attachment.data, targetPx) {
        value = withContext(Dispatchers.IO) { decodeSampled(attachment.data, targetPx) }
    }
    return state
}

private fun decodeSampled(base64: String, targetPx: Int): ImageBitmap? = runCatching {
    val bytes = Base64.decode(base64, Base64.NO_WRAP)

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
                text = stringResource(R.string.attach_char_count, spec.data.length),
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
) {
    /*
     * 三个格子，和官方那套一致：拍照 / 相册 / 文件。
     *
     * 等宽而不是按内容自适应 —— 三个入口的地位是平等的，
     * 谁宽谁窄会让人觉得有一个"更主要"。
     */
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AttachmentTile(
            label = stringResource(R.string.attach_camera),
            icon = AppIcons.Camera,
            onClick = onCapture,
            modifier = Modifier.weight(1f),
        )
        AttachmentTile(
            label = stringResource(R.string.attach_gallery),
            icon = AppIcons.Image,
            onClick = onPickImage,
            modifier = Modifier.weight(1f),
        )
        AttachmentTile(
            label = stringResource(R.string.attach_file),
            icon = AppIcons.FileText,
            onClick = onPickFile,
            modifier = Modifier.weight(1f),
        )
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
            .padding(vertical = 14.dp, horizontal = 10.dp),
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
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ══════════════════════════════════════════════════════════
//  全屏查看
// ══════════════════════════════════════════════════════════

/** 点开看大图。纯查看，不做保存/分享 —— 那是系统相册的事。 */
@Composable
fun AttachmentViewerDialog(
    images: List<Attachment>,
    initial: Attachment,
    onDismiss: () -> Unit,
) {
    val colors = LocalChatColors.current
    val density = LocalDensity.current
    // 全屏尺寸解码：屏幕最长边的像素数，够清楚又不至于把原图解进内存
    val target = with(density) { 1080.dp.roundToPx() }
    val bitmap = rememberAttachmentImage(initial, target)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 520.dp)
                    .clip(SoftShape)
                    .background(colors.fieldBackground),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = initial.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Icon(
                        imageVector = AppIcons.Image,
                        contentDescription = null,
                        tint = colors.placeholder,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            val multiNote = stringResource(R.string.attach_viewer_multi, images.size)
            Text(
                text = buildString {
                    append(initial.name)
                    if (initial.width > 0) append(" · ${initial.width}×${initial.height}")
                    append(" · ${Attachments.formatSize(initial.sizeBytes)}")
                    if (images.size > 1) append(multiNote)
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
                modifier = Modifier.padding(top = 8.dp),
            )

            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clip(PillShape)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(R.string.action_close),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
