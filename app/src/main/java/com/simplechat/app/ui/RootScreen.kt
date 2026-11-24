package com.simplechat.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import com.simplechat.app.data.InterfaceControl
import com.simplechat.app.ui.theme.floatingGlassBackground
import com.simplechat.app.ui.theme.floatingPanelSurface
import com.simplechat.app.ui.theme.needsLiveSurface
import com.simplechat.app.ui.theme.rememberLiveBlurAvailable
import com.simplechat.app.ui.theme.rememberBackdropSource
import com.simplechat.app.ui.theme.recordBackdrop
import com.simplechat.app.ui.theme.LocalWallpaper
import com.simplechat.app.ui.theme.drawWallpaper
import com.simplechat.app.ui.theme.rememberWallpaperBitmap
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.simplechat.app.App
import com.simplechat.app.R
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.ui.chat.ChatScreen
import com.simplechat.app.ui.chat.ChatViewModel
import com.simplechat.app.ui.chat.HorizontalScrollRegions
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppShadow
import com.simplechat.app.ui.common.AppTextInputDialog
import com.simplechat.app.ui.common.MotionDurationMs
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.common.screenTransform
import com.simplechat.app.ui.history.ConversationSearchPage
import com.simplechat.app.ui.history.HistoryDrawerContent
import com.simplechat.app.ui.history.HistoryViewModel
import com.simplechat.app.ui.history.searchResultKey
import com.simplechat.app.ui.settings.SettingsScreen
import com.simplechat.app.ui.onboarding.OnboardingScreen
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.GlassForeground
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
    SETTINGS(1), IMAGE_SETTINGS(1), API_SETTINGS(1),
}

/** 抽屉平时的宽度。 */
private const val DrawerWidthFraction = 0.78f
private const val WideChatMinWidthDp = 720
private val WideSidebarWidth = 256.dp

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
fun RootScreen(chat: ChatViewModel? = null, history: HistoryViewModel? = null) {
    val container = (LocalContext.current.applicationContext as App).container
    val onboarding by container.settingsStore.flow.collectAsStateWithLifecycle(initialValue = null)
    if (onboarding == null) return
    if (onboarding?.onboardingComplete == false) {
        OnboardingScreen(
            settingsStore = container.settingsStore,
            chatApi = container.chatApi,
            repository = container.chatRepository,
        )
        return
    }
    val scope = rememberCoroutineScope()

    val chatViewModel: ChatViewModel = chat ?: viewModel(
        factory = ChatViewModel.factory(
            chatApi = container.chatApi,
            settingsStore = container.settingsStore,
            repository = container.chatRepository,
            openCodeSessionId = container.openCodeSessionId,
        ),
    )
    val historyViewModel: HistoryViewModel = history ?: viewModel(
        factory = HistoryViewModel.factory(container.chatRepository, container.settingsStore),
    )

    val conversations by historyViewModel.conversations.collectAsStateWithLifecycle()
    val searchPage by historyViewModel.searchPage.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val configuration = LocalConfiguration.current
    val wideChatLayout = configuration.screenWidthDp >= WideChatMinWidthDp &&
        (configuration.screenWidthDp > configuration.screenHeightDp || configuration.screenWidthDp >= 1000)
    var wideSidebarVisible by rememberSaveable { mutableStateOf(true) }
    var searchWasDocked by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(wideChatLayout) {
        if (wideChatLayout && drawerState.targetValue == DrawerValue.Open) drawerState.close()
    }

    /*
     * `rememberSaveable` 而不是 `remember`：换语言是 `recreate()` 整页重建，
     * 普通 `remember` 会把导航状态丢掉 —— 表现就是"切个语言，人被送回对话页"。
     * 存档过一次实例状态，重建后就还停在原来那一页（设置）。
     */
    var route by rememberSaveable { mutableStateOf(Route.CHAT) }

    /** 抽屉是否处于搜索态。见类注释：它是抽屉的状态，不是一条路由。 */
    var searchMode by rememberSaveable { mutableStateOf(false) }
    // 搜索切换会移除普通抽屉列表，折叠状态留在根界面。
    var pinnedGroupExpanded by rememberSaveable { mutableStateOf(true) }
    var readingSearchResult by rememberSaveable { mutableStateOf(false) }
    var openingSearchResult by remember { mutableStateOf(false) }
    var selectedSearchResultKey by rememberSaveable { mutableStateOf<String?>(null) }
    val searchListState = rememberLazyListState()
    var horizontalRegionPressed by remember { mutableStateOf(false) }
    var drawerOrigin by remember { mutableStateOf(Offset.Zero) }

    var renameTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationEntity?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    // 首次进入播种内置预设
    androidx.compose.runtime.LaunchedEffect(historyViewModel) { historyViewModel.seedPresets() }

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

    fun exitSearch() {
        historyViewModel.updateQuery("")
        searchMode = false
        readingSearchResult = false
        selectedSearchResultKey = null
        if (searchWasDocked) wideSidebarVisible = true
        searchWasDocked = false
    }

    BackHandler(enabled = !wideChatLayout && route == Route.CHAT && readingSearchResult && !drawerState.isOpen) {
        scope.launch { drawerState.open() }
    }
    BackHandler(enabled = !wideChatLayout && drawerState.isOpen) { closeDrawer() }
    BackHandler(enabled = wideChatLayout && route == Route.CHAT && wideSidebarVisible && historyViewModel.selectionMode) {
        historyViewModel.exitSelection()
    }
    BackHandler(enabled = route == Route.CHAT && searchMode &&
        (if (wideChatLayout) (wideSidebarVisible || searchWasDocked) else drawerState.isOpen)) {
        exitSearch()
    }

    val sidebarContent: @Composable (Boolean) -> Unit = { docked ->
        Crossfade(
            targetState = searchMode,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(MotionDurationMs, easing = MotionEasing),
            label = "historySearch",
        ) { searching ->
            if (searching) {
                val displayedPage = searchPage
                ConversationSearchPage(
                    hits = displayedPage.hits,
                    isLoading = displayedPage.isLoading,
                    hasMore = displayedPage.hasMore,
                    onLoadMore = historyViewModel::loadMoreSearchResults,
                    listState = searchListState,
                    autoFocus = !readingSearchResult && (docked || drawerState.targetValue == DrawerValue.Open),
                    selectedResultKey = selectedSearchResultKey,
                    query = displayedPage.query,
                    onQueryChange = { selectedSearchResultKey = null; historyViewModel.updateQuery(it) },
                    onBack = { exitSearch() },
                    onClick = { hit ->
                        if (!openingSearchResult && displayedPage.query == historyViewModel.query) {
                            openingSearchResult = true
                            selectedSearchResultKey = hit.searchResultKey
                            readingSearchResult = true
                            historyViewModel.exitSelection()
                            scope.launch {
                                try {
                                    if (!docked) drawerState.close()
                                    chatViewModel.openConversation(hit.conversation.id, hit.messageId, displayedPage.query.trim(), hit.reasoningMatch)
                                } finally {
                                    openingSearchResult = false
                                }
                            }
                        }
                    },
                    onRename = { renameTarget = it },
                    onTogglePin = historyViewModel::togglePin,
                    onDelete = { deleteTarget = it },
                )
            } else {
                HistoryDrawerContent(
                    conversations = conversations,
                    currentConversationId = chatViewModel.currentConversationId,
                    selectionMode = historyViewModel.selectionMode,
                    selectedIds = historyViewModel.selectedIds.toList(),
                    pinnedGroupExpanded = pinnedGroupExpanded,
                    onTogglePinnedGroup = { pinnedGroupExpanded = !pinnedGroupExpanded },
                    onSearchClick = {
                        readingSearchResult = false
                        searchWasDocked = docked
                        searchMode = true
                    },
                    onClick = { conversation ->
                        if (!docked) closeDrawer()
                        else if (searchMode) exitSearch()
                        chatViewModel.openConversation(conversation.id)
                    },
                    onEnterSelection = { historyViewModel.enterSelection(it.id) },
                    onToggleSelection = historyViewModel::toggleSelection,
                    onRename = { renameTarget = it },
                    onTogglePin = historyViewModel::togglePin,
                    onDelete = { deleteTarget = it },
                    onExitSelection = historyViewModel::exitSelection,
                    onPinSelected = historyViewModel::pinSelected,
                    onDeleteSelected = { confirmBatchDelete = true },
                    onOpenSettings = {
                        closeDrawer()
                        route = Route.SETTINGS
                    },
                )
            }
        }
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

    var sheetWidth by remember { mutableIntStateOf(0) }
    val drawerVisible by remember(drawerState) { derivedStateOf {
        drawerState.isOpen || drawerState.targetValue == DrawerValue.Open || drawerState.currentOffset > -sheetWidth.toFloat()
    } }
    val sidebarBlur = needsLiveSurface(InterfaceControl.SIDEBAR) && rememberLiveBlurAvailable()
    val drawerSource = rememberBackdropSource(!wideChatLayout && drawerVisible && sidebarBlur)

    ModalNavigationDrawer(
        modifier = Modifier.onGloballyPositioned { drawerOrigin = it.boundsInRoot().topLeft }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    horizontalRegionPressed = HorizontalScrollRegions.any { it.contains(drawerOrigin + down.position) }
                    try {
                        do { val event = awaitPointerEvent(PointerEventPass.Initial) }
                        while (event.changes.any { it.pressed })
                    } finally { horizontalRegionPressed = false }
                }
            },
        drawerState = drawerState,
        // 撑满时禁止拖动：那时它已经不是"抽屉"了，往左拖会把它拖成一个奇怪的宽度
        gesturesEnabled = !wideChatLayout && !horizontalRegionPressed && route == Route.CHAT && (!searchMode || (readingSearchResult && !drawerState.isOpen)),
        // 遮罩由下面 content 里的显式 scrim 画，内置 scrim 关掉，避免双层遮罩。
        scrimColor = Color.Transparent,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .onSizeChanged { sheetWidth = it.width }
                    .fillMaxWidth(if (wideChatLayout) 0f else sheetFraction)
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
                // 面板底色由容器统一绘制一次，抽屉内容沿用这层底色。
                drawerContainerColor = Color.Transparent,
                /*
                 * 系统栏内边距**由抽屉内容自己处理**（`HistoryDrawerContent` 里
                 * 有 `statusBarsPadding` / `navigationBarsPadding`）。
                 * 两边都加会得到双倍高度，顶部那一截还会是容器的底色 —— 实测就是
                 * 抽屉顶上多出一条淡紫色带。
                 */
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                if (!wideChatLayout) {
                    val colors = LocalChatColors.current
                    val surface = floatingPanelSurface(InterfaceControl.SIDEBAR)
                    val navigationBars = WindowInsets.navigationBars
                    val bottomInset = navigationBars.getBottom(LocalDensity.current)
                    Box(
                        Modifier.fillMaxSize()
                            .floatingGlassBackground(
                                colors.card, InterfaceControl.SIDEBAR, surface, drawerSource,
                                extendIntoBottomInset = bottomInset,
                            ),
                    ) { GlassForeground(InterfaceControl.SIDEBAR, surface) { sidebarContent(false) } }
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
                modifier = Modifier.recordBackdrop(drawerSource),
                targetState = route,
                transitionSpec = {
                    screenTransform(forward = targetState.depth > initialState.depth)
                },
                label = "route",
            ) { target ->
                pageStates.SaveableStateProvider(target) {
                    when (target) {
                        Route.CHAT -> Row(
                            modifier = Modifier.fillMaxSize().background(LocalChatColors.current.pageBackground).then(
                                if (configuration.screenWidthDp >= 600) Modifier.windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                                ) else Modifier
                            ),
                        ) {
                            AnimatedVisibility(
                                visible = wideChatLayout && (wideSidebarVisible || searchWasDocked),
                                enter = expandHorizontally(tween(MotionDurationMs, easing = MotionEasing), Alignment.Start) + fadeIn(),
                                exit = shrinkHorizontally(tween(MotionDurationMs, easing = MotionEasing), Alignment.Start) + fadeOut(),
                            ) {
                                Box(Modifier.width(WideSidebarWidth).fillMaxHeight()) {
                                    /*
                                     * 宽屏常驻侧栏也要玻璃化：竖屏抽屉走的是
                                     * ModalDrawerSheet 的容器色，宽屏走的是这一个 Box ——
                                     * 两条路径不接同一个 alpha 的话，平板/横屏用户就会
                                     * 看到"聊天区透明、侧栏死白"。
                                     */
                                    val colors = LocalChatColors.current
                                    val wallpaper = LocalWallpaper.current
                                    val bitmap = rememberWallpaperBitmap(wallpaper)
                                    val source = rememberBackdropSource(sidebarBlur && bitmap != null)
                                    // Draw over the filled sidebar, not as a translucent strip above the window.
                                    Box(Modifier.fillMaxSize().clip(androidx.compose.ui.graphics.RectangleShape).drawWithContent {
                                        drawContent()
                                        val thickness = 1f
                                        val edge = if (layoutDirection == LayoutDirection.Ltr) size.width - thickness else 0f
                                        drawRect(colors.divider, topLeft = Offset(edge, 0f), size = Size(thickness, size.height))
                                    }) {
                                        Box(Modifier.matchParentSize().recordBackdrop(source).drawBehind {
                                            drawRect(colors.pageBackground)
                                            bitmap?.let { drawWallpaper(it, wallpaper, colors.pageBackground) }
                                        })
                                        Box(Modifier.fillMaxSize().floatingGlassBackground(colors.card, InterfaceControl.SIDEBAR, source = source)) {
                                        GlassForeground(InterfaceControl.SIDEBAR) {
                                        if (searchMode) {
                                            SearchContent(
                                                searchPage = searchPage,
                                                listState = searchListState,
                                                autoFocus = true,
                                                selectedSearchResultKey = selectedSearchResultKey,
                                                historyViewModel = historyViewModel,
                                                onBack = { exitSearch() },
                                                onOpenSearchResult = { hit, keyword ->
                                                    if (!openingSearchResult) {
                                                        openingSearchResult = true
                                                        selectedSearchResultKey = hit.searchResultKey
                                                        readingSearchResult = true
                                                        scope.launch {
                                                            try {
                                                                chatViewModel.openConversation(hit.conversation.id, hit.messageId, keyword, hit.reasoningMatch)
                                                                if (!searchWasDocked) exitSearch()
                                                            } finally { openingSearchResult = false }
                                                        }
                                                    }
                                                },
                                                onRename = { renameTarget = it },
                                                onTogglePin = historyViewModel::togglePin,
                                                onDelete = { deleteTarget = it },
                                            )
                                        } else sidebarContent(true)
                                        }
                                        }
                                    }
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                                ChatScreen(
                                    vm = chatViewModel,
                                    onOpenDrawer = {
                                        if (wideChatLayout) {
                                            if (wideSidebarVisible) historyViewModel.exitSelection()
                                            wideSidebarVisible = !wideSidebarVisible
                                        } else scope.launch { drawerState.open() }
                                    },
                                    onConfigureApi = { route = Route.API_SETTINGS },
                                    onConfigureImageApi = { route = Route.IMAGE_SETTINGS },
                                    onNewConversation = {
                                        exitSearch()
                                        chatViewModel.newConversation()
                                    },
                                    constrainContentWidth = configuration.screenWidthDp >= 600,
                                )
                            }
                        }

                        Route.SETTINGS, Route.IMAGE_SETTINGS, Route.API_SETTINGS -> SettingsScreen(
                            onBack = { route = Route.CHAT }, startAtImageApi = target == Route.IMAGE_SETTINGS,
                            startAtApiProfiles = target == Route.API_SETTINGS,
                            onMessagesRestored = chatViewModel::refreshRestoredMessages,
                        )
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
                visible = !wideChatLayout && drawerShown,
                enter = fadeIn(tween(MotionDurationMs, easing = MotionEasing)),
                exit = fadeOut(tween(MotionDurationMs, easing = MotionEasing)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind {
                            // Dim only the exposed chat, so a translucent drawer never sees this scrim.
                            val offset = drawerState.currentOffset
                            val covered = if (offset.isFinite()) {
                                (sheetWidth + offset).coerceIn(0f, size.width)
                            } else 0f
                            val left = if (layoutDirection == LayoutDirection.Ltr) covered else 0f
                            drawRect(scrim, topLeft = Offset(left, 0f), size = Size(size.width - covered, size.height))
                        }
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
            // 移到回收站可撤销，不用危险红（§7.1：红色只给不可逆操作）
            destructive = false,
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
            // 移到回收站可撤销，不用危险红（§7.1：红色只给不可逆操作）
            destructive = false,
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
