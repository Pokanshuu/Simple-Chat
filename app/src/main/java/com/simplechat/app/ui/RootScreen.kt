package com.simplechat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplechat.app.App
import com.simplechat.app.R
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.ui.chat.ChatScreen
import com.simplechat.app.ui.chat.ChatViewModel
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppShadow
import com.simplechat.app.ui.common.AppTextInputDialog
import com.simplechat.app.ui.common.MotionDurationMs
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.common.screenTransform
import com.simplechat.app.ui.history.ConversationSearchPage
import com.simplechat.app.ui.history.HistoryDrawerContent
import com.simplechat.app.ui.history.HistoryViewModel
import com.simplechat.app.ui.settings.SettingsScreen
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.launch

/**
 * 顶层路由。
 *
 * 只有两个：对话页与设置页。**搜索不在其中** —— 它不是"另一个页面"，
 * 而是抽屉自己的一种状态（见 [RootScreen] 的 `searchMode`）。
 *
 * [depth] 用来判断转场方向：更深的一层从右侧进来、退出时原路返回。
 */
private enum class Route(val depth: Int) {
    CHAT(0),
    SETTINGS(1),
}

/** 抽屉平时的宽度。 */
private const val DrawerWidthFraction = 0.78f

/** 抽屉圆角。撑满时必须收成 0，否则全屏面板会在四角露出底下的内容。 */
private val DrawerCorner = 16.dp

/**
 * 顶层路由与侧拉抽屉。
 *
 * 刻意**不引 Navigation 组件** —— 页面就两个，一个状态枚举足够。
 *
 * ### 搜索为什么不是一条路由
 *
 * 点搜索框时**抽屉不关**：同一个面板从 78% 撑到满屏，内容换成搜索页。
 * 全程只有**一个**运动。
 *
 * 先前是"关掉抽屉（往左跑）+ 切到搜索路由（从右边推入）" ——
 * 两个方向相反的运动同时发生，看着就是各走各的。
 * 而搜索本来就该是"把抽屉拉开看清楚"，不是"跳到另一个页面"：
 * 它的搜索框、它的列表，都在同一块面板上。
 */
@Composable
fun RootScreen() {
    val container = (LocalContext.current.applicationContext as App).container
    val scope = rememberCoroutineScope()

    val chatViewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(
            chatApi = container.chatApi,
            settingsStore = container.settingsStore,
            repository = container.chatRepository,
            openCodeSessionId = container.openCodeSessionId,
        ),
    )
    val historyViewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.factory(container.chatRepository),
    )

    val conversations by historyViewModel.conversations.collectAsStateWithLifecycle()
    val searchHits by historyViewModel.searchHits.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    /*
     * `rememberSaveable` 而不是 `remember`：换语言是 `recreate()` 整页重建，
     * 普通 `remember` 会把导航状态丢掉 —— 表现就是"切个语言，人被送回对话页"。
     * 存档过一次实例状态，重建后就还停在原来那一页（设置）。
     */
    var route by rememberSaveable { mutableStateOf(Route.CHAT) }

    /** 抽屉是否处于搜索态。见类注释：它是抽屉的状态，不是一条路由。 */
    var searchMode by remember { mutableStateOf(false) }

    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    // 首次进入播种内置预设
    remember { historyViewModel.seedPresets(); Unit }

    /** 关抽屉时顺带退出多选，避免下次打开还停在选择态。 */
    fun closeDrawer() {
        historyViewModel.exitSelection()
        scope.launch { drawerState.close() }
    }

    /*
     * 返回键。优先级**从低到高**书写 ——
     * `OnBackPressedDispatcher` 是后添加的先响应，所以写在最后的优先级最高。
     *
     *   ① 设置页 → 回对话页
     *   ② 抽屉打开 → 关抽屉
     *   ③ 搜索态 → 退出搜索（抽屉保持打开）
     */

    BackHandler(enabled = route != Route.CHAT) {
        route = Route.CHAT
    }

    /*
     * ⚠️ 这条不能省，而且**不是** Material3 帮我们做的。
     *
     * 实测：抽屉打开时按返回键，`ModalNavigationDrawer` 不消费、上面那条也
     * 是 disabled，事件一路落空到 Activity → 直接 `finish()`，用户被丢出 App。
     * （旧版本同样如此，只是没人从抽屉里按过返回键。）
     */
    BackHandler(enabled = drawerState.isOpen) {
        closeDrawer()
    }

    // 搜索态按返回 → 回抽屉列表。抽屉**保持打开** —— 用户本来就是从那儿进来的。
    BackHandler(enabled = searchMode) {
        historyViewModel.updateQuery("")
        searchMode = false
    }

    // ── 抽屉：搜索时把面板从 78% 撑满 ──────────────────────
    val sheetFraction by animateFloatAsState(
        targetValue = if (searchMode) 1f else DrawerWidthFraction,
        animationSpec = tween(MotionDurationMs, easing = MotionEasing),
        label = "sheetFraction",
    )
    val sheetCorner by animateDpAsState(
        targetValue = if (searchMode) 0.dp else DrawerCorner,
        animationSpec = tween(MotionDurationMs, easing = MotionEasing),
        label = "sheetCorner",
    )
    val sheetShape = RoundedCornerShape(topEnd = sheetCorner, bottomEnd = sheetCorner)

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 撑满时禁止拖动：那时它已经不是"抽屉"了，往左拖会把它拖成一个奇怪的宽度
        gesturesEnabled = route == Route.CHAT && !searchMode,
        // 遮罩由下面 content 里的显式 scrim 画，内置 scrim 关掉，避免双层遮罩。
        scrimColor = Color.Transparent,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxWidth(sheetFraction)
                    .fillMaxHeight()
                    // 抽屉自己也要"浮起来"，否则右边缘是一道生硬的白灰交界
                    .shadow(
                        elevation = AppShadow.RaisedElevation,
                        shape = sheetShape,
                        clip = false,
                        ambientColor = AppShadow.RaisedAmbient,
                        spotColor = AppShadow.RaisedSpot,
                    ),
                drawerShape = sheetShape,
                // 与抽屉内容同色，否则状态栏那一条会露出 Material 的默认容器色（淡紫）
                drawerContainerColor = LocalChatColors.current.card,
                /*
                 * 系统栏内边距**由抽屉内容自己处理**（`HistoryDrawerContent` 里
                 * 有 `statusBarsPadding` / `navigationBarsPadding`）。
                 * 两边都加会得到双倍高度，顶部那一截还会是容器的底色 —— 实测就是
                 * 抽屉顶上多出一条淡紫色带。
                 */
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                /*
                 * 列表 ↔ 搜索交叉淡入。
                 *
                 * 宽度正在变，内容如果硬切会像"闪一下"；
                 * 叠一层淡入淡出，视觉上就是"同一块面板换了个内容"。
                 */
                Crossfade(
                    targetState = searchMode,
                    modifier = Modifier.fillMaxSize(),
                    animationSpec = tween(MotionDurationMs, easing = MotionEasing),
                    label = "drawerContent",
                ) { searching ->
                    if (searching) {
                        ConversationSearchPage(
                            hits = searchHits,
                            currentConversationId = chatViewModel.currentConversationId,
                            query = historyViewModel.query,
                            onQueryChange = { historyViewModel.updateQuery(it) },
                            onBack = {
                                historyViewModel.updateQuery("")
                                searchMode = false
                            },
                            onClick = { conversation ->
                                historyViewModel.updateQuery("")
                                searchMode = false
                                closeDrawer()
                                chatViewModel.openConversation(conversation.id)
                            },
                            onRename = { renameTarget = it },
                            onTogglePin = { historyViewModel.togglePin(it) },
                            onDelete = { deleteTarget = it },
                        )
                    } else {
                        HistoryDrawerContent(
                            conversations = conversations,
                            currentConversationId = chatViewModel.currentConversationId,
                            selectionMode = historyViewModel.selectionMode,
                            selectedIds = historyViewModel.selectedIds.toList(),
                            onSearchClick = { searchMode = true },
                            onClick = { conversation ->
                                closeDrawer()
                                chatViewModel.openConversation(conversation.id)
                            },
                            onEnterSelection = { historyViewModel.enterSelection(it.id) },
                            onToggleSelection = { historyViewModel.toggleSelection(it) },
                            onRename = { renameTarget = it },
                            onTogglePin = { historyViewModel.togglePin(it) },
                            onDelete = { deleteTarget = it },
                            onExitSelection = { historyViewModel.exitSelection() },
                            onPinSelected = { pinned -> historyViewModel.pinSelected(pinned) },
                            onDeleteSelected = { confirmBatchDelete = true },
                            onOpenSettings = {
                                closeDrawer()
                                route = Route.SETTINGS
                            },
                        )
                    }
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            /*
             * 底层内容：对话页 / 设置页。
             *
             * 页面切换统一走 screenTransform（ui/common/Motion.kt）。
             * SaveableStateHolder 保住各页自己的滚动位置 —— 页面离开组合后
             * `rememberSaveable` 的存档会一并丢掉，于是"从设置返回对话就得重新滚到底"。
             * 存档本来就是导航组件该管的事，我们不引导航组件，就得自己接上。
             */
            val pageStates = rememberSaveableStateHolder()

            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    screenTransform(forward = targetState.depth > initialState.depth)
                },
                label = "route",
            ) { target ->
                pageStates.SaveableStateProvider(target) {
                    when (target) {
                        Route.CHAT -> ChatScreen(
                            vm = chatViewModel,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onNewConversation = {
                                chatViewModel.newConversation()
                            },
                        )

                        Route.SETTINGS -> SettingsScreen(onBack = { route = Route.CHAT })
                    }
                }
            }

            /*
             * 抽屉遮罩 —— 显式画，不依赖 Material 内置 scrim。
             *
             * 内置 scrim 在本项目的组合下（自定义抽屉宽度 + 搜索态撑满）表现不稳定，
             * 用户反馈"遮罩只在点按钮时闪一下"。这里自己铺一层：抽屉一旦开始
             * 打开/拖动就淡入，点遮罩即关抽屉，行为与官方一致。
             */
            val scrim = LocalChatColors.current.scrim
            val drawerShown by remember(drawerState) {
                derivedStateOf { drawerState.targetValue != DrawerValue.Closed }
            }
            AnimatedVisibility(
                visible = drawerShown,
                enter = fadeIn(tween(MotionDurationMs, easing = MotionEasing)),
                exit = fadeOut(tween(MotionDurationMs, easing = MotionEasing)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scrim)
                        .pointerInput(Unit) {
                            detectTapGestures { closeDrawer() }
                        },
                )
            }
        }
    }

    // ── 弹窗 ─────────────────────────────────────────────

    renameTarget?.let { target ->
        AppTextInputDialog(
            title = stringResource(R.string.hist_rename_title),
            initial = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { value ->
                historyViewModel.rename(target.id, value)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        AppConfirmDialog(
            title = stringResource(R.string.hist_delete_title),
            message = stringResource(R.string.hist_delete_body),
            confirmText = stringResource(R.string.hist_delete_confirm),
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                // 删的正是当前正在看的会话 → 立刻回到空态，
                // 否则对话页还挂着一个已经不存在的会话
                if (chatViewModel.currentConversationId == target.id) {
                    chatViewModel.newConversation()
                }
                historyViewModel.delete(target.id)
                deleteTarget = null
            },
        )
    }

    if (confirmBatchDelete) {
        val count = historyViewModel.selectedIds.size
        AppConfirmDialog(
            title = stringResource(R.string.hist_delete_selected_title, count),
            message = stringResource(R.string.hist_delete_selected_body),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { confirmBatchDelete = false },
            onConfirm = {
                // 当前会话也在被删之列 → 同样立刻回空态
                if (chatViewModel.currentConversationId in historyViewModel.selectedIds) {
                    chatViewModel.newConversation()
                }
                historyViewModel.deleteSelected()
                confirmBatchDelete = false
            },
        )
    }
}
