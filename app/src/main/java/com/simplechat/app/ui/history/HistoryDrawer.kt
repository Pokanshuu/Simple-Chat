package com.simplechat.app.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuDivider
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.FieldThickness
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.common.Space
import com.simplechat.app.ui.common.rememberPressPosition
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 抽屉内**所有全宽元素**共用的左右内边距。
 *
 * 搜索框、分组标题、会话行高亮都从它取值 —— 之前三处各写各的
 * （16 / 16 / 8dp），高亮块和搜索框左右对不齐，很显眼。
 */
private val DrawerInset = Space.Screen

/** 块内文字的左边界（搜索框占位符、分组标题、会话标题共用，保证一条竖线对齐）。 */
private val DrawerTextInset = Space.Screen + Space.Inline

/**
 * 侧拉会话列表。
 *
 * 结构（对齐 DeepSeek 抽屉）：
 * ```
 * [ 🔍 搜索对话内容… ]        ← 点击进入全屏搜索页
 * 置顶 ⌃
 *   会话 A
 * 今天
 *   会话 B …
 * ─────────────────
 * [ ⚙ 设置 ]                 ← 总设置入口固定在底部
 * ```
 */
@Composable
fun HistoryDrawerContent(
    conversations: List<ConversationEntity>,
    currentConversationId: String?,
    selectionMode: Boolean,
    selectedIds: List<String>,
    onSearchClick: () -> Unit,
    onClick: (ConversationEntity) -> Unit,
    onEnterSelection: (ConversationEntity) -> Unit,
    onToggleSelection: (String) -> Unit,
    onRename: (ConversationEntity) -> Unit,
    onTogglePin: (ConversationEntity) -> Unit,
    onDelete: (ConversationEntity) -> Unit,
    onExitSelection: () -> Unit,
    onPinSelected: (Boolean) -> Unit,
    onDeleteSelected: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.card),
    ) {
        Spacer(Modifier.statusBarsPadding())

        // ── 搜索框：点击进入全屏搜索页 ────────────────────
        SearchBox(onClick = onSearchClick)

        if (selectionMode) {
            SelectionHeader(count = selectedIds.size)
        }

        // ── 列表 ─────────────────────────────────────────
        val grouped = remember(conversations) { groupConversations(conversations) }

        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.hist_empty),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.placeholder,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 2.dp,
                    bottom = 8.dp,
                ),
            ) {
                grouped.forEach { (group, items) ->
                    item(key = "header-${group.name}") {
                        GroupHeader(label = group.label)
                    }
                    items(items = items, key = { it.id }) { conversation ->
                        ConversationRow(
                            conversation = conversation,
                            current = conversation.id == currentConversationId,
                            selectionMode = selectionMode,
                            selected = conversation.id in selectedIds,
                            onClick = {
                                if (selectionMode) {
                                    onToggleSelection(conversation.id)
                                } else {
                                    onClick(conversation)
                                }
                            },
                            onEnterSelection = { onEnterSelection(conversation) },
                            onRename = { onRename(conversation) },
                            onTogglePin = { onTogglePin(conversation) },
                            onDelete = { onDelete(conversation) },
                        )
                    }
                }
            }
        }

        // ── 底部：多选操作栏 / 总设置入口 ──────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider),
        )

        if (selectionMode) {
            SelectionActionBar(
                count = selectedIds.size,
                onPin = { onPinSelected(true) },
                onDelete = onDeleteSelected,
                onCancel = onExitSelection,
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(21.dp),
                )
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 手势条（"小白条"）不遮挡设置入口 —— 抽屉自己铺满整屏，底部必须自己让位
        Spacer(Modifier.navigationBarsPadding())
        Spacer(Modifier.height(2.dp))
    }
}

/** 多选模式下的底部操作栏。 */
@Composable
private fun SelectionActionBar(
    count: Int,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalChatColors.current
    val enabled = count > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarAction(
            label = stringResource(R.string.action_pin),
            icon = AppIcons.Pin,
            enabled = enabled,
            onClick = onPin,
            modifier = Modifier.weight(1f),
        )
        BarAction(
            label = stringResource(R.string.action_delete),
            icon = AppIcons.Trash,
            enabled = enabled,
            tint = colors.danger,
            onClick = onDelete,
            modifier = Modifier.weight(1f),
        )
        BarAction(
            label = stringResource(R.string.action_cancel),
            icon = AppIcons.Close,
            enabled = true,
            onClick = onCancel,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BarAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color? = null,
) {
    val colors = LocalChatColors.current
    val color = when {
        !enabled -> colors.placeholder
        tint != null -> tint
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = modifier
            .clip(PillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

// ══════════════════════════════════════════════════════════
//  子组件
// ══════════════════════════════════════════════════════════

@Composable
private fun SearchBox(onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DrawerInset, vertical = 8.dp)
            .clip(PillShape)
            .background(colors.fieldBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = FieldThickness),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = AppIcons.Search,
            contentDescription = null,
            tint = colors.placeholder,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = stringResource(R.string.search_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.placeholder,
        )
    }
}

@Composable
private fun SelectionHeader(count: Int) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.hist_selected_count, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun GroupHeader(label: String) {
    val colors = LocalChatColors.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = colors.placeholder,
        modifier = Modifier.padding(start = DrawerTextInset, top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRow(
    conversation: ConversationEntity,
    current: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onEnterSelection: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    highlightQuery: String = "",
    /** 搜索命中正文时的一行片段；仅标题命中时为 null。 */
    snippet: String? = null,
    /** 搜索页不支持多选，隐藏该项以免点了没反应。 */
    showMultiSelect: Boolean = true,
) {
    val colors = LocalChatColors.current
    // 高亮跟着主题色（Material 的 primary 就是设置页里选的品牌色）
    val highlightColor = MaterialTheme.colorScheme.primary
    var menuOpen by remember { mutableStateOf(false) }

    // 长按落点：菜单跟手从这里弹出，而不是贴到行边缘
    val (pressPosition, pressListener) = rememberPressPosition()

    // 内边距放在外层 Box 上：菜单的锚点因此也内缩 DrawerInset，
    // 弹出的左边界与搜索框、分组标题对齐，而不是贴到屏幕边缘
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(pressListener)
            .padding(horizontal = DrawerInset),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SoftShape)
                .background(
                    when {
                        selected || current -> colors.fieldBackground
                        else -> colors.card
                    },
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        // 多选态下长按＝切换勾选；否则弹出上下文菜单
                        if (selectionMode) onClick() else menuOpen = true
                    },
                )
                .padding(horizontal = 12.dp, vertical = 2.dp)
                // 单行时保持 46dp 的节奏；带片段时（搜索页）让它长高
                .heightIn(min = 46.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) AppIcons.CircleCheck else AppIcons.Circle,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else colors.placeholder,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = highlightedTitle(conversation.title, highlightQuery, highlightColor),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (snippet != null) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = highlightedTitle(snippet, highlightQuery, highlightColor),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.placeholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 长按菜单：与对话页**同一个** AppMenu（同宽、同行高、同圆角、同内边距）
        AppMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            touchPoint = pressPosition(),
        ) {
            AppMenuItem(stringResource(R.string.action_rename), AppIcons.Edit) {
                menuOpen = false
                onRename()
            }
            AppMenuItem(
                label = if (conversation.pinned) {
                    stringResource(R.string.action_unpin)
                } else {
                    stringResource(R.string.action_pin)
                },
                icon = if (conversation.pinned) AppIcons.PinOff else AppIcons.Pin,
            ) {
                menuOpen = false
                onTogglePin()
            }
            if (showMultiSelect) {
                AppMenuItem(stringResource(R.string.hist_multi_select), AppIcons.ListChecks) {
                    menuOpen = false
                    onEnterSelection()
                }
            }
            // 分隔线固定接在危险操作之前 —— 结构不随数据变化
            AppMenuDivider()
            AppMenuItem(stringResource(R.string.action_delete), AppIcons.Trash, destructive = true) {
                menuOpen = false
                onDelete()
            }
        }
    }
}

/** 搜索时高亮匹配片段。高亮色由调用方给 —— **跟着主题色走**，写死品牌蓝会在换主题色后脱节。 */
private fun highlightedTitle(
    title: String,
    query: String,
    highlightColor: Color,
) = buildAnnotatedString {
    if (query.isBlank()) {
        append(title)
        return@buildAnnotatedString
    }
    val start = title.indexOf(query, ignoreCase = true)
    if (start < 0) {
        append(title)
        return@buildAnnotatedString
    }
    val end = start + query.length
    append(title.substring(0, start))
    withStyle(SpanStyle(color = highlightColor)) {
        append(title.substring(start, end))
    }
    append(title.substring(end))
}

/** 按时间分组。置顶恒在最前。 */
fun groupConversations(    conversations: List<ConversationEntity>,
    now: Long = System.currentTimeMillis(),
): List<Pair<HistoryGroup, List<ConversationEntity>>> =
    HistoryGroup.entries
        .map { group -> group to conversations.filter { groupOf(it, now) == group } }
        .filter { it.second.isNotEmpty() }
