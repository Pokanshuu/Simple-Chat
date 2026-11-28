package com.simplechat.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.simplechat.app.R
import com.simplechat.app.net.UpdateChecker
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 「发现新版本」对话框。
 *
 * 把**下载源**放在用户手里而不是"自动选一个"：GitHub 直连在部分网络下能拿到
 * JSON 却下不动 APK，而这类公共加速站时好时坏 —— 用户比我们更清楚自己该走哪条。
 * 默认选中直连，失败后可以换个源再点一次（已下载的会校验复用，不会重来）。
 */
@Composable
internal fun UpdateAvailableDialog(
    release: UpdateChecker.ReleaseInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
    onDownload: (UpdateChecker.Mirror) -> Unit,
    /** 发布页：没有 APK 资产时的兜底出口。 */
    onOpenPage: () -> Unit = {},
) {
    val colors = LocalChatColors.current
    var mirror by remember { mutableStateOf(UpdateChecker.Mirror.GITHUB) }
    val asset = release.asset
    val sizeText = remember(asset) {
        if (asset == null || asset.size <= 0) "" else formatSize(asset.size)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .clip(ContainerShape)
                .background(colors.card)
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.update_new_title,
                    release.tag.removePrefix("v").removePrefix("V"),
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = buildString {
                    append(currentVersion)
                    append("  →  ")
                    append(release.tag.removePrefix("v").removePrefix("V"))
                    if (sizeText.isNotEmpty()) {
                        append("   ")
                        append(sizeText)
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.placeholder,
            )

            if (release.notes.isNotBlank()) {
                Spacer(Modifier.size(14.dp))
                Text(
                    text = stringResource(R.string.update_notes),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.placeholder,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = plainNotes(release.notes),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.processText,
                )
            }

            Spacer(Modifier.size(16.dp))
            Text(
                text = stringResource(R.string.update_source),
                style = MaterialTheme.typography.labelMedium,
                color = colors.placeholder,
            )
            Spacer(Modifier.size(6.dp))
            UpdateMirror.values().forEach { option ->
                val selected = option.mirror == mirror
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PillShape)
                        .clickable { mirror = option.mirror }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(option.labelRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary else colors.processText,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (asset == null) {
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.update_no_asset),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.danger,
                )
            } else if (asset.sha256 == null) {
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.update_no_checksum),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                )
            }

            Spacer(Modifier.size(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextAction(stringResource(R.string.action_cancel), onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                if (asset != null) {
                    TextAction(
                        text = stringResource(R.string.update_download_install),
                        primary = true,
                        onClick = { onDownload(mirror) },
                    )
                } else {
                    TextAction(
                        text = stringResource(R.string.update_open),
                        primary = true,
                        onClick = onOpenPage,
                    )
                }
            }
        }
    }
}

/** 「正在下载更新」：进度 + 取消。[progress] 为负表示总量未知（不确定进度条）。 */
@Composable
internal fun UpdateDownloadingDialog(
    progress: Float,
    onCancel: () -> Unit,
) {
    val colors = LocalChatColors.current
    val indeterminate = progress < 0f
    Dialog(onDismissRequest = {}) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .clip(ContainerShape)
                .background(colors.card)
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.update_downloading),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(14.dp))
            if (indeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                // 总量未知时不要写死 0% —— 看起来像卡死。
                text = if (indeterminate) "…" else "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = colors.placeholder,
            )
            Spacer(Modifier.size(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextAction(stringResource(R.string.action_cancel), onClick = onCancel)
            }
        }
    }
}

/** 下载源选项（枚举 + 文案资源），UI 用。 */
internal enum class UpdateMirror(val mirror: UpdateChecker.Mirror, val labelRes: Int) {
    GITHUB(UpdateChecker.Mirror.GITHUB, R.string.update_source_github),
    GH_PROXY(UpdateChecker.Mirror.GH_PROXY, R.string.update_source_gh_proxy),
    GHFAST(UpdateChecker.Mirror.GHFAST, R.string.update_source_ghfast),
}

/**
 * 把 release notes 的 Markdown 压成能直接读的纯文本。
 *
 * 不做完整渲染（那要引一个 Markdown 库，为一个"看一看改了什么"的对话框不值得）：
 * 只去三类噪声 —— 链接语法、标题井号、加粗星号。保留换行和列表符号。
 */
internal fun plainNotes(raw: String): String = raw
    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("(?m)^#{1,6}\\s*"), "")
    .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
    .trim()

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
