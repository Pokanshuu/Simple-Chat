package com.simplechat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import com.simplechat.app.ui.common.MenuShape
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 编辑 AI 回复。
 *
 * 只有**这里**需要区分两个出口：
 * - 「保存」——该位置新增一个版本，后面照旧
 * - 「保存并发送」——新增版本后，据此继续往下生成
 *
 * 用户消息不弹这个 —— 它走输入栏的「修改输入」，一个发送键就够（官方做法）。
 *
 * 样式对齐官方：吸底卡片、顶部一行小标题 + 圆形 ✕、浅灰填充的多行输入区、
 * 右下角一排胶囊按钮。
 */
@Composable
fun AiEditDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onSaveAndSend: (String) -> Unit,
) {
    val colors = LocalChatColors.current
    var value by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            /*
             * ⚠️ 这一行是关键，不能省。
             *
             * 默认（`true`）时对话框窗口会**自己吃掉**系统栏 / 输入法的内边距，
             * 于是里面的 `imePadding()` 拿到的是 0 —— 键盘弹起来直接把底部的
             * 「保存 / 保存并发送」盖住，用户根本点不到。
             *
             * 置为 false 让窗口铺到边到边，inset 才会往下传。
             */
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 点空白处关闭
                .clickable(onClick = onDismiss)
                .imePadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(colors.card)
                    // 吞掉卡片内的点击，避免落到外面的关闭层上
                    .clickable(enabled = false) {}
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                // ── 标题行：修改回复 · ✕ ───────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.msg_edit_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IconCircleButton(
                        icon = AppIcons.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        onClick = onDismiss,
                        size = 30.dp,
                        iconSize = 16.dp,
                        background = colors.fieldBackground,
                        tint = colors.processText,
                    )
                }

                Spacer(Modifier.size(12.dp))

                // ── 输入区 ────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MenuShape)
                        .background(colors.fieldBackground)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 110.dp, max = 300.dp)
                            .focusRequester(focusRequester),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }

                Spacer(Modifier.size(14.dp))

                // ── 动作 ──────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    SheetAction(
                        text = stringResource(R.string.action_save),
                        enabled = value.isNotBlank(),
                        onClick = { onSave(value) },
                    )
                    Spacer(Modifier.size(8.dp))
                    SheetAction(
                        text = stringResource(R.string.msg_save_and_send),
                        primary = true,
                        enabled = value.isNotBlank(),
                        onClick = { onSaveAndSend(value) },
                    )
                }
            }
        }
    }
}

/**
 * 吸底卡片里的胶囊按钮。
 *
 * `clip` 在 `clickable` **之前** —— 水波纹才会被裁成胶囊；
 * 反过来会得到一个方角的水波纹，和按钮形状对不上。
 */
@Composable
private fun SheetAction(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalChatColors.current
    val container = when {
        !enabled -> colors.fieldBackground
        primary -> MaterialTheme.colorScheme.primary
        else -> colors.card
    }
    val content = when {
        !enabled -> colors.placeholder
        primary -> androidx.compose.ui.graphics.Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .clip(PillShape)
            .background(container)
            .then(
                if (primary) {
                    Modifier
                } else {
                    Modifier.border(1.dp, colors.outline, PillShape)
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = content,
        )
    }
}
