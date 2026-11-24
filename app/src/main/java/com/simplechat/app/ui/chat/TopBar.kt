package com.simplechat.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.Space

private val TopBarActionSize = 48.dp

/** 顶栏：`☰` 标题 ｜ 自动朗读 · 新建对话。设置入口在抽屉底部。 */
@Composable
fun ChatTopBar(
    title: String,
    onMenu: () -> Unit,
    onNewChat: () -> Unit,
    onDoubleTap: () -> Unit = {},
    modifier: Modifier = Modifier,
    applyStatusBarInset: Boolean = true,
    autoReadAloud: Boolean = false,
    readAloudAvailable: Boolean = true,
    onToggleAutoReadAloud: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .pointerInput(onDoubleTap) {
                var lastTapUp = 0L
                var lastTapPosition: androidx.compose.ui.geometry.Offset? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (change.changedToUpIgnoreConsumed()) {
                            if (!moved) {
                                val now = change.uptimeMillis
                                val previousPosition = lastTapPosition
                                val isSecondTap = lastTapUp > 0L &&
                                    now - lastTapUp <= viewConfiguration.doubleTapTimeoutMillis &&
                                    previousPosition != null &&
                                    (change.position - previousPosition).getDistance() <= viewConfiguration.touchSlop
                                if (isSecondTap) {
                                    lastTapUp = 0L
                                    lastTapPosition = null
                                    onDoubleTap()
                                } else {
                                    lastTapUp = now
                                    lastTapPosition = change.position
                                }
                            }
                            break
                        }
                    }
                }
            }
            .fillMaxWidth()
            .then(if (applyStatusBarInset) Modifier.statusBarsPadding() else Modifier)
            .heightIn(min = TopBarActionSize)
            .padding(horizontal = Space.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TopBarAction(AppIcons.Menu, stringResource(R.string.topbar_history), onMenu)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(start = Space.Gap),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (readAloudAvailable) {
            val label = stringResource(if (autoReadAloud) R.string.auto_read_aloud_on else R.string.auto_read_aloud_off)
            TopBarAction(
                if (autoReadAloud) AppIcons.Volume else AppIcons.VolumeOff,
                label, onToggleAutoReadAloud,
                tint = if (autoReadAloud) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                state = label,
            )
        }
        // 与长按菜单的「从这里新建对话」同一个图标 —— 同一个动作，同一个符号。
        // 圆圈加号留给输入栏的附件按钮（⊕ 加图片/文件），两者别混用。
        TopBarAction(AppIcons.MessageSquarePlus, stringResource(R.string.topbar_new_chat), onNewChat)
    }
}

/** 顶栏图标按钮，48dp 触控目标。 */
@Composable
private fun TopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onBackground,
    state: String? = null,
) {
    Box(
        modifier = Modifier
            .size(TopBarActionSize)
            .clip(CircleShape)
            .semantics { if (state != null) stateDescription = state }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}
