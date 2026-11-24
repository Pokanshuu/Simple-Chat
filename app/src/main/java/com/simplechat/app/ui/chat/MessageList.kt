package com.simplechat.app.ui.chat

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import com.simplechat.app.ui.common.rememberPressWindowPosition
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import com.simplechat.app.ui.markdown.LocalSearchViewport
import com.simplechat.app.ui.markdown.SearchViewport
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.ui.common.ReadAloudEnabled
import com.simplechat.app.ui.common.FoldAnimMs
import com.simplechat.app.ui.common.BubbleShapeOut
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuDivider
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.CopyIconButton
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.common.rememberPressPosition
import com.simplechat.app.ui.markdown.MarkdownText
import com.simplechat.app.ui.markdown.LocalCitationClick
import com.simplechat.app.ui.markdown.MarkdownWithMath
import com.simplechat.app.ui.markdown.SearchHighlightHost
import com.simplechat.app.ui.markdown.LocalSearchHighlight
import com.simplechat.app.ui.markdown.rememberSearchTextDecoration
import com.simplechat.app.ui.theme.ChatColors
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.userBubbleBackground
import com.simplechat.app.ui.theme.floatingPanelSurface
import com.simplechat.app.data.InterfaceControl
import com.simplechat.app.ui.theme.glassContrastForeground
import com.simplechat.app.ui.theme.LocalAssistantFontFamily
import com.simplechat.app.ui.theme.LocalUserFontFamily
import com.simplechat.app.ui.theme.LocalTypographySettings
import com.simplechat.app.ui.theme.LocalTypographyFonts
import com.simplechat.app.ui.theme.LocalTypographyTextColor
import com.simplechat.app.ui.theme.TypographyCategory
import com.simplechat.app.data.FontSlot
import com.simplechat.app.ui.common.PillShape
import androidx.compose.animation.animateContentSize
import com.simplechat.app.ui.common.MotionEasing
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.items

/**
 * 消息列表。
 *
 * 性能约定（§8）：
 * - 稳定 `key`（消息 id）
 * - 稳定 `contentType`（按角色区分），避免跨类型复用导致的重组
 *
 * 每条消息下方都有**同一套操作栏**（用户消息也有）：复制 / 重新生成 /
 * 编辑 / 删除，有多个版本时右侧补 `‹ n/m ›`。
 * 长按消息弹菜单 —— 与操作栏功能一致，但少一次精确点击（拇指友好）。
 */
@Composable
fun MessageList(
    messages: List<UiMessage>,
    modifier: Modifier = Modifier,
    rows: List<MessageListEntry> = messageListEntries(messages, false),
    listState: LazyListState = rememberLazyListState(),
    /**
     * 列表内边距。
     *
     * 底部要留给**浮在列表之上的输入栏** —— 输入栏不再占位、而是盖在消息区上面，
     * 不给这块留白的话最后一条消息会永远被压在输入栏底下，滚不出来。
     */
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    onCopy: (UiMessage) -> Unit = {},
    onRegenerate: (UiMessage) -> Unit = {},
    /** 继续生成：接着这条回复往下写（只有扩展通道能续，见 `ContinuePolicy`）。 */
    onContinue: (UiMessage) -> Unit = {},
    supportsContinuation: Boolean = true,
    /** 朗读这条回复（系统 / DeepSeek 官方 TTS）。 */
    onSpeak: (UiMessage) -> Unit = {},
    /**
     * 当前音色**能不能出声**。
     *
     * 官方音色只在「服务商 = DeepSeek 官方 + Key 已填」时可用；不可用时
     * 整颗朗读按钮不显示 —— 点下去再弹一句"不支持"是最差的两种体验的组合。
     */
    readAloudAvailable: Boolean = true,
    /** 正在朗读的那条消息 id。 */
    speakingId: String? = null,
    onEdit: (UiMessage) -> Unit = {},
    onFork: (UiMessage) -> Unit = {},
    onDelete: ((UiMessage) -> Unit)? = null,
    onVariantChange: (UiMessage, Int) -> Unit = { _, _ -> },
    onOpenImage: (UiMessage, Attachment) -> Unit = { _, _ -> },
    onRetry: (UiMessage) -> Unit = {},
    onStopImage: () -> Unit = {},
    onChooseModel: (() -> Unit)? = null,
    onRetryWithoutAttachmentsAndTools: ((UiMessage) -> Unit)? = null,
    /**
     * 用户展开 / 收起某个折叠块（思考面板、超长用户气泡）时调用。
     *
     * 只当**信号**用，不带数值 —— 具体怎么处理由调用方定（`ChatScreen` 用它
     * 停止自动跟底，见那里的说明）。早先这里是 `onFoldResize(Δpx)`，
     * 在"底部为原点"的时代给视口做开环补偿；改回顶部为原点后锚定天然正确，
     * 那份补偿已无用（调用方传的是 `{}`），只剩下这套 Δ 记账在空转。
     */
    onFoldToggle: () -> Unit = {},
    /**
     * 思考内容是否在正文开始时自动收起（全局显示设置）。
     *
     * 只决定**初态**：开着 → 收成一行摘要；关掉 → 保持展开。
     * 它并进 [AssistantBlock] 里 `remember` 的 key，所以设置一改，
     * 连历史消息的初态也跟着变（用户在设置里刚改完，回头看旧会话该是新行为）。
     */
    autoCollapseThinking: Boolean = true,
    searchTarget: SearchTarget? = null,
    onDismissSearch: () -> Unit = {},
    searchBottomObstructionPx: () -> Int = { 0 },
    searchPlacementReset: Long = 0,
    textSelection: MessageTextSelection = remember { MessageTextSelection() },
    shareSelection: MessageShareSelection? = null,
    onShare: ((UiMessage) -> Unit)? = null,
    onDeleteSelectionStart: ((UiMessage) -> Unit)? = null,
    onShareExcerpt: ((UiMessage, String) -> Unit)? = null,
    loadingLabelFor: (String) -> String? = { null },
    loadingWordsUseThemeColor: Boolean = true,
) {
    /*
     * 「谁被碰过」。
     *
     * 碰一下 AI 回复，它的**模型名与 token 数**就出现在操作栏旁边；
     * 手指一动（开始滚动）或碰到别的消息就换人。
     *
     * 做成**瞬态**是刻意的：常驻会在每条回复下面都挂一行没人读的小字，
     * 而这两条信息属于"偶尔想看一眼"。
     *
     * 只给 AI 回复 —— 用户消息那行没有操作栏（本来就刻意不放图标），
     * 而且它的点击已经是「编辑」，没有余量再挂一个手势。
     */
    var peekedId by remember { mutableStateOf<String?>(null) }
    val shareMessages = if (onShare != null || shareSelection?.active == true) messages.toList() else emptyList()
    val shareGroups = remember(shareMessages) {
        messageShareGroups(shareMessages)
    }
    val shareGroupByMessage = remember(shareGroups) {
        shareGroups.flatMap { group -> group.messages.map { it.id to group } }.toMap()
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                // 只有"碰一下看元信息"是即时的：手指一动就收起。
                if (scrolling) peekedId = null
            }
    }

    // Deletion / branch switches must not leave a selection mode with no message.
    val currentMessages by rememberUpdatedState(messages)
    LaunchedEffect(textSelection) {
        snapshotFlow { textSelection.messageId?.takeUnless { id -> currentMessages.any { it.id == id } } }
            .collect { missingId -> if (missingId != null) textSelection.dismiss(missingId) }
    }

    var viewport by remember { mutableStateOf(Rect.Zero) }
    val visibleBounds = if (searchTarget != null) {
        Rect(viewport.left, viewport.top, viewport.right,
            (viewport.bottom - searchBottomObstructionPx()).coerceAtLeast(viewport.top))
    } else viewport
    val searchViewport = remember(visibleBounds, listState) {
        SearchViewport(visibleBounds) { listState.scrollBy(it) }
    }
    // Allow first/last hits to reach the upper center. Keep room after fading until a new message/branch arrives.
    var searchSpacePx by remember(messages.lastOrNull()?.id, searchPlacementReset) { mutableStateOf(0f) }
    androidx.compose.runtime.SideEffect {
        if (searchTarget != null) searchSpacePx = visibleBounds.height
    }
    val searchSpace = with(LocalDensity.current) { searchSpacePx.toDp() }
    val searchContentPadding = remember(contentPadding, searchSpace) {
        object : PaddingValues by contentPadding {
            override fun calculateTopPadding() = contentPadding.calculateTopPadding() + searchSpace * 0.38f
            override fun calculateBottomPadding() = contentPadding.calculateBottomPadding() + searchSpace * 0.62f
        }
    }
    HistoryPrewarmEffect(listState, messages) { rows.getOrNull(it)?.messageIndex ?: 0 }
    CompositionLocalProvider(LocalSearchViewport provides searchViewport) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { viewport = it.boundsInWindow() }
            .dismissMessageSelectionOnTap(textSelection),
        contentPadding = searchContentPadding,

        /*
         * ⚠️ **顶部为原点（对齐官方）。这一行是流式不滑动的根。**
         *
         * 锚点钉在条目的**顶边**，而流式消息永远是**最后一条** ——
         * 它长高是长在自己的**底边**，也就是长在视口**下方**：
         * 屏上已显示的内容**纹丝不动**，不需要任何补偿代码。
         *
         * 反过来用 `reverseLayout = true`（锚点钉**底边**）：同一条消息在底边
         * 继续长 = 把已显示内容整体往上顶。实测 0.5 秒滑走 861px 且手拦不住，
         * 这正是之前四轮都想"补偿掉"的那个现象 —— 它不该补，该换方向。
         *
         * 落点"点进对话就是底部"由 `LazyListState(initialFirstVisibleItemIndex =
         * Int.MAX_VALUE)` 解决（下标给到末尾之外，LazyList 自动夹到最后一条）。
         * 那是**布局参数**而不是一次滚动，所以首帧就在底部。
         */
        reverseLayout = false,
    ) {
        items(
            /*
             * 正序喂进去：最旧一条在下标 0（= 视觉顶部），最新的在最后。
             * 与列表方向一致，不再需要 `asReversed()`。
             */
            items = rows,
            key = { it.key },
            contentType = { if (it.footer) "actions" else it.message.role },
        ) { entry ->
            val message = entry.message
            val topGap = if (entry.footer) MessageGap else if (entry.messageIndex > 0) 18.dp else 0.dp
            Column(Modifier.padding(top = topGap)) {
            if (entry.footer || message.deletedNavigation) {
                if (!message.deletedNavigation && message.role == MessageRole.ASSISTANT) {
                    MessageActions(
                        message, peekedId == message.id,
                        supportsContinuation && canContinueReply(message, entry.lastOnPath),
                        speakingId == message.id, readAloudAvailable,
                        { onCopy(message) }, { onRegenerate(message) }, { onContinue(message) },
                        { onSpeak(message) }, { onEdit(message) }, onDelete?.let { delete -> { delete(message) } },
                        onVariantChange = { onVariantChange(message, it) },
                    )
                } else if (message.variantCount > 1 && shareSelection?.active != true) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (message.role == MessageRole.USER) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (message.deletedNavigation) {
                            Text(stringResource(R.string.msg_deleted_navigation), color = LocalChatColors.current.processText)
                        }
                        VariantSwitcher(message.variantIndex, message.variantCount, { onVariantChange(message, it) })
                    }
                }
            } else {
            val sharedGroup = shareGroupByMessage[message.id]
            val row: @Composable () -> Unit = {
            CompositionLocalProvider(LocalShareExcerpt provides onShareExcerpt?.let { share -> { text -> share(message, text) } }) {
            MessageRow(
                message = message,
                loadingLabel = if (message.streaming) loadingLabelFor(message.id) else null,
                loadingWordsUseThemeColor = message.streaming && loadingWordsUseThemeColor,
                search = searchTarget?.takeIf { it.messageId == message.id },
                onDismissSearch = onDismissSearch,
                peeked = peekedId == message.id,
                selecting = textSelection.messageId == message.id,
                selectionActive = textSelection.isActive || shareSelection?.active == true,
                sharing = shareSelection?.active == true,
                showActions = !entry.separateActions,
                onShare = if (onShare != null && sharedGroup?.available == true) ({ onShare(message) }) else null,
                onDeleteSelectionStart = if (onDeleteSelectionStart != null && sharedGroup?.available == true) {
                    { onDeleteSelectionStart(message) }
                } else null,
                onSelectingChange = { state ->
                    if (state != null) textSelection.begin(message.id, state)
                    else textSelection.dismiss(message.id)
                },
                /*
                 * 能不能续写由**位置 + 状态**共同决定（见 `ContinuePolicy`）。
                 * 「最后一条」在这里判最准：列表本身就是活跃路径。
                 */
                canContinue = supportsContinuation && canContinueReply(message, isLastOnPath = entry.lastOnPath),
                speakingId = speakingId,
                readAloudAvailable = readAloudAvailable,
                autoCollapseThinking = autoCollapseThinking,
                /*
                 * 折叠/展开**不需要任何视口补偿**（顶部为原点）。
                 *
                 * 锚点是"最上面那条可见项的顶边"，LazyList 按 (下标, 偏移) 保持不动：
                 * 展开的东西在锚点**之上** → 视口完全不动；在锚点**之下** → 往下长，
                 * 那正是展开该有的样子。
                 *
                 * 唯一会破坏它的是**自动跟底**：展开让内容变高，`canScrollForward`
                 * 立刻变真，跟底那一帧就把整屏往上推 —— 被点的那个按钮于是跑掉。
                 * 所以这里只需要通知外层"用户在看东西了，别再跟"，见 [onFoldToggle]。
                 */
                onFoldToggle = onFoldToggle,
                onTogglePeek = {
                    peekedId = if (peekedId == message.id) null else message.id
                },
                onCopy = { onCopy(message) },
                onRegenerate = { onRegenerate(message) },
                onContinue = { onContinue(message) },
                onSpeak = { onSpeak(message) },
                onEdit = { onEdit(message) },
                onFork = { onFork(message) },
                onDelete = onDelete?.let { delete -> { delete(message) } },
                onOpenImage = { onOpenImage(message, it) },
                onRetry = { onRetry(message) },
                onStopImage = onStopImage,
                onChooseModel = onChooseModel,
                onRetryWithoutAttachmentsAndTools = onRetryWithoutAttachmentsAndTools?.let { retry -> { retry(message) } },
            )
            }
            }
            if (shareSelection?.active == true && sharedGroup != null) {
                ShareSelectableRow(sharedGroup, sharedGroup.id in shareSelection.selectedIds,
                    onToggle = { shareSelection.toggle(sharedGroup) }, content = row)
            } else row()
            }
            }
        }
    }
    }
}

/**
 * 单条消息 = 内容 + 操作栏。
 *
 * 操作栏对用户消息**右对齐**、对 AI 消息左对齐，跟随内容方向，
 * 免得用户消息下面挂一排左边距对齐的图标，视觉上"跑偏"。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: UiMessage,
    loadingLabel: String?,
    loadingWordsUseThemeColor: Boolean,
    search: SearchTarget?,
    onDismissSearch: () -> Unit,
    peeked: Boolean,
    /** 这条是否处于「选择文本」态（由列表统一持有，见 `MessageList`）。 */
    selecting: Boolean,
    selectionActive: Boolean,
    sharing: Boolean,
    showActions: Boolean,
    onShare: (() -> Unit)?,
    onDeleteSelectionStart: (() -> Unit)?,
    onSelectingChange: (SelectionState?) -> Unit,
    canContinue: Boolean,
    speakingId: String?,
    readAloudAvailable: Boolean,
    autoCollapseThinking: Boolean,
    onTogglePeek: () -> Unit,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onSpeak: () -> Unit,
    onEdit: () -> Unit,
    onFork: () -> Unit,
    onDelete: (() -> Unit)?,
    onOpenImage: (Attachment) -> Unit,
    onRetry: () -> Unit,
    onStopImage: () -> Unit,
    onChooseModel: (() -> Unit)?,
    onRetryWithoutAttachmentsAndTools: (() -> Unit)?,
    onFoldToggle: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    /*
     * 「选择文本」= 让这一条的正文变成可选，**不留任何界面痕迹**：
     * 没有提示条、没有「完成」按钮，点空白处就退出（见
     * `dismissMessageSelectionOnTap`）。为什么不能常驻：`SelectionContainer` 自己也要用
     * **长按**起选，而长按是这一条的菜单入口，两者会抢同一个手势。
     */
    val selectionState = remember(message.id) { SelectionState() }
    val selectionHits = remember(message.id) { com.simplechat.app.ui.markdown.SelectionTextHits() }
    var selectionFromMenu by remember(message.id) { mutableStateOf(false) }
    val view = LocalView.current
    val viewConfiguration = LocalViewConfiguration.current
    val onSelectionChange by rememberUpdatedState(onSelectingChange)
    // Keep just the selected row composed, including its page, registrar and Text nodes.
    // A state object alone cannot preserve anchors after a lazy item is disposed.
    val pinnableContainer = LocalPinnableContainer.current
    DisposableEffect(selecting, pinnableContainer) {
        val pinned = if (selecting) pinnableContainer?.pin() else null
        onDispose { pinned?.release() }
    }
    // 长按落点（窗口坐标）：进入选择态后要在这同一个点上合成一次长按，
    // 让选区**从用户按下去的地方**开始，而不是"先进入模式，再自己长按一次"。
    val (windowPressPosition, windowPressListener) = rememberPressWindowPosition()

    LaunchedEffect(selecting) {
        if (!selecting) {
            selectionState.clear()
            return@LaunchedEffect
        }
        val point = windowPressPosition()
        if (point == null) {
            onSelectionChange(null)
            return@LaunchedEffect
        }
        // 等这一帧把 SelectionContainer 真正挂上（菜单的关闭动画也在同一帧里），再注入
        withFrameNanos { }
        if (selectionFromMenu) delay(SelectionMenuDismissDelayMs)
        if (!selectionHits.selectAt(point, selectionState)) {
            startSelectionAt(view, point, viewConfiguration.longPressTimeoutMillis + 60L)
        }
        if (selectionState.selectedTexts.isEmpty()) onSelectionChange(null)
    }
    LaunchedEffect(selecting, selectionState) {
        if (!selecting) return@LaunchedEffect
        var hadSelection = false
        snapshotFlow { selectionState.selectedTexts.isNotEmpty() }.collect { hasSelection ->
            if (hasSelection) hadSelection = true
            // Native Copy / focus loss also ends the mode. Never leave a hidden gate behind.
            else if (hadSelection) onSelectionChange(null)
        }
    }
    val isUser = message.role == MessageRole.USER
    // 流式未结束时不对这条给出编辑/删除入口，避免和落库打架
    val actionable = !message.streaming

    // 长按落点：菜单跟手从这里弹出，而不是从内容左边缘
    val (pressPosition, pressListener) = rememberPressPosition()

    // 菜单作为**插槽**注入内容内部：Popup 以父节点为锚，
    // 落点坐标因此与锚点同坐标系，直接相加即可。
    val menu: @Composable () -> Unit = {
        AppMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            touchPoint = pressPosition(),
        ) {
            AppMenuItem(stringResource(R.string.action_copy), AppIcons.Copy) {
                menuOpen = false
                onCopy()
            }
            AppMenuItem(stringResource(R.string.action_select_text), AppIcons.TextSelect) {
                menuOpen = false
                selectionFromMenu = true
                onSelectingChange(selectionState)
            }
            if (onShare != null) AppMenuItem(stringResource(R.string.action_share), AppIcons.Share) {
                menuOpen = false
                onShare()
            }
            if (!isUser) {
                AppMenuItem(stringResource(R.string.msg_regenerate), AppIcons.Refresh) {
                    menuOpen = false
                    onRegenerate()
                }
                // 续写：只在真能续的时候出现（见 ContinuePolicy 的五条否决）
                if (canContinue) {
                    AppMenuItem(stringResource(R.string.msg_continue), AppIcons.Play) {
                        menuOpen = false
                        onContinue()
                    }
                }
            }
            AppMenuItem(stringResource(R.string.action_edit), AppIcons.Edit) {
                menuOpen = false
                onEdit()
            }
            // 建设性操作，放在危险操作分隔线之前
            AppMenuItem(stringResource(R.string.msg_fork_from_here), AppIcons.MessageSquarePlus) {
                menuOpen = false
                onFork()
            }
            if (onDelete != null || onDeleteSelectionStart != null) AppMenuDivider()
            if (onDelete != null) {
                AppMenuItem(stringResource(R.string.action_delete), AppIcons.Trash, destructive = true) {
                    menuOpen = false
                    onDelete()
                }
            }
            if (onDeleteSelectionStart != null) {
                AppMenuItem(stringResource(R.string.share_select_delete), AppIcons.ListChecks) {
                    menuOpen = false
                    onDeleteSelectionStart()
                }
            }
        }
    }

    // Coordinate tracking is needed only after this row enters selection, not during normal scrolling.
    CompositionLocalProvider(com.simplechat.app.ui.markdown.LocalSelectionTextHits provides selectionHits.takeIf { selecting }) {
    SearchHighlightHost(search, onDismissSearch) {
    Column(modifier = Modifier.fillMaxWidth().then(windowPressListener)) {
        when (message.role) {
            MessageRole.USER -> {
                val userContent: @Composable () -> Unit = {
                    UserBubble(
                        message = message,
                        interactive = actionable && !selectionActive,
                        selecting = selecting,
                        selectionState = selectionState,
                        // 点一下就进「修改输入」（对齐官方入口习惯）
                        onClick = onEdit,
                        onLongClick = { menuOpen = true },
                        onOpenImage = onOpenImage,
                        menu = menu,
                        pressListener = pressListener,
                        onFoldToggle = onFoldToggle,
                    )
                }
                if (LocalTypographySettings.current == null) userContent()
                else TypographyCategory(FontSlot.USER, userContent)
            }

            MessageRole.ASSISTANT -> {
                val assistantContent: @Composable () -> Unit = { AssistantBlock(
                message = message,
                loadingLabel = loadingLabel,
                loadingWordsUseThemeColor = loadingWordsUseThemeColor,
                interactive = actionable && !selectionActive,
                selecting = selecting,
                selectionState = selectionState,
                autoCollapseThinking = autoCollapseThinking,
                reasoningSearchRequest = search?.takeIf { it.reasoningMatch }?.request,
                // 点一下 = 看这条是谁写的、占多少 token（再点收起）
                onClick = onTogglePeek,
                onLongClick = { menuOpen = true },
                onDoubleClick = { selectionFromMenu = false; onSelectingChange(selectionState) },
                onOpenImage = onOpenImage,
                onRetry = onRetry,
                onStopImage = onStopImage,
                onChooseModel = onChooseModel,
                onRetryWithoutAttachmentsAndTools = onRetryWithoutAttachmentsAndTools,
                menu = menu,
                pressListener = pressListener,
                // 与用户气泡同一条补偿：展开思考内容时"上方固定、向下展开"
                onFoldToggle = onFoldToggle,
                ) }
                if (LocalTypographySettings.current == null) assistantContent()
                else TypographyCategory(FontSlot.ASSISTANT, assistantContent)
            }
        }

        if (showActions && actionable && !sharing && !isUser) {
            Spacer(Modifier.size(MessageGap))
            MessageActions(
                message = message,
                peeked = peeked,
                canContinue = canContinue,
                speaking = speakingId == message.id,
                readAloudAvailable = readAloudAvailable,
                onCopy = onCopy,
                onRegenerate = onRegenerate,
                onContinue = onContinue,
                onSpeak = onSpeak,
                onEdit = onEdit,
                onDelete = onDelete,
            )
        }
    }
    }
    }
}

/**
 * 用户消息：右对齐浅蓝气泡，黑字。最大宽度约屏宽 82%。
 *
 * 交互（点击进编辑 / 长按出菜单）挂在**气泡自己**身上，且 `clip` 在
 * `combinedClickable` **之前** —— 水波纹才会被裁成气泡的形状。
 * 之前把点击挂在整行 `fillMaxWidth()` 的 Box 上，水波纹横贯全屏，
 * 和气泡形状完全对不上。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserBubble(
    message: UiMessage,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    /** 「选择文本」态：正文外包一层 `SelectionContainer`（见 `MessageRow`）。 */
    selecting: Boolean = false,
    selectionState: SelectionState = remember { SelectionState() },
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onOpenImage: (Attachment) -> Unit = {},
    menu: @Composable () -> Unit = {},
    /** 旁听落点用（不消费事件），菜单据此跟手弹出。 */
    pressListener: Modifier = Modifier,
    /** 用户展开 / 收起这条气泡时调用 —— 原样转达给外层（见 `MessageList` 的 `onFoldToggle`）。 */
    onFoldToggle: () -> Unit = {},
) {
    val palette = LocalChatColors.current
    val surface = floatingPanelSurface(InterfaceControl.USER_BUBBLE)
    val foreground = glassContrastForeground(surface, palette.pageBackground, MaterialTheme.colorScheme.onSurface)
    val colors = palette.copy(onUserBubble = foreground)
    /*
     * 用户发言层的自定义字体（设置 → 外观 → 字体 → 我的发言）。
     * 未选择时直接用全局排版本身，不 copy —— 保持原来的常量语义。
     */
    val userFont = LocalTypographyFonts.current.user ?: LocalUserFontFamily.current
    val baseBodyStyle = MaterialTheme.typography.bodyLarge
    val categoryTextColor = LocalTypographyTextColor.current
    val userTextStyle = if (userFont == null) baseBodyStyle else baseBodyStyle.copy(fontFamily = userFont)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.82f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            /*
             * ⚠️ 这里**不能用 `clip`** —— 见设计文档 §31。
             *
             * `clip` 会给气泡挂一层**带裁剪的图形层**。超长消息的气泡能有几十万像素高，
             * 一旦超过平台的图形层上限，这层就废了：内容照画，
             * **命中测试却只认到某个高度为止** —— 表现正是"气泡上半能点、下半点不动"，
             * 长按同样没反应。
             *
             * 实测（1 万行刻度消息，13.6 万 px 深处）：
             * `clip` 在 → 点不动；去掉 `clip` → 立刻点得动；只去水波纹 → 仍点不动。
             * 也就是说：**凶手是裁剪层，水波纹是清白的。**
             *
             * 形状改由 `background(color, shape)` 画 —— 画一个圆角矩形，**不产生图形层**，
             * 视觉与 `clip` 完全一致。按压反馈同理：水波纹要靠 `clip` 才能裁成圆角，
             * 于是换成**按下时铺一层同形状的浅色** —— 形状天然正确，也不需要层。
             */
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val fadePx = with(LocalDensity.current) { UserBubbleFadeRadius.toPx() }
            /*
             * 折叠状态要在**气泡这一层**就用到（底部那条渐隐画在气泡上），
             * 所以提到最外面声明 —— 放进正文块里的话这里够不着。
             */
            var expanded by remember(message.id) { mutableStateOf(false) }
            var folded by remember(message.id) { mutableStateOf(false) }
            val search = LocalSearchHighlight.current
            LaunchedEffect(search?.request) {
                if (search != null && message.content.contains(search.query, ignoreCase = true)) expanded = true
            }

            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .userBubbleBackground(BubbleShapeOut, surface)
                    .then(pressListener)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = interactive,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                // 按下时的浅色层：垫在正文**下面**，正文颜色不受影响
                if (pressed) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(colors.onUserBubble.copy(alpha = 0.07f), BubbleShapeOut),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    /*
                     * 附件排在文字**上面**：图片是"我给它看的东西"，
                     * 文字是"我要说的话"，从上往下读正好是这个顺序。
                     *
                     * 图片自己带点击（放大查看），会吃掉这一下 ——
                     * 不会顺手把整条消息带进编辑态。
                     */
                    CompositionLocalProvider(LocalChatColors provides colors) {
                        MessageAttachments(
                            attachments = message.attachments,
                            onOpenImage = onOpenImage,
                            inUserBubble = true,
                        )
                    }
                    if (message.content.length > LARGE_MESSAGE_CHARS) {
                        LargeMessageBody(message.content, userFont)
                    } else if (message.content.isNotBlank()) {
                        /*
                         * 超长正文的**两态渲染**（根因与实测见设计文档 §31）：
                         *
                         * - **收起态**：`maxLines` 卡住高度 —— 高度有界，命中测试就不会丢；
                         * - **展开态**：切成多块 `Text` 渲染（`textChunks`），
                         *   和 AI 回复走同一套路子，几十万像素也整条点得动。
                         *
                         * ⚠️ 顺带记住那条判据：**别给高度无界的节点挂 `clip` / `graphicsLayer`** ——
                         * 超长气泡上那层图形层会失效，命中测试只认到某个高度为止。
                         */
                        val chunks = remember(message.content) { textChunks(message.content) }

                        Box {
                            Column(
                                modifier = Modifier
                                    /*
                                     * 展开 / 缩回的动画。
                                     *
                                     * 挂在这一层（正文容器）上：外层气泡尺寸跟着它走，
                                     * 于是整条气泡平滑地长高 / 收回。
                                     *
                                     * `animateContentSize` 只是把**量出来的尺寸**做插值并裁剪，
                                     * 文字本身不重排 —— 所以 20 万字也是一次"掀开"，
                                     * 而不是让眼睛去追一段疯狂滚动的文字。
                                     *
                                     * ⚠️ **只在可折叠的长消息上挂**：普通消息的高度不需要插值，
                                     * 而高度被插值意味着列表在动画期间反复重锚 —— 那是另一种"跳"。
                                     */
                                    .then(
                                        if (folded) {
                                            Modifier.animateContentSize(
                                                animationSpec = tween(
                                                    durationMillis = FoldAnimMs,
                                                    easing = MotionEasing,
                                                ),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    )
                            ) {
                                /*
                                 * 选择态：正文外面套一层 `SelectionContainer`，长按即可起选、
                                 * 拖动拉选区，系统工具栏自带复制 / 全选。
                                 *
                                 * 平时不套：那样长按会被选区抢走，而本行的长按是**菜单**。
                                 */
                                SelectableText(selecting, selectionState) {
                                    if (expanded) {
                                        /*
                                         * 展开态：切成多块。
                                         * 外面套一个**无间距**的 Column —— 外层那个
                                         * `spacedBy(8.dp)` 是给"附件 ↔ 正文"用的，
                                         * 块之间加间距会凭空多出一堆空行。
                                         */
                                        Column {
                                            // SelectionContainer places direct children at (0, 0).
                                            // Keep chunks in one vertical layout in both modes.
                                            chunks.forEach { chunk ->
                                                val (searchModifier, onSearchLayout) = rememberSearchTextDecoration(chunk)
                                                Text(
                                                    text = chunk,
                                                    modifier = searchModifier,
                                                    onTextLayout = onSearchLayout,
                                                    style = userTextStyle,
                                                    color = categoryTextColor ?: colors.onUserBubble,
                                                )
                                            }
                                        }
                                    } else {
                                        /*
                                         * ⚠️ 收起态这个 `Text` **必须始终被组合**。
                                         *
                                         * 它的 `onTextLayout` 是唯一能判定"是否超行"的
                                         * 信号源。先前把它放在 `if (folded)` 里，
                                         * 而 `folded` 初始为 false —— 于是这个 Text
                                         * 永远不会被组合、`folded` 永远是 false，
                                         * 折叠功能**从来没生效过**（一个自锁的死循环）。
                                         */
                                        Text(
                                            text = message.content,
                                            modifier = if (folded) Modifier.graphicsLayer {
                                                compositingStrategy = CompositingStrategy.Offscreen
                                            }.drawWithContent {
                                                drawContent()
                                                drawRect(
                                                    brush = Brush.radialGradient(
                                                        colors = listOf(Color.Black, Color.Transparent),
                                                        center = Offset(size.width, size.height),
                                                        radius = fadePx,
                                                    ),
                                                    blendMode = BlendMode.DstOut,
                                                )
                                            } else Modifier,
                                            style = userTextStyle,
                                            color = categoryTextColor ?: colors.onUserBubble,
                                            maxLines = UserBubbleCollapsedLines,
                                            overflow = TextOverflow.Clip,
                                            onTextLayout = { folded = it.didOverflowHeight },
                                        )
                                    }
                                }
                            }

                            if (folded) {
                                /*
                                 * 右下角折叠按钮：**半透明胶囊**。
                                 *
                                 * 用半透明而不是实心底 —— 实心圆贴在气泡角上像一块硬片，太丑；
                                 * 半透明让下面的气泡与淡出的正文透出来，边缘自然化开。
                                 *
                                 * ⚠️ 但它必须有底色（哪怕半透明）：右下角正是渐隐最深的地方，
                                 * 只剩一根线的话会糊在淡出的文字里，看着像被盖住。
                                 * 它在渐隐**之后**绘制（是渐隐那一层的下一个兄弟），所以压在遮罩上方。
                                 */
                                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                                    IconCircleButton(
                                        icon = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                                        contentDescription = if (expanded) stringResource(R.string.md_collapse) else stringResource(R.string.msg_expand_all),
                                        onClick = {
                                            expanded = !expanded
                                            // 先通知外层别跟底，否则展开的高度立刻被吃掉、按钮跑掉
                                            onFoldToggle()
                                        },
                                        size = UserBubbleFoldButtonSize,
                                        width = UserBubbleFoldButtonWidth,
                                        shape = PillShape,
                                        iconSize = 14.dp,
                                        background = colors.card.copy(alpha = 0.72f),
                                        tint = foreground,
                                    )
                                }
                            }
                        }
                    }
                }
                menu()
            }
        }
    }
}

/**
 * AI 消息：左对齐、无气泡，过程面板 + 正文。
 *
 * 长按出菜单，水波纹裁成圆角块（不再是整行矩形）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantBlock(
    message: UiMessage,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    /** 「选择文本」态：正文外包一层 `SelectionContainer`（见 `MessageRow`）。 */
    selecting: Boolean = false,
    selectionState: SelectionState = remember { SelectionState() },
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onDoubleClick: (() -> Unit)? = null,
    onOpenImage: (Attachment) -> Unit = {},
    onRetry: () -> Unit = {},
    onStopImage: () -> Unit = {},
    onChooseModel: (() -> Unit)? = null,
    onRetryWithoutAttachmentsAndTools: (() -> Unit)? = null,
    menu: @Composable () -> Unit = {},
    pressListener: Modifier = Modifier,
    /**
     * 用户展开 / 收起思考面板时调用 —— 原样转达给 `ChatScreen`（见 `MessageList` 的同名参数）。
     */
    onFoldToggle: () -> Unit = {},
    /** 思考内容是否自动收起；见 `MessageList` 的同名参数。 */
    autoCollapseThinking: Boolean = true,
    loadingLabel: String? = null,
    loadingWordsUseThemeColor: Boolean = true,
    reasoningSearchRequest: Long? = null,
) {
    /*
     * 初态由设置决定（`TODO.md` 里那条"只写不读"的开关就是这里接上的）：
     * 开 = 收起（一行的摘要），关 = 展开（全文可见，想收自己点）。
     *
     * 设置并进 `remember` 的 key：用户刚改完设置回头看旧会话，看到的就是新行为。
     * 代价是切换设置会把手动展开/收起的状态重置一次 —— 可接受，
     * 因为那一刻用户要看的正是"这个开关到底有没有用"。
     */
    var reasoningExpanded by remember(autoCollapseThinking) {
        androidx.compose.runtime.mutableStateOf(!autoCollapseThinking)
    }
    LaunchedEffect(reasoningSearchRequest) {
        if (reasoningSearchRequest != null) reasoningExpanded = true
    }
    val hasReasoning = message.hasReasoning
    // 只在等待中的消息首次收到思考文本时渐入，已载入的历史消息直接显示。
    val reasoningVisibility = remember(message.id) { MutableTransitionState(hasReasoning) }
    reasoningVisibility.targetState = hasReasoning

    /*
     * 扩展通道的回复带**内容块时间线**，渲染成「思考 / 搜索 / 浏览 / 正文」的交错结构；
     * OpenAI 通道与老数据的 `timeline` 为空，继续走原来的「思考栏 + 正文」。
     *
     * 两条路并存而不是二选一替换：老会话还得能看，而 OpenAI 通道本来就产不出
     * 搜索块 —— 强行统一只会让老消息丢内容。
     */
    val timeline = remember(message.timeline, message.streaming) {
        if (message.timeline.isEmpty()) {
            null
        } else {
            buildTimelineView(message.timeline, message.streaming)
        }
    }
    /** 当前打开的来源列表；null 表示不显示弹窗。 */
    var sourceSheet by remember { mutableStateOf<List<SourceRef>?>(null) }

    /*
     * 正文：扩展通道走时间线推出来的那份（含引用角标），其余直接就是 `content`。
     * 角标占位换真链接这一步很便宜（纯字符串替换），但**不能省** ——
     * 没换的话点角标会去打不开的 `#cite-N`。
     */
    val answer = if (timeline == null || message.imageGeneration || !timeline.hasAnswer) {
        message.content
    } else {
        remember(timeline) { resolveCitationLinks(timeline.answer, timeline.sources) }
    }

    /*
     * ⚠️ 同样**不能用 `clip`** —— 见 `UserBubble` 上方与设计文档 §31。
     * AI 回复也可能很长，一层带裁剪的图形层同样会让下半部分点不动。
     * 按压反馈改用「按下时给同形状的背景上色」，不产生图形层。
     */
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (pressed) LocalChatColors.current.fieldBackground else Color.Transparent,
                SoftShape,
            )
            .then(pressListener)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = interactive,
                onClick = onClick,
                onLongClick = onLongClick,
                onDoubleClick = onDoubleClick,
            )
            .padding(2.dp),
    ) {
        /*
         * 一直等到首条正文可见才收起加载词。思考内容或时间线先到时，答案首字
         * 仍可能迟迟不出现；保留等待提示，避免长上下文下回复气泡再次显得空白。
         */
        AnimatedVisibility(
            visible = message.streaming && answer.isBlank() && !message.imageGeneration,
            enter = fadeIn(tween(120, easing = MotionEasing)),
            exit = fadeOut(tween(240, easing = MotionEasing)),
        ) {
            Column {
                WaitingIndicator(loadingLabel, useThemeColor = loadingWordsUseThemeColor)
                Spacer(Modifier.size(MessageGap))
            }
        }

        if (!message.imageGeneration && message.attachments.isNotEmpty()) {
            MessageAttachments(message.attachments, onOpenImage)
            Spacer(Modifier.size(MessageGap))
        }

        val reasoningHighlight = LocalSearchHighlight.current.takeIf { reasoningSearchRequest != null }
        CompositionLocalProvider(LocalSearchHighlight provides reasoningHighlight) {
            if (timeline != null) {
                if (timeline.hasSteps) {
                    TimelinePanel(
                        view = timeline,
                        hasGeneratedImage = message.imageGeneration && message.status == MessageStatus.DONE &&
                            message.attachments.any { it.isImage },
                        streaming = message.streaming,
                        expanded = reasoningExpanded,
                        onToggleExpand = {
                            reasoningExpanded = !reasoningExpanded
                            onFoldToggle()
                        },
                        onOpenSources = { sourceSheet = it },
                    )
                    Spacer(Modifier.size(MessageGap))
                }
            } else {
                AnimatedVisibility(
                    visibleState = reasoningVisibility,
                    enter = fadeIn(tween(FoldAnimMs, easing = MotionEasing)),
                    exit = ExitTransition.None,
                ) {
                    Column {
                        ProcessPanel(
                            reasoning = message.reasoning.orEmpty(),
                            reasoningStreaming = message.reasoningStreaming,
                            reasoningSeconds = message.reasoningSeconds,
                            expanded = reasoningExpanded,
                            // 点开后先停跟底，避免展开的内容把标题推到屏幕外。
                            onToggleExpand = {
                                reasoningExpanded = !reasoningExpanded
                                onFoldToggle()
                            },
                        )
                        Spacer(Modifier.size(MessageGap))
                    }
                }
            }
        }

        if (message.imageGeneration) {
            GeneratedImageContent(
                attachment = message.attachments.firstOrNull { it.isImage },
                requestedRatio = message.imageRequest?.previewAspectRatio(),
                stopped = message.status == MessageStatus.STOPPED,
                failed = message.status == MessageStatus.ERROR,
                onStop = onStopImage,
                onRetry = onRetry,
                onOpen = onOpenImage,
            )
            Spacer(Modifier.size(MessageGap))
        }

        /*
         * 正文走 Markdown，**流式期间也是实时 Markdown**（不是纯文本降级）——
         * 两阶段渲染会让定稿那一刻整段重排，版式跳得很难看。
         *
         * 流式的开销靠两道闸压住：上游按固定节奏采样（见 `MarkdownText`），
         * 下游逐块渲染、只有尾块重解析（见 `MarkdownTextBlocks`）。
         */
        /*
         * 点正文角标：按 URL 对回来源表，打开只含这一条的来源弹窗，
         * 不再直接跳浏览器 —— 先让用户看清是哪个网页、讲了什么，再决定要不要去。
         */
        val sources = timeline?.sources.orEmpty()
        val onCitationClick: ((String) -> Unit)? = remember(sources) {
            if (sources.isEmpty()) {
                null
            } else {
                { url -> sources.firstOrNull { it.url == url || escapeHref(it.url) == url }?.let { sourceSheet = listOf(it) } }
            }
        }
        val answerHighlight = LocalSearchHighlight.current.takeIf { reasoningSearchRequest == null }
        // Tool calls can leave a short preamble/process text before the final answer.
        // Fade the answer in only when it first becomes available; historical replies start visible.
        val answerVisibility = remember(message.id) { MutableTransitionState(answer.isNotBlank()) }
        answerVisibility.targetState = answer.isNotBlank()
        AnimatedVisibility(
            visibleState = answerVisibility,
            enter = fadeIn(tween(220, easing = MotionEasing)),
            exit = fadeOut(tween(120, easing = MotionEasing)),
        ) {
            SelectableText(selecting, selectionState) {
                CompositionLocalProvider(LocalCitationClick provides onCitationClick, LocalSearchHighlight provides answerHighlight) {
                    if (answer.length > LARGE_MESSAGE_CHARS) {
                        LargeMessageBody(answer, LocalAssistantFontFamily.current)
                    } else {
                        MarkdownWithMath(content = answer, streaming = message.streaming)
                    }
                }
            }
        }

        // On a live reply, grow the footer into place instead of adding its whole
        // height in one frame while the list is following the bottom.
        val sourcesVisibility = remember(message.id) { MutableTransitionState(sources.isNotEmpty()) }
        sourcesVisibility.targetState = sources.isNotEmpty()
        AnimatedVisibility(
            visibleState = sourcesVisibility,
            enter = expandVertically(tween(180, easing = MotionEasing), expandFrom = Alignment.Top) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(140, easing = MotionEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(100)),
        ) {
            val visibleSources = remember { mutableStateOf(sources) }
            SideEffect { if (sources.isNotEmpty()) visibleSources.value = sources }
            SourcesStrip(
                sources = sources.ifEmpty { visibleSources.value },
                onOpenSources = { sourceSheet = it },
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        sourceSheet?.let { sources ->
            SourceSheet(sources = sources, onDismiss = { sourceSheet = null })
        }

        if (!message.imageGeneration && message.imageRequest != null && message.status == MessageStatus.DONE) {
            Spacer(Modifier.size(8.dp))
            CapsuleButton(text = stringResource(R.string.image_generate_direct), onClick = onRetry, leadingIcon = AppIcons.ImageEdit)
        }

        message.errorMessage?.let { error ->
            val contentRisk = com.simplechat.app.net.providerHintRes(error) == R.string.error_provider_content_risk
            Spacer(Modifier.size(8.dp))
            Text(text = error, style = MaterialTheme.typography.labelMedium, color = LocalChatColors.current.danger)
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (contentRisk && onRetryWithoutAttachmentsAndTools != null) {
                    CapsuleButton(
                        text = stringResource(R.string.error_retry_without_attachments_tools),
                        onClick = onRetryWithoutAttachmentsAndTools,
                    )
                } else if (!contentRisk && onChooseModel != null) {
                    CapsuleButton(text = stringResource(R.string.error_choose_model), onClick = onChooseModel)
                    Spacer(Modifier.size(8.dp))
                }
                if (!contentRisk && !message.imageGeneration) CapsuleButton(
                    text = stringResource(R.string.action_retry),
                    onClick = onRetry,
                    leadingIcon = AppIcons.Refresh,
                )
            }
        }

        menu()
    }
}

/** 消息下方操作栏：复制 / 续写 / 朗读 / 编辑 / 删除，以及版本切换器。 */
@Composable
private fun MessageActions(
    message: UiMessage,
    peeked: Boolean,
    canContinue: Boolean,
    speaking: Boolean,
    readAloudAvailable: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    onSpeak: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
    onVariantChange: ((Int) -> Unit)? = null,
) {
    val colors = LocalChatColors.current

    /*
     * 一整行按钮**同一个尺寸、同一个节奏**。
     *
     * 先前的毛病是版本切换器用 26dp/14dp、操作图标用 30dp/16dp，
     * 同一行里两种大小并排，chevron 明显小一圈、还挤在一起。
     *
     * ★ 「重新生成」放在**行尾最右**（对齐官方：其余操作靠左，刷新单独压右边）。
     * 位置本身就是语义：它是对整条回复的动作，不是"又一个编辑按钮"。
     */
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (onVariantChange != null) {
            VariantSwitcher(message.variantIndex, message.variantCount, onVariantChange)
            Spacer(Modifier.size(ActionGap))
        }
        CopyIconButton(ActionButtonSize, ActionIconSize, colors.processText, stringResource(R.string.action_copy), onCopy)
        if (message.role == MessageRole.ASSISTANT) {
            // 续写紧挨着「重新生成」原本的位置，两者是同一件事的两个方向（重来 / 接着写）
            if (canContinue) {
                ActionIcon(AppIcons.Play, stringResource(R.string.msg_continue), onContinue, colors)
            }
            // 朗读：进行中换成停止图标，按同一下即停。
            // 不可用时整颗按钮不出现（见 ReadAloudEnabled / readAloudAvailable）——
            // 留着但点了没反应更糟；弹一句"不支持"也一样糟。
            if (ReadAloudEnabled && readAloudAvailable) {
                ActionIcon(
                    if (speaking) AppIcons.Stop else AppIcons.Volume,
                    stringResource(if (speaking) R.string.tts_stop else R.string.tts_speak),
                    onSpeak,
                    colors,
                )
            }
        }
        ActionIcon(AppIcons.Edit, stringResource(R.string.action_edit), onEdit, colors)
        if (onDelete != null) ActionIcon(AppIcons.Trash, stringResource(R.string.action_delete), onDelete, colors)

        /*
         * 中间这块是**弹性空白**：碰一下消息时模型名 · token 数出现在这，
         * 不碰就留空 —— 而右边的刷新按钮因此永远钉在同一处。
         *
         * ⚠️ 权重必须挂在**普通 Box** 上，不能挂在 `AnimatedVisibility` 上：
         * 后者在没有内容时（peeked = false）不占位，刷新按钮就会滑到按钮旁边，
         * 表现是"只有显示模型信息时才居右"。
         */
        Box(modifier = Modifier.weight(1f)) {
            MessageMetaSlot(peeked = peeked, message = message)
        }

        if (message.role == MessageRole.ASSISTANT) {
            ActionIcon(AppIcons.Refresh, stringResource(R.string.msg_regenerate), onRegenerate, colors)
        }
    }
}

/**
 * 弹性空白里的元信息（模型名 · token 数）。
 *
 * 单独抽成 Composable 而不是内联：内联时它处在 `RowScope` 里，
 * `AnimatedVisibility` 会解析成 **`RowScope` 的扩展**（要求有剩余宽度才看得见），
 * 而这里要的是顶层版本 —— 放进自己的作用域，解析结果就确定了。
 */
@Composable
private fun MessageMetaSlot(peeked: Boolean, message: UiMessage) {
    AnimatedVisibility(
        visible = peeked,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(120)),
    ) {
        MessageMeta(
            message = message,
            modifier = Modifier.padding(start = ActionGap + 8.dp),
        )
    }
}

/**
 * 「选择文本」态的内容包装。
 *
 * 只在用户从长按菜单里**明确进入**选择态时才套 `SelectionContainer`：
 * 平时套着的话，长按会被选区抢走，而长按是这条消息的菜单入口。
 *
 * [state] 由外层持有 —— 进入选择态时要用它做一次 `clear()`，
 * 这样点空白处退出时选区不会残留在下一次。
 */
private object ShareExcerptMenuKey

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableText(
    selecting: Boolean,
    state: SelectionState,
    content: @Composable () -> Unit,
) {
    // Moving into/out of the registrar must preserve the current page and expanded code blocks.
    val currentContent by rememberUpdatedState(content)
    val body = remember { movableContentOf { currentContent() } }
    if (selecting) {
        val onShareExcerpt = LocalShareExcerpt.current
        val label = stringResource(R.string.share_excerpt)
        SelectionContainer(
            state = state,
            modifier = Modifier.appendTextContextMenuComponents {
                if (onShareExcerpt != null && state.selectedTexts.isNotEmpty()) {
                    item(ShareExcerptMenuKey, label) {
                        val text = state.selectedTexts.joinToString("\n") { it.text }.trim()
                        close()
                        if (text.isNotEmpty()) onShareExcerpt(text)
                    }
                }
            },
        ) { body() }
    } else body()
}

/**
 * 在 [windowPoint] 处**合成一次长按**，让选区从用户按下去的地方开始。
 *
 * 为什么要合成：Compose 没有"按坐标设置选区"的公开接口 —— `SelectionState`
 * 只接受**全局字符偏移**（多段文本还要自己算累计长度），而这里只知道手指落在
 * 屏幕上的哪个点。与其把一套排版几何抄一遍，不如把同一个点上的长按重放一次：
 * 命中测试、行内定位、选区手柄全都走系统原本那条路。
 *
 * 时长 ≥ 系统的长按阈值，否则会被判成"点了一下"（那会把选择态立刻关掉）。
 * 注入的事件只发给本进程的 Compose 视图，不经过窗口路由，也不影响其他 App。
 */
private suspend fun startSelectionAt(view: View, windowPoint: IntOffset, holdMillis: Long) {
    if (!view.isShown) return
    val origin = IntArray(2)
    view.getLocationInWindow(origin)
    val x = (windowPoint.x - origin[0]).toFloat()
    val y = (windowPoint.y - origin[1]).toFloat()
    val downTime = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    view.dispatchTouchEvent(down)
    down.recycle()
    var completed = false
    try {
        delay(holdMillis)
        completed = true
    } finally {
        // Cancellation (navigation / dismissal) must terminate the injected pointer too.
        val action = if (completed) MotionEvent.ACTION_UP else MotionEvent.ACTION_CANCEL
        val end = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        view.dispatchTouchEvent(end)
        end.recycle()
    }
}

/**
 * 进入选择态后、注入长按前的等待。
 *
 * 要盖住两件事：`SelectionContainer` 得先挂上，菜单的**关闭动画**（140ms）也得走完 ——
 * 菜单还是个 focusable 的独立窗口，没关干净时注入的按下有可能被它吃掉。
 */
private const val SelectionMenuDismissDelayMs = 160L

/**
 * 「这条是谁写的、占多少 token」。
 *
 * token 数**算的是它进上下文的那部分**：正文 + 附件。
 * 思维链不计 —— 它只在本地展示，构造请求时不回传（§11.1），
 * 算进去会让这个数字与实际占用对不上。
 */
@Composable
private fun MessageMeta(message: UiMessage, modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    val tokens = TokenEstimate.of(message.content) +
        attachmentTokenEstimate(message.attachments)

    // 老数据（v3 以前）没有 model 这一列 → 只显示 token 数，不猜一个模型填上去
    val label = listOfNotNull(
        message.model?.takeIf { it.isNotBlank() },
        "$tokens tokens",
    ).joinToString(" · ")

    Text(
        text = label,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = colors.placeholder,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 操作栏统一尺寸 —— 改这里一处，整行跟着变。 */
private val ActionButtonSize = 30.dp
private val ActionIconSize = 16.dp
private val ActionGap = 2.dp

/**
 * 消息内部各段之间的间距：思考栏 ↔ 正文 ↔ 操作栏。
 *
 * **只有一个值** —— 上下两处用同一常量，间距才是真的统一。
 * 先前是「思考栏→正文 14dp、正文→操作栏 6dp」，两头不一致。
 */
private val MessageGap = 12.dp

/** 用户消息收起时显示几行。超过就折叠 —— 参照官方。 */
private val UserBubbleCollapsedLines = 10

/** 折叠时右下角渐隐的半径。要够大才有"化开"的感觉，太小就成了一条硬边。 */
private val UserBubbleFadeRadius = 140.dp

/*
 * 折叠刻度（行数 / 渐隐半径 / 胶囊尺寸）见各自调用点；
 * 折叠**时长**在 `ui/common/Motion.kt` 的 `FoldAnimMs` —— 思考面板用的是同一个。
 */

/** 折叠按钮的高。比操作栏的图标按钮小一档 —— 它是挂在气泡里的，不该抢戏。 */
private val UserBubbleFoldButtonSize = 22.dp

/** 折叠按钮的宽。比高宽出一截 —— 做成**胶囊**，不是圆。 */
private val UserBubbleFoldButtonWidth = 40.dp

@Composable
private fun ActionIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    colors: ChatColors,
    destructive: Boolean = false,
) {
    IconCircleButton(
        icon = icon,
        contentDescription = description,
        onClick = onClick,
        size = ActionButtonSize,
        iconSize = ActionIconSize,
        tint = if (destructive) colors.danger else colors.processText,
    )
}

/** 版本切换器 `‹ 2/3 ›`，对应槽位模型的多版本。 */
@Composable
fun VariantSwitcher(
    index: Int,
    count: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 与操作栏同一尺寸规格，不再自成一套
        IconCircleButton(
            icon = AppIcons.ChevronLeft,
            contentDescription = stringResource(R.string.msg_variant_prev),
            onClick = { onChange(index - 1) },
            size = ActionButtonSize,
            iconSize = ActionIconSize,
            enabled = index > 0,
            tint = if (index > 0) {
                MaterialTheme.colorScheme.onBackground
            } else {
                colors.placeholder
            },
        )
        Text(
            text = "${index + 1}/$count",
            style = MaterialTheme.typography.labelSmall,
            color = colors.processText,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        IconCircleButton(
            icon = AppIcons.ChevronRight,
            contentDescription = stringResource(R.string.msg_variant_next),
            onClick = { onChange(index + 1) },
            size = ActionButtonSize,
            iconSize = ActionIconSize,
            enabled = index < count - 1,
            tint = if (index < count - 1) {
                MaterialTheme.colorScheme.onBackground
            } else {
                colors.placeholder
            },
        )
    }
}
