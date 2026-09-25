package com.simplechat.app.ui.chat

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.AttachmentImport
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppShadow
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.common.dismissKeyboardOnTouch
import com.simplechat.app.ui.markdown.warmMarkdownCache
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxHeight

/** 输入栏下方最多同时展开一个面板。 */
private enum class InputPanelState { NONE, THINKING, STATS, SETTINGS, ATTACH }

/** 切换会话的淡入时长。**快** —— 慢一点就从"过渡"变成"等待"了。 */
private const val SwitchFadeInMs = 160

/** 换会话时等列表真正贴底的上限。超过就先淡入，别把白屏停太久。 */
private const val BottomSettleMs = 900L

/**
 * 判定"超长对话"的阈值（单条消息字符数）。
 *
 * 只有这种对话才给加载态 —— 因为拖慢首次布局的是**单条消息的文字排版**
 * （一条两万字的段落要排出上千行），不是对话有多少条。
 * 普通长对话（几十条短消息）直接出内容，不闪圈（对齐官方）。
 */
private const val HugeMessageChars = 20_000

/** 等新会话内容就位的兜底上限。超过就当它已经好了，不能让界面停在透明态。 */
private const val SwitchLoadTimeoutMs = 2_000L

/** 打字时草稿落库的防抖间隔。与「会话标题」那条同档。 */
private const val DraftSaveDelayMs = 400L

/** 输入栏上方"淡出"的高度。正文滑进这一段就渐渐看不见了。 */
private val BottomFadeHeight = 22.dp

/** 淡出带整体下移量：正文多留一点实字，贴着输入栏才淡完（尾段落在输入栏背后）。 */
private val BottomFadeDrop = 6.dp

/**
 * 对话主界面。
 *
 * 消息与流式全部由 [ChatViewModel] 驱动；本文件只负责布局与交互。
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val context = LocalContext.current

    var panel by remember { mutableStateOf(InputPanelState.NONE) }
    var input by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<UiMessage?>(null) }

    /**
     * 两条编辑路径，对应两种真实需求：
     * - 用户消息：**复用输入栏**（官方做法）。一个发送键 = 保存并重发，
     *   不必让用户在"仅改文字 / 编辑并重发"之间做选择 —— 改了提问，
     *   旧回答本来就没意义了。
     * - AI 回复：弹窗二选一。「保存」只留一个新版本；「保存并发送」
     *   还会据此继续往下生成。
     */
    var editingMessage by remember { mutableStateOf<UiMessage?>(null) }
    var aiEditTarget by remember { mutableStateOf<UiMessage?>(null) }
    var inputBeforeEdit by remember { mutableStateOf("") }

    /** 待发送附件（图片 / 纯文本文件）。 */
    var pending by remember { mutableStateOf<List<Attachment>>(emptyList()) }

    /** 附件相关的报错。与网络错误分开 —— 后者是 vm.banner，语义不同。 */
    var attachError by remember { mutableStateOf<String?>(null) }

    /** 全屏查看的图片：本条的全部图片 + 当前点开的那张。 */
    var viewingImage by remember { mutableStateOf<Pair<List<Attachment>, Attachment>?>(null) }

    /*
     * ⚠️⚠️ **输入状态必须按会话分开。**
     *
     * 上面那几个（草稿 / 待发附件 / 正在编辑的那条）原本是整屏一份的 `remember` ——
     * 切到别的会话，它们**全都跟过去** ✗。顺手按发送，内容就发进了另一个会话；
     * 更糟的是"正在编辑的那条"也跟过去 —— 那是会改错消息的。
     *
     * 做法：切换时**交换**一份（切走的存回它自己的会话、切进来的取出来）。
     * 是"留在各自的会话里"，不是"切走就清空" —— 草稿不该被吞掉。
     *
     * 这个 effect 声明在下面那个"切换会话"的 effect **之前**，所以它先跑，
     * 输入状态在溶解动画开始前就已经换好了。
     */
    val composerKey = vm.currentConversationId.orEmpty()
    val pendingByConversation = remember { mutableStateMapOf<String, List<Attachment>>() }
    val editingByConversation = remember { mutableStateMapOf<String, UiMessage?>() }
    val draftBeforeEditByConversation = remember { mutableStateMapOf<String, String>() }
    var composerOwner by remember { mutableStateOf<String?>(null) }

    /*
     * **要落库的那一份草稿。**
     *
     * 「修改输入」时输入框里是**编辑文本**，不是草稿 —— 存进去下次打开会变成一句
     * 没头没尾的话，而且它指向的消息也没了。所以编辑期间存的是编辑前那份。
     */
    fun draftToPersist(): String = if (editingMessage != null) inputBeforeEdit else input

    LaunchedEffect(vm.currentConversationId) {
        // ① 把上一份存回它自己的会话
        composerOwner?.let { previous ->
            vm.saveDraft(previous, draftToPersist())
            pendingByConversation[previous] = pending
            editingByConversation[previous] = editingMessage
            draftBeforeEditByConversation[previous] = inputBeforeEdit
        }
        // ② 取来这一份：草稿走库（进程被杀也还在），其余是瞬时状态、留在内存
        input = vm.loadDraft(composerKey).orEmpty()
        pending = pendingByConversation[composerKey].orEmpty()
        editingMessage = editingByConversation[composerKey]
        inputBeforeEdit = draftBeforeEditByConversation[composerKey].orEmpty()
        composerOwner = composerKey

        // ③ 这些是"指向某条消息"的瞬时状态 —— 跨会话留着是危险的，一律清掉
        aiEditTarget = null
        deleteTarget = null
        viewingImage = null
        attachError = null
        panel = InputPanelState.NONE
    }

    /*
     * 打字时**防抖落库**：停手 400ms 才写一次，与「会话标题」那条同档。
     *
     * ⚠️ 只在**换会话已经完成**之后才写 —— 换会话那一瞬间输入框里还是上一个会话
     * 的内容，而 `composerKey` 已经是新的了，这时候写会把别人的草稿覆盖掉。
     * 所以认 `composerOwner`（= 换会话 effect 收尾时才更新）。
     */
    LaunchedEffect(composerKey) {
        snapshotFlow { draftToPersist() }
            .collectLatest {
                if (composerOwner != composerKey) return@collectLatest
                delay(DraftSaveDelayMs)
                // 延迟期间可能又打了字，落库取**当下**这一份
                vm.saveDraft(composerKey, draftToPersist())
            }
    }

    /*
     * 退到后台 / 离开本页**立刻存一次**。
     *
     * 防抖最坏会丢最后 400ms，而"进程被杀"往往就发生在退到后台那一刻 ——
     * 这一下是真正兜底的那次。
     */
    val draftScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        fun flush() {
            draftScope.launch { composerOwner?.let { vm.saveDraft(it, draftToPersist()) } }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) flush()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flush()
        }
    }

    /** 收起「修改输入」并还原编辑前的草稿。 */
    fun cancelEdit() {
        editingMessage = null
        input = inputBeforeEdit
        pending = emptyList()
    }

    /*
     * 返回键：**先收界面上的临时状态，最后才轮到退出 App**。
     *
     * 写在最后的优先级最高（`OnBackPressedDispatcher` 是后添加的先响应），
     * 所以顺序是：面板 < 消息索引 < 修改输入 < AI 回复编辑弹窗。
     *
     * ⚠️ 这几条不能省。Compose 的返回事件**没人消费时会一路落空到
     * Activity 的 `finish()`** —— 用户开着「对话设置」面板随手按个返回，
     * App 就直接没了。抽屉那边是同一个坑（见 `RootScreen` 的注释）。
     */
    /** 消息索引的手势状态（§34）—— 左滑跟手，从右侧滑出。 */
    val messageIndex = rememberMessageIndexDragState()
    BackHandler(enabled = panel != InputPanelState.NONE) { panel = InputPanelState.NONE }
    BackHandler(enabled = messageIndex.isOpen) { messageIndex.collapse() }
    BackHandler(enabled = editingMessage != null) { cancelEdit() }
    BackHandler(enabled = aiEditTarget != null) { aiEditTarget = null }



    // ── 导出当前对话 ──────────────────────────────────────
    val scope = rememberCoroutineScope()
    var exportHint by remember { mutableStateOf<String?>(null) }

    /** 两个导出口共用：拿到目标位置后写文件。 */
    suspend fun exportTo(uri: Uri, markdown: Boolean) {
        val text = if (markdown) {
            vm.exportCurrentConversationMarkdown()
        } else {
            vm.exportCurrentConversation()
        }
        if (text == null) {
            exportHint = Res.get(R.string.chat_export_empty)
            return
        }
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray())
                } ?: error(Res.get(R.string.error_write_failed))
            }.isSuccess
        }
        exportHint = if (ok) Res.get(R.string.export_done) else Res.get(R.string.export_failed_short)
    }

    /** 导出文件名：会话标题去掉文件系统不认的字符。 */
    fun exportFileName(extension: String): String {
        val base = vm.conversationTitle()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            // 40 个字符足够放下绝大多数标题；截断后把悬空的左括号/空白收掉，
            // 免得出现「…（副本 .md」这种切一半的名字
            .take(40)
            .trim()
            .trimEnd('（', '(', '[', '<', ' ', '·', '-', '_')
            .ifBlank { "conversation" }
        return "$base.$extension"
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch { exportTo(uri, markdown = false) }
    }

    val exportMarkdownLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri != null) scope.launch { exportTo(uri, markdown = true) }
    }

    // ── 附件 ──────────────────────────────────────────────
    //
    // 三个入口都用系统能力，且**都不需要申请任何权限**：
    // - 拍照走 ACTION_IMAGE_CAPTURE + FileProvider（相机 App 自己持有相机权限）
    // - 图片走 PickVisualMedia（系统相册选择器）
    // - 文件走 OpenDocument
    // 刻意不申请 READ_MEDIA_IMAGES 之类的权限 —— 为了一个附件功能
    // 弹一堆系统授权，性价比太差。

    /** 附件读取失败/超限时的统一反馈。 */
    fun onImportFailed(message: String?) {
        attachError = message ?: Res.get(R.string.attach_read_failed)
    }

    /*
     * 相机要往一个 URI 里写，所以得先在启动前把它建好、记下来，
     * 回来时才知道去读哪个文件（`TakePicture` 的回调只给一个 Boolean）。
     */
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    val cameraPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = captureUri
        captureUri = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (!ok) {
            // 用户取消了拍摄 —— 中转文件已经在磁盘上，顺手清掉
            AttachmentImport.discardCapture(context, uri)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            AttachmentImport.image(context, uri)
                .onSuccess { pending = pending + it }
                .onFailure { onImportFailed(it.message) }
            AttachmentImport.discardCapture(context, uri)
        }
    }

    fun pickCamera() {
        val uri = runCatching { AttachmentImport.newCaptureUri(context) }.getOrNull()
        if (uri == null) {
            onImportFailed(Res.get(R.string.attach_camera_file_failed))
            return
        }
        captureUri = uri
        cameraPicker.launch(uri)
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            AttachmentImport.image(context, uri)
                .onSuccess { pending = pending + it }
                .onFailure { onImportFailed(it.message) }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            /*
             * 一个入口认三种东西：PDF（渲染成图）、docx（抽文本）、
             * 以及其余一律按纯文本试。格式按**文件头**判定，不看扩展名。
             */
            AttachmentImport.document(context, uri)
                .onSuccess { result ->
                    pending = pending + result.attachments
                    // 有损的地方要说出来（比如 PDF 只带了前 12 页）
                    result.note?.let { attachError = it }
                }
                .onFailure { onImportFailed(it.message) }
        }
    }

    /** 打开文件选择器。MIME 过滤刻意放到最宽，真正的把关在 `AttachmentImport.text`。 */
    fun pickFile() {
        /*
         * ⚠️ 这里刻意不用 MIME 过滤（传通配），而不是只挑 `text` 那一类。
         *
         * `.md` 在不同设备/提供者上被报成 `text/markdown`、`text/x-markdown`
         * 甚至 `application/octet-stream` —— 按 MIME 过滤会让用户在文件选择器里
         * **根本看不到自己的 md 文件**，那种"我的文件呢"比"选错了文件"
         * 更让人困惑。真正的把关放在 `AttachmentImport.text`：
         * 严格按 UTF-8 解码，二进制文件会直接报"看起来不是纯文本文件"。
         */
        filePicker.launch(arrayOf("*/*"))
    }

    /*
     * 滚动状态**按会话隔离**。
     *
     * 早先是一个 `rememberLazyListState()` 用到底。换会话时 LazyList 会按
     * "上一个会话的第一个可见条目**下标**"保持原位：新会话条数**更多**就停在
     * **中间**、**更少**就夹到**尾**、上一个恰好停在顶部就停在**头** ——
     * 这就是"有的从头、有的从尾、有的是随机中间"。
     *
     * ⚠️ 而且 `snapToBottomWhenReady()` 是**一次性**的：它跑完之后才发生那次重排，
     * 重排又把下标锚回去 —— 谁赢取决于时序，所以看起来是"随机"。
     * 换成按会话新建状态后起点恒为 0，"滚到底"就成了确定的事。
     *
     * 仍然用 `rememberSaveable`：`SaveableStateHolder` 靠它保住"从设置返回对话"的位置。
     */
    val listState = rememberSaveable(
        vm.currentConversationId,
        saver = LazyListState.Saver,
    ) {
        /*
         * ★ 下标给到"末尾之外"→ LazyList 布局时自动夹到最后一条 = **点进去就在底部**。
         * 官方同款（`ChatPageMessagesLoaded` 里用的是 `messages.size + 1`）。
         *
         * 这是**布局参数**，不是一次滚动：首帧就在底部，不需要测高度、不需要重试循环、
         * 不需要"贴底守卫"。之前 `ScrollToBottom.kt` 里写的"顶部为原点必须
         * scrollBy(超大) + 重试"是误诊 —— 那是"用滚动去追落点"的代价，不是方向的代价。
         */
        LazyListState(firstVisibleItemIndex = Int.MAX_VALUE)
    }

    /*
     * 消息索引的内容（§34.3）—— **快照**：只在会话或条数变化时重建，不在流式期间
     * 反复重算（§34.5）。预览文本在这里算完，别在组合里现算（每次重组跑一遍
     * `take(N)` 是白烧）。
     */
    val messageIndexEntries = remember(vm.currentConversationId, vm.messages.size) {
        buildMessageIndexEntries(vm.messages)
    }

    val density = LocalDensity.current
    /** 输入区实测高度（含系统栏内边距）—— 消息列表据此让出底部空间。 */
    var inputAreaHeight by remember { mutableStateOf(0) }
    val inputAreaPadding = with(density) { inputAreaHeight.toDp() }

    /** 正在切换会话。此时列表被整份替换，禁止切换逻辑之外的打扰（见下方切换 LaunchedEffect）。 */
    var switchingConversation by remember { mutableStateOf(false) }

    /**
     * 路径重建后要保住的位置：`(目标下标, 目标的末尾在屏上的 y, 目标的旧高度)`。
     *
     * "末尾"= 操作栏（`‹ n/m › 📋 🔄 ✏️ 🗑`）所在处 —— 用户对"在底部"的定义。
     * 摆回去见下方那个 `SideEffect` 与紧随其后的修正 effect。
     */
    var pathAnchor by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    /** 换会话期间列表还没就位 —— 用来决定要不要显示转圈。 */
    var loadingConversation by remember { mutableStateOf(false) }

    /**
     * 这个会话是不是"超长对话"（含单条两万字以上的消息）。
     *
     * 由切换 effect 在**读完库之后**判定 —— 切换那一刻 `vm.messages` 还是上一个会话的，
     * 提前判会判错。
     */
    var hugeConversation by remember { mutableStateOf(false) }

    /** 真正显示转圈。只有超长对话才置真。 */
    var showLoading by remember { mutableStateOf(false) }

    LaunchedEffect(loadingConversation, hugeConversation) {
        /*
         * **只有超长对话才转圈。**
         * 普通对话（哪怕很长）直接出内容 —— 首次布局对它们本来就是毫秒级的事，
         * 闪个圈反而像卡了一下（对齐官方）。
         */
        showLoading = loadingConversation && hugeConversation
    }

    /**
     * 路径正在重建 —— 用它挡住"追加新消息就跟随到底部"。
     *
     * 这些操作会重建整条路径（旧分支的下游让位给新分支的），**条数可能变**；
     * 但那不是"来了新消息"。重建后由下面那个 `SideEffect` 把视口摆到列表末尾
     * （= 操作栏），不需要跟底逻辑再插一脚。
     */
    var pathRebuilding by remember { mutableStateOf(false) }

    val tail = vm.messages.lastOrNull()
    val tailStreaming = tail?.streaming == true
    val tailGrowth = tail?.let { it.content.length + it.reasoning.orEmpty().length } ?: 0

    /*
     * ⚠️⚠️ **「内容增长」和「追加新消息」是两件事，处理方式完全相反** ——
     * 把它们混在一起，就是上面那个死循环。
     *
     * - **内容增长**（流式往同一条里写字）：贴底时是**结构性**的 —— 尾条底边钉住、
     *   往上长，新字自己冒出来，不需要滚动代码；上翻时由 `MessageList` 的
     *   **尾条高度补偿**把视口钉在内容上（长高 Δ 就把 offset 也加 Δ），
     *   也不需要在这里订阅内容变化。
     * - **追加新消息**：这个**不是**结构性的 ✗ —— 新条目落在下标 0，其余条目整体后移，
     *   而 Compose 会按 key 把"你原来在看的那条"钉在原处，于是视口**不会**跟到底部。
     *   实测表现：**发完消息停在上一条回复的结尾，回复来了也看不见**。
     *
     * 所以这里只补"追加"这一半，而且刻意做成**低频、有界**的：只在**条数**变化时滚一次。
     * 绝不订阅内容变化 —— 那才是之前那个每 80ms 转一圈的死循环。
     */
    var detached by remember { mutableStateOf(false) }

    /*
     * 「别跟底」的状态。
     *
     * 置位（谁要求别跟）有三个来源：
     *   ① 手指把视口拖离底部（`DragInteraction.Start`）
     *   ② 切版本 / 删除的锚点（`anchorOnPathChange`）—— 用户在看某一条，不在等新内容
     *   ③ 展开 / 收起折叠块（`onFoldToggle`）—— 同理，视口要留在被他点的那一行上
     *
     * ⚠️ **解禁不能写成"只要在底部就清掉"**（本版一度就是那样，是真 bug）。
     * ②③ 都发生在**贴底**的状态下，而布局要下一帧才落定 —— 设完标记的那一帧
     * `isAtBottom()` 仍然为真，"在底部就清"会把刚设的标记当场撤销，
     * 跟底随即把视口拽回列表末尾。实测现象就是**切中间那条的分支又跑到最底下**。
     *
     * 所以解禁只有三条**明确**的路：
     *   ① 用户的手势（含惯性）**停稳后落在底部** → 他回来了，继续跟
     *   ② 点「回到底部」（见下面那个按钮）
     *   ③ 发消息 / 重新生成 / 重试 / 编辑重发 / 新对话 ——"我要看新的"
     *
     * ⚠️ 判据也不能是下标：追加那一刻下标会整体后移，会把"本来贴着底"误判成"离开了"。
     *
     * ⚠️ 也不能用 `isScrollInProgress && !atBottom`（更早的写法）。它成立的前提是
     * 跟底**每帧都落到**底部 —— 误判一次下一帧就清回来了。跟底改成**逼近式**
     * （见 `ScrollToBottom.followBottom`）之后不再落底，一次误判就会**卡死**：
     * 新字全停在屏外，还得手动滑到底才恢复。
     * 只有手指拖动会产生 `DragInteraction`，代码滚（跟底 / 锚点 / 回到底部）都不会。
     */
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> detached = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    // 等这一次手势（含惯性滑动）彻底停下，再看落点是不是底部
                    snapshotFlow { listState.isScrollInProgress }.first { !it }
                    if (listState.isAtBottom()) detached = false
                }
                else -> Unit
            }
        }
    }

    /*
     * ★ 贴底跟随 —— 顶部为原点下**唯一**需要的滚动代码。
     *
     * 锚点钉顶边 → 长高不会推走已显示内容（所以**上翻阅读是稳的，零补偿**）。
     * 但**贴底时**新字长在视口**下方**，必须主动跟，否则它永远滚不出来 ——
     * 实测日志：落点是对的（`atBottom=true`），可内容一长高 `atBottom` 就变 false，
     * 而没有任何写入者，于是停在 `off=1158`，新字全在屏外。
     *
     * 官方同款（`StreamingAutoScrollEffect`）：逐帧循环，第一件事判
     * `isScrollInProgress` 让位，`detached`（用户上翻过）期间完全不跟。
     *
     * 这里的"晚一帧"是**无害**的：它补的是"把新长出来的字带进视口"，
     * 不是"钉住某个阅读位置" —— 晚一帧只是新字晚一帧出现，不会抖。
     * （这正是之前 `off += Δ` 失败、而这里可行的区别。）
     *
     * ⚠️ 一帧里做的是**逼近**，不是 `scrollBy(1e9)` 一把跳到位 —— 后者会把
     * "内容每 50ms 才来一批"直接暴露成"每 50ms 挪一个台阶"。取舍与实现在
     * `ScrollToBottom.followBottom`。
     */
    LaunchedEffect(listState) {
        while (true) {
            /*
             * 「贴底就粘住」：只要用户没主动上翻（`!detached`）、没在滚动、
             * 且下面真的还有内容（`canScrollForward`），就把它钉在末尾。
             *
             * 不只在流式时跑 —— 长消息的文字布局要好几帧才排出真实高度，
             * 只在流式时跑会漏掉"加载完但还在长"的那几帧（实测：第一次
             * atBottom=true 之后高度又长了一截，就再也没人跟了）。
             *
             * `pathRebuilding` / `switchingConversation` 期间让位 ——
             * 那两种是**故意**要把视口挪到别处去的，别和它们抢。
             */
            if (!detached && !pathRebuilding && !switchingConversation &&
                !listState.isScrollInProgress && listState.canScrollForward
            ) {
                // 向底部**逼近**一帧，不是一把跳过去 —— 见 ScrollToBottom.followBottom
                listState.followBottom()
            }
            withFrameNanos { }
        }
    }

    /*
     * 预热 Markdown 解析缓存（**后台线程**）。
     *
     * 解析改成同步之后（见 `MarkdownText` 的 MarkdownStateCache），一条长消息
     * 第一次组合时会在**主线程**逐块解析 —— 这就是"加载时卡一点点"的来源。
     * 这里在列表一变就把各条的块提前解析好，条目真正组合时只命中缓存。
     *
     * **从最新一条往前**：贴底打开时先看到的正是尾部那几条。
     */
    LaunchedEffect(vm.messages.size) {
        val contents = vm.messages.asReversed().map { it.content }
        withContext(Dispatchers.Default) {
            contents.forEach { warmMarkdownCache(it) }
        }
    }

    /** 追加新消息后跟到底部（用户没离开时）。 */
    LaunchedEffect(vm.messages.size) {
        if (switchingConversation) return@LaunchedEffect
        if (detached) return@LaunchedEffect
        /*
         * ⚠️ **路径重建不是"来了新消息"。**
         *
         * 切版本 / 重新生成 / 编辑并重发 / 重试都会重建整条路径（旧分支的下游
         * 让位给新分支的），条数因此可能变 —— 但用户是在看/对比某一条，
         * 不是在等新内容。跟底会把他从那里甩走。
         *
         * 这个 effect 声明在下面那个"摆回锚点"的 effect **之前**，所以先跑，
         * 也就能先看到这个标记（两个 effect 在同一帧按声明顺序启动）。
         */
        if (pathRebuilding) return@LaunchedEffect
        listState.scrollToBottom()
    }

    /*
     * 键盘弹起会抬高输入栏 → 列表底部留白跟着变大 → 整块正文被顶上去。
     *
     * **贴底时这是想要的**（最后一条要让出输入栏的位置）；但用户上翻读上文时
     * 不该被顶 —— 对齐官方：非贴底状态下正文不随键盘抬高。
     *
     * ⚠️ **补偿必须和留白变化落在同一帧**，否则会闪一下：
     * 留白变大那一帧内容先被顶上去、下一帧才被拉回来 —— 一帧的整屏位移，
     * 看起来就是闪。
     *
     * 所以用 `SideEffect` + `requestScrollToItem` 这一对：
     * - `SideEffect` 在**组合之后、布局之前**跑（`LaunchedEffect` 要等下一帧）
     * - `requestScrollToItem` 在**同一次测量**里生效，且不是动画
     *
     * 于是"被顶上去"的那一帧压根不会被画出来。
     */
    val padPx = inputAreaHeight
    var lastPadPx by remember { mutableStateOf(-1) }
    val padDelta = if (lastPadPx >= 0) padPx - lastPadPx else 0
    SideEffect {
        lastPadPx = padPx
        // 首次测量只记基线（0 → 实测值那一跳不是键盘引起的）
        if (padDelta == 0) return@SideEffect
        /*
         * ★ 顶部为原点：底部留白变化**不推**内容。
         *
         * 锚点是"最上面那条可见项的顶边"，它和底部留白无关 —— 非贴底时什么都不用做。
         * 只有贴底时要主动跟：留白变大 = 内容末尾离视口底更远 = 最后一条会被输入栏盖住，
         * 得重新贴到新的末尾。
         *
         * `isAtBottom()` 在这里读到的是**上一次布局**的结果 = 变化前的状态，正是要的。
         */
        if (listState.isAtBottom()) {
            scope.launch { listState.scrollToBottom() }
        }
    }

    /*
     * 路径重建后**停在原处**。
     *
     * 锚是**正序下标**（位置，不是身份）：重建后那条消息（或顶替它的那条）仍在
     * 同一个位置上 —— 切版本换的是同父的兄弟、重新生成加的是兄弟、编辑重发同理、
     * 删除则由下面那条顶上，深度都不变，它前面的那些一条没动。
     * 所以只需用**新的** size 折算成倒序下标。
     *
     * ⚠️ **必须和重建落在同一帧。** 用 `SideEffect`（组合之后、布局之前跑）
     * + `requestScrollToItem`（同一次测量里生效），而不是
     * `LaunchedEffect` + `scrollToItem`（要等下一帧）。这与键盘补偿是同一个坑，
     * 解法也一样（见上方那段 SideEffect 的说明）。
     *
     * 晚一帧为什么会"乱飞"：重建后条数往往变小（切版本会把旧分支的下游整段丢掉），
     * 而 LazyList 记的是**旧的倒序下标** —— 条数一变，那个下标已经不是这条消息了
     * （被切的那条会整体前移）。于是先按错的下标画出一帧、下一帧才被拉回来，
     * 看起来就是"窜一下 / 乱飞"。同一帧摆回去，这一帧压根不会被画出来。
     */
    val pathRev = vm.pathRevision
    val appliedPathRev = remember { intArrayOf(vm.pathRevision) }
    SideEffect {
        if (pathRev == appliedPathRev[0]) return@SideEffect
        appliedPathRev[0] = pathRev
        val anchor = pathAnchor
        if (anchor == null || vm.messages.isEmpty()) return@SideEffect
        val (target, endY, oldHeight) = anchor
        /*
         * **同一帧**先摆一次：用**旧高度**算偏移，让目标的末尾回到它原来的 y。
         *
         * ⚠️ 只在**目标顶边本来就在视口之上**时才能这么算：
         * `oldHeight - endY` 正是"顶边在视口上方多少"。目标比视口**短**时
         * （顶边本来就在屏上）这个值是**负**的 —— 而 `requestScrollToItem`
         * 只能表达"顶边在视口上方"，表达不了"在视口下方"。硬夹成 0 会退化成
         * "把顶边贴到视口顶边" = 往上跳。那种情况交给下面的相对修正。
         */
        val safe = target.coerceIn(0, vm.messages.size - 1)
        /*
         * ★ 第一帧只求**不越界**：把目标的顶边贴到视口顶边。
         *
         * 不能用"旧高度"去估偏移 —— 新版本比旧的短很多时，那个偏移会让目标
         * **整个跑到视口之上**，LazyList 只能钳到后面的条目上
         * （实测：请求 (7, 23658) 之后视口落到 item 8；`n` 还从 8 变 10 ——
         * 切到的兄弟版本自带下游）。
         *
         * 精确位置交给下面的修正 —— 那时才能读到目标的**新高度**。
         */
        listState.requestScrollToItem(safe, 0)
    }

    /*
     * `pathRebuilding` 的复位单独留在这里，而**不是**放进上面的 SideEffect。
     *
     * 上面那个"条数变化就跟到底部"的 effect 声明在前、同一帧先跑，
     * 它要靠 `pathRebuilding == true` 来跳过跟底。`SideEffect` 在应用阶段、
     * `LaunchedEffect` 在下一帧 —— 若在 SideEffect 里就把标记清掉，
     * 等 size effect 醒来时它已经是 false，于是又跟了一次底。
     */
    LaunchedEffect(vm.pathRevision) {
        pathRebuilding = false
        /*
         * ★ 路径重建的**精确修正**：新高度要等重建后那一帧的布局才知道，
         * 而 `SideEffect` 在布局**之前**跑 —— 同一帧只能用旧高度估。
         * 这里拿到新高度后把目标的末尾摆回它原来的 y。跑两帧，第二帧兜底。
         */
        val anchor = pathAnchor ?: return@LaunchedEffect
        val (target, endY, _) = anchor
        repeat(3) {
            withFrameNanos { }
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (info == null) {
                /*
                 * ★ 目标**完全不在视口里** —— 上一版就卡在这：第一帧的偏移越界，
                 * 视口落到了后面的条目上，修正从"可见条目"里找不到目标，
                 * 直接放弃，于是永远停在错的位置。
                 *
                 * 先把它拉进视口（顶边贴视口顶边），下一轮就能读到它的新高度。
                 */
                listState.scrollToItem(target, 0)
                return@repeat
            }
            val offset = (info.size - endY).coerceAtLeast(0)
            listState.scrollToItem(target, offset)
        }
    }

    /*
     * 切换会话：**直接落到最新一条** + 一次快速交叉溶解。
     *
     * 直接整份换内容会硬切一下（列表瞬间变样），快速连点会话时尤其明显。
     * 顺序是"先透明 → 等新内容就位 → 滚到底 → 再淡入"：换内容的那一瞬
     * 全程不可见，所以看起来就是一次溶解，而不是旧内容闪一下又变成新内容。
     *
     * 用 alpha 而不是 `Crossfade`：Crossfade 的出场分支会被重组，
     * 而两个分支读的是同一份实时列表 —— 淡出的那一份早就变成新内容了。
     */
    val dissolve = remember { Animatable(1f) }
    var previousConversationId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vm.currentConversationId) {
        val id = vm.currentConversationId
        val fromAnotherConversation = previousConversationId != null
        previousConversationId = id

        // 换会话 = 换了一份完全不同的内容，跟随状态一律重置
        pathAnchor = null
        pathRebuilding = false

        if (id == null) {
            switchingConversation = false
            dissolve.snapTo(1f)
            return@LaunchedEffect
        }

        /*
         * `switching` 兜个底：万一它没能回落（会话被删、任务被顶掉之类），
         * 消息区不能就这么停在透明状态 —— 那是"白屏"，比跳一下严重得多。
         */
        suspend fun awaitLoaded() = withTimeoutOrNull(SwitchLoadTimeoutMs) {
            snapshotFlow { vm.switching }.first { !it }
        }

        switchingConversation = true
        loadingConversation = fromAnotherConversation
        try {
            /*
             * 立刻透明，**不做淡出动画** —— 首次打开也一样。
             *
             * 顶部为原点之后，"落到最新一条"不再是布局参数一次到位的事：
             * `firstVisibleItemIndex = Int.MAX_VALUE` 只能夹到最后一条的**顶部**，
             * 而它的高度是逐帧才长到真实的。所以有一段"往上追"的过程，
             * 把它一起盖进透明里，用户就只看到一次干净的淡入。
             */
            dissolve.snapTo(0f)
            awaitLoaded()
            /*
             * 读完库之后再判定"超长" —— 此时 `vm.messages` 才是新会话的。
             */
            hugeConversation = (vm.messages.maxOfOrNull { it.content.length } ?: 0) > HugeMessageChars

            /*
             * ★ 首屏贴底必须**逐帧补**，不能只滚一次。
             *
             * 列表总高是随文字布局逐帧长到真实的：一次 `scrollBy(超大)` 只能夹到
             * **当时**已知内容的末尾，下一帧内容更高就又不到底了
             * （实测日志：滚完之后 fvi=9/off=0，停在最后一条的顶部）。
             * 所以在有界窗口内每帧补一次，直到真的贴底。
             */
            withTimeoutOrNull(BottomSettleMs) {
                // 等**稳定 3 帧**再收工：第一次 atBottom=true 不代表终点，
                // 长消息的文字布局还会再长几帧（实测差了一整截）。
                var stable = 0
                while (stable < 3) {
                    listState.scrollToBottom()
                    withFrameNanos { }
                    stable = if (listState.isAtBottom()) stable + 1 else 0
                }
            }
            dissolve.animateTo(1f, tween(SwitchFadeInMs, easing = MotionEasing))
        } finally {
            switchingConversation = false
            loadingConversation = false
        }
    }

    /** 收起键盘与焦点。 */
    fun dismissInput() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    /**
     * 记下**路径重建后要回到的位置**。只给「切版本 / 删除」用。
     *
     * 锚的是"**目标的下一条**"，目的是让目标的**操作栏（条目末尾）留在屏上** ——
     * 见函数体里的说明。
     *
     * 其余三个动作（重新生成 / 重试 / 编辑并重发）**一律不锚**：
     * 它们把这条换成一条**空的**流式回复，高度会先塌到十几像素
     * （实测 18355px → 12px），"原来的阅读位置"在新内容里根本不存在 ——
     * 硬锚只会把视口钳到内容末尾（往上跳）。而且用户按它们就是想看新回复，
     * 跟到底部才对。见各自调用点的说明。
     */
    fun anchorOnPathChange(message: UiMessage) {
        /*
         * ★ **锚点 = 发生操作那个操作栏在屏上的位置。**
         *
         * 用户对"在底部"的定义就是**操作栏可见**，而操作栏
         * （`‹ n/m › 📋 🔄 ✏️ 🗑`）就在条目**末尾**。所以这里记下
         * "目标末尾现在在屏上的 y"，重建后摆回原处。
         *
         * ⚠️ **不能锚"列表末尾"**：树模型下切到的兄弟版本**可能自己带着下游**
         * （在那个分支里继续聊过），目标就不一定是最后一条 —— 锚列表末尾会跳到
         * **对话末尾**，又是"乱跑"。
         *
         * ⚠️ **不能锚"目标的下一条"**：下游被丢掉时那条不存在，`coerceIn` 会把
         * 锚点夹回目标自己 → 视口被摆到目标的**顶边** → 超长消息"乱跑到很上面"。
         *
         * 只有"目标的末尾在屏上的 y"这一件事，对上面两种情况都成立。
         */
        val target = vm.messages.indexOfFirst { m -> m.id == message.id }
        val entry = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
        pathAnchor = if (target >= 0 && entry != null) {
            Triple(target, entry.offset + entry.size, entry.size)
        } else {
            null
        }
        pathRebuilding = true
        /*
         * ★ **必须摘掉自动跟随**（`detached`）。
         *
         * 跟底循环的目标是**列表末尾**。目标不是最后一条时（树模型下切到的
         * 兄弟版本可能自带下游），两者方向不一致 —— 跟底会把刚钉好的操作栏
         * 又拽到**对话末尾**。实测现象就是"切中间那条 → 跳到很下面"。
         *
         * 最后一条永远暴露不了这个问题（它的末尾就是列表末尾），所以只拿
         * 最后一条验证会漏掉。回到「回到底部」按钮再点一下即恢复跟随。
         */
        detached = true
    }

    /**
     * 「保存并发送」。
     *
     * **不需要二次确认**：树模型下旧的那条（连同它整棵子树）原样留在旧分支里，
     * 一条都不会丢。想回到旧分支，切一下版本就行。
     */
    fun requestResend(target: UiMessage, text: String, attachments: List<Attachment>) {
        // 编辑并重发 = 从这条起重建路径，下游换成一条空的流式回复 → 同「重新生成」，不锚
        detached = false
        vm.editMessage(target, text, resend = true, attachments = attachments)
    }

    /** 展开面板时收起键盘 —— 键盘与面板同时占屏会让输入区几乎没有空间。 */
    fun openPanel(target: InputPanelState) {
        dismissInput()
        panel = if (panel == target) InputPanelState.NONE else target
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.pageBackground),
    ) {
        ChatTopBar(
            title = vm.conversationTitle(),
            onMenu = {
                dismissInput()
                onOpenDrawer()
            },
            onNewChat = {
                dismissInput()
                onNewConversation()
                panel = InputPanelState.NONE
                // 新对话 = "我要看新的"，恢复跟随
                detached = false
            },
            // 点顶栏任意处也收键盘
            modifier = Modifier.dismissKeyboardOnTouch(),
        )

        /*
         * 消息区 + **浮在它上面的**输入区。
         *
         * 输入栏不占列表的位，而是盖在列表上方 —— 长文滚动时正文会从输入栏
         * 底下穿过去，而不是被一道硬边界截断。列表底部用等高的 contentPadding
         * 让出空间，最后一条仍然滚得出来。
         */
        Box(
            modifier = Modifier
                .weight(1f)
                .messageIndexDrag(messageIndex),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = dissolve.value }
                    // 点消息区空白处收键盘；不消费事件，气泡照常可点
                    .dismissKeyboardOnTouch()
            ) {
                if (vm.messages.isEmpty()) {
                    // 空态也要避开输入栏，否则"一起创作吧"会压在输入框上
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = inputAreaPadding),
                    ) {
                        EmptyState(modifier = Modifier.align(Alignment.Center))
                    }
                } else {
                    MessageList(
                        messages = vm.messages,
                        listState = listState,
                        /*
                         * 展开 / 收起**任何**折叠块（思考面板、超长用户气泡）时，
                         * 立刻停止自动跟底。
                         *
                         * 折叠块都在视口**下方**：跟底会马上把这一屏往上推，
                         * 于是"展开"这个动作先把**被点的那个按钮**顶走了 ——
                         * 用户点的就是它、视线也在它上面，它必须原地不动。
                         * 停下来之后新长出来的内容往**下**长，正是"掀开"的观感。
                         *
                         * 而且停下来是**持续的**：`detached` 只在用户重新滚回底部时
                         * 才被清掉（见跟底循环里那条恢复条件），所以展开后可以
                         * 慢慢往下读，不会被反复拽走。
                         *
                         * 反过来说，这也是"点开来看"与"等新内容"两种意图的分界：
                         * 点折叠 = 我在读已经有的东西；跟底只在流式那种
                         * "我在等新字"的场合才对。
                         */
                        onFoldToggle = { detached = true },
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = inputAreaPadding + 12.dp,
                        ),
                        onCopy = { message -> copyToClipboard(context, message.content) },
                        onRegenerate = { message ->
                            /*
                             * ⚠️ **重新生成不锚**，交给跟底。
                             *
                             * 它把这条换成一条**空的**流式回复 —— 高度会先塌到十几像素
                             * （实测 18355px → 12px），此时"原来的阅读位置"在新内容里
                             * 根本不存在，锚不住；硬锚只会把视口钳到内容末尾 = 往上跳。
                             *
                             * 而用户按「重新生成」就是想**看新回复**，跟到底部才对。
                             * （对照：「切版本」是看已完成的兄弟版本，那里才锚。）
                             */
                            detached = false
                            vm.regenerate(message)
                        },
                        onEdit = { message ->
                            if (message.role == MessageRole.USER) {
                                if (editingMessage == null) inputBeforeEdit = input
                                editingMessage = message
                                input = message.content
                                // 附件跟着进输入栏 —— 改错别字不该顺手把图丢了
                                pending = message.attachments
                                panel = InputPanelState.NONE
                            } else {
                                aiEditTarget = message
                            }
                        },
                        onDelete = { message -> deleteTarget = message },
                        onFork = { message -> vm.forkFrom(message) },
                        onVariantChange = { message, index ->
                            // 切版本也重建路径（旧分支下游让位）—— 锚住操作栏那一行，
                            // 换完视口不动，连点 `‹ ›` 才点得准。详见 anchorOnPathChange。
                            anchorOnPathChange(message)
                            vm.switchVariant(message, index)
                        },
                        onOpenImage = { message, attachment ->
                            viewingImage = message.attachments.filter { it.isImage } to attachment
                        },
                        onRetry = { message ->
                            // 同「重新生成」：换成一条空的流式回复 → 不锚，跟到底部看它写
                            detached = false
                            vm.retryMessage(message)
                        },
                    )
                }
            }

            /*
             * 回到底部。
             *
             * 只在离开底部时出现 —— 一直挂着会平白挡住正文，而它贴底时毫无用处。
             * 点它会**恢复自动跟随**：用户的意图显然是"我要看最新的"。
             */
            /*
             * 换会话时的白屏转圈。
             *
             * 只在"切换会话"且**等了一小会儿还没就位**时出现：短会话一闪而过，
             * 没必要闪个圈；长消息排版慢，这时给个明确的"在加载"，
             * 比让人看着列表自己往下滑要好。
             */
            if (showLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp),
                    strokeWidth = 3.dp,
                    color = LocalChatColors.current.processText,
                )
            }

            /*
             * 安卓原生的那种细滚动条：滚动时出现、停手淡出。
             *
             * 放在消息区这一层、贴右缘；输入栏浮在它上面，淡出后也不碍事。
             */
            VerticalScrollBar(
                state = listState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
            )

            val atBottom by rememberIsAtBottom(listState)
            BackToBottomButton(
                /*
                 * ★ 显隐判据里必须有 `detached`（用户主动离开底部），**不能只有 `!atBottom`**。
                 *
                 * `!atBottom` 在**跟随过程中**会以内容到达的节奏来回翻：每来一批字
                 * `canScrollForward` 变真 → 按钮冒出来；跟底把它吃掉 → 又变假 → 按钮消失。
                 * 表现就是"一触发跟底，按钮就不停闪烁"。
                 *
                 * `detached` 是**意图**而不是**位置**：跟随期间它一直是假，
                 * 所以跟底时这个按钮压根不存在 —— 正是要的。
                 * 留 `!atBottom` 只是为了避免"明明已经在底部还挂着一个回到底部"。
                 */
                visible = vm.messages.isNotEmpty() && detached && !atBottom,
                // 生成中在按钮外圈转一圈进度环；生成完自动消失
                generating = vm.isStreaming,
                onClick = {
                    // 用户显式要"看最新的" —— 这是少数几个该主动滚动的地方之一
                    detached = false
                    scope.launch { listState.scrollToBottom() }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = inputAreaPadding + 12.dp),
            )

            /*
             * 面板展开时，点消息区任意处即可关闭。
             *
             * 这层是**透明**的、只在面板展开时存在，并且会吃掉这一击 ——
             * 否则用户点空白处关面板，顺手还点开了底下那条消息。
             */
            if (panel != InputPanelState.NONE) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            dismissInput()
                            panel = InputPanelState.NONE
                        },
                )
            }

            /*
             * 输入区背后的"压底"。
             *
             * 正文滑到输入栏下面就该**消失**，而不是从输入栏四周的圆角、以及
             * 它下方那段空隙里露出来（实测露出来很脏）。这里垫一层
             * "透明 → 页面底色"的竖向渐变：正文是**淡出**的，而不是被一条硬边切断。
             *
             * 渐变带整体比输入栏顶边**下移 [BottomFadeDrop]**：正文多留一点实字，
             * 淡完的位置压到输入栏背后一点（尾段反正被输入栏盖住）。
             */
            if (inputAreaHeight > 0) {
                val fadeTotal = inputAreaPadding + BottomFadeHeight - BottomFadeDrop
                val fadeStop = (BottomFadeHeight / fadeTotal).coerceIn(0f, 0.98f)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(fadeTotal)
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Transparent,
                                    fadeStop to colors.pageBackground,
                                    1f to colors.pageBackground,
                                ),
                            ),
                        ),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // 先量高度再让位：消息列表的底部留白要跟它一致
                    .onSizeChanged { inputAreaHeight = it.height }
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                vm.banner?.let { message ->
                    BannerBar(message = message, onDismiss = { vm.dismissBanner() })
                }

                attachError?.let { message ->
                    BannerBar(message = message, onDismiss = { attachError = null })
                }

                // 待发送附件排在输入栏**上面**：先看到"我挂了什么"，再看到输入框
                PendingAttachmentsRow(
                    attachments = pending,
                    onRemove = { spec -> pending = pending - spec },
                    onOpen = { spec -> viewingImage = pending.filter { it.isImage } to spec },
                )

                InputBar(
                    text = input,
                    onTextChange = {
                        input = it
                        if (panel != InputPanelState.NONE) panel = InputPanelState.NONE
                    },
                    editingLabel = if (editingMessage != null) stringResource(R.string.msg_edit_input) else null,
                    onCancelEdit = { cancelEdit() },
                    // 面板展开时输入栏投影淡出，两块合看成一体
                    panelExpanded = panel != InputPanelState.NONE,
                    contextStats = vm.contextStats,
                    statsPanelOpen = panel == InputPanelState.STATS,
                    onOpenStatsPanel = { openPanel(InputPanelState.STATS) },
                    thinkingEnabled = vm.settings.thinkingEnabled,
                    thinkingPanelOpen = panel == InputPanelState.THINKING,
                    onOpenThinkingPanel = { openPanel(InputPanelState.THINKING) },
                    settingsPanelOpen = panel == InputPanelState.SETTINGS,
                    onOpenSettingsPanel = { openPanel(InputPanelState.SETTINGS) },
                    streaming = vm.isStreaming,
                    onSend = {
                        val target = editingMessage
                        val attached = pending

                        /*
                         * 模型不支持识图时**在这里拦住**，而不是让请求打到服务端换回 400。
                         * 400 的原文（如 "image_url is not supported"）对用户没有指导意义，
                         * 这里能直接告诉他"换个模型"。
                         */
                        if (attached.any { it.isImage } && !vm.settings.model.supportsVision) {
                            attachError = Res.get(R.string.chat_vision_unsupported, vm.settings.model.displayName)
                        } else {
                            /*
                             * 主动发消息 = "我要看新内容"：清掉"已离开底部"标记。
                             *
                             * ⚠️ **这里不能直接 `scrollToBottom()`** —— 消息是异步落库的，
                             * 滚动发出时它还没进列表，下标 0 还是上一条回复；等它进来时
                             * 下标整体后移，Compose 又按 key 把原条目钉住，于是人停在上条
                             * 回复的结尾（实测就是这个 bug）。
                             * 交给上面那个"条数变化才滚"的 effect —— 等它真的进列表再滚。
                             */
                            detached = false
                            if (target != null) {
                                // 输入栏的发送键就是「保存并发送」，不再让用户做二次选择
                                editingMessage = null
                                requestResend(target, input, attached)
                            } else {
                                vm.send(input, attached)
                            }
                            input = ""
                            pending = emptyList()
                            // 发出去的就不再是草稿 —— 立刻删行，不等防抖
                            draftScope.launch { composerOwner?.let { vm.clearDraft(it) } }
                            // 发完就收键盘：接下来是看回复，不是继续打字。
                            // 键盘占着屏幕会把刚发出的那条和开头几行回复一起顶掉。
                            dismissInput()
                        }
                    },
                    onStop = { vm.stop() },
                    // ⊕ 真的能用了：展开与其它面板同一个容器（浮在输入栏上方的卡片）
                    canAttach = true,
                    onAttachClick = { openPanel(InputPanelState.ATTACH) },
                    attachPanelOpen = panel == InputPanelState.ATTACH,
                    pendingCount = pending.size,
                    onTextFieldFocused = { panel = InputPanelState.NONE },
                )

                // ── 面板零：附件 ────────────────────────────────
                // 与下面三个面板**同一个容器**（浮在输入栏上方的卡片），
                // 不是 Material 的 ModalBottomSheet —— 那个满宽、自带蒙版，
                // 摆在应用里和输入栏对不齐，是另一套语言。
                InputPanel(
                    visible = panel == InputPanelState.ATTACH,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    AttachmentPanelContent(
                        onCapture = {
                            // 先收面板再拉起相机，否则拍完回来它还挂着
                            panel = InputPanelState.NONE
                            pickCamera()
                        },
                        onPickImage = {
                            // 先收面板再拉起系统选择器，否则选完回来它还挂着
                            panel = InputPanelState.NONE
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onPickFile = {
                            panel = InputPanelState.NONE
                            pickFile()
                        },
                    )
                }

                // ── 面板一：思考档位 + 模型 ─────────────────────
                InputPanel(
                    visible = panel == InputPanelState.THINKING,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    ThinkingPanelContent(
                        settings = vm.settings,
                        onSettingsChange = { vm.updateSettings(it) },
                        models = vm.models,
                        onRefreshModels = { vm.refreshModels() },
                        refreshing = vm.refreshingModels,
                        hint = vm.modelHint,
                    )
                }

                // ── 面板二：统计 + 上下文压缩 ───────────────────
                InputPanel(
                    visible = panel == InputPanelState.STATS,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    ContextPanelContent(
                        stats = vm.contextStats,
                        compaction = vm.compaction,
                        compacting = vm.compacting,
                        compactPreview = vm.compactPreview,
                        onCompact = { vm.compactContext() },
                        onCancelCompact = { vm.cancelCompaction() },
                        onClearCompaction = { vm.clearCompaction() },
                        onUpdateSummary = { vm.updateCompactionSummary(it) },
                    )
                }

                // ── 面板三：对话设置 ────────────────────────────
                InputPanel(
                    visible = panel == InputPanelState.SETTINGS,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    ConversationSettingsPanelContent(
                        settings = vm.settings,
                        onSettingsChange = { vm.updateSettings(it) },
                        presets = vm.presets,
                        conversationId = vm.currentConversationId,
                        title = vm.conversationTitle(),
                        /*
                         * 「新对话」也**可以**起名 —— 名字先当待用标题记下（§35），
                         * 建会话时一并落库，AI 起名那条路不再覆盖它。
                         */
                        onTitleChange = { vm.renameCurrentConversation(it) },
                        onExportMarkdown = if (vm.currentConversationId != null) {
                            {
                                exportHint = null
                                exportMarkdownLauncher.launch(exportFileName("md"))
                            }
                        } else {
                            null
                        },
                        onExportJson = if (vm.currentConversationId != null) {
                            {
                                exportHint = null
                                exportJsonLauncher.launch(exportFileName("json"))
                            }
                        } else {
                            null
                        },
                        exportHint = exportHint,
                    )
                }

                Spacer(Modifier.size(8.dp))
            }

            /*
             * 消息索引（§34）—— **盖在最上面**：索引开着的时候输入栏、回到底部、
             * 面板统统按不动，用户就是在找路。点遮罩即收。
             */
            MessageIndexOverlay(
                state = messageIndex,
                entries = messageIndexEntries,
                listState = listState,
                onJump = { entry ->
                    messageIndex.collapse()
                    scope.launch {
                        /*
                         * 跳转落点对齐**视口顶部**（§34.4）。
                         *
                         * 这里刻意**不走** §25.3 那套锚点：那套是给"列表被整份重建"
                         * 用的（切版本 / 删除），而跳转时列表没动，`scrollToItem` 就是
                         * 确定性的 —— 再套一层锚点反而是白绕。
                         */
                        listState.scrollToItem(entry.index, 0)
                        /*
                         * 跳走 = "我在读已经有的东西"，跟底要停（§34.4）。
                         * 但只在**真的离开底部**时停；跳到底部那条不该把跟随断掉。
                         */
                        if (!listState.isAtBottom()) detached = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // ── AI 回复编辑：只有这一处需要区分「保存」与「保存并发送」────

    aiEditTarget?.let { target ->
        AiEditDialog(
            initial = target.content,
            onDismiss = { aiEditTarget = null },
            onSave = { text ->
                aiEditTarget = null
                vm.editMessage(target, text, resend = false)
            },
            onSaveAndSend = { text ->
                aiEditTarget = null
                // AI 回复没有附件，照传（空列表）即可
                requestResend(target, text, target.attachments)
            },
        )
    }



    // ── 图片放大查看 ──────────────────────────────────────

    viewingImage?.let { (all, one) ->
        AttachmentViewerDialog(
            images = all,
            initial = one,
            onDismiss = { viewingImage = null },
        )
    }

    // ── 删除单条确认 ──────────────────────────────────────

    deleteTarget?.let { target ->
        AppConfirmDialog(
            title = stringResource(R.string.msg_delete_title),
            message = stringResource(R.string.msg_delete_body),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                // 删除同样重建路径：顶替它的那条会落在同一个位置上，锚住它
                anchorOnPathChange(target)
                vm.deleteMessage(target)
            },
        )
    }
}

/**
 * 「回到底部」悬浮按钮。
 *
 * 抽成独立组件而不是内联写：内联时它处在根 `Column` 的作用域里，
 * `AnimatedVisibility` 会解析到 **`ColumnScope` 的那个扩展**，
 * 而这里需要的是顶层版本（我们要的是"在 Box 里对齐"而不是"在 Column 里换行"）。
 */
@Composable
private fun BackToBottomButton(
    visible: Boolean,
    generating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) +
            scaleIn(initialScale = 0.85f, animationSpec = tween(180, easing = MotionEasing)),
        exit = fadeOut(tween(90)) +
            scaleOut(targetScale = 0.85f, animationSpec = tween(120)),
        modifier = modifier,
    ) {
        /*
         * 外层只负责"尺寸恒定为 32dp"。环画在它里面、但**不裁剪**，
         * 于是可以压到按钮边上；节点尺寸不变，环出现/消失时按钮落点一动不动。
         */
        Box(modifier = Modifier.size(32.dp)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .shadow(
                        elevation = AppShadow.RaisedElevation,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = AppShadow.RaisedAmbient,
                        spotColor = AppShadow.RaisedSpot,
                    )
                    .clip(CircleShape)
                    .background(colors.card)
                    .border(1.dp, colors.outline, CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.ArrowDown,
                    contentDescription = stringResource(R.string.chat_scroll_bottom),
                    tint = colors.processText,
                    modifier = Modifier.size(16.dp),
                )
            }

            /*
             * 环是**后面那个兄弟** → 画在按钮上层。
             * 这一条是"能压住按钮边缘"的前提，见 [GeneratingRing]。
             * 它没有手势修饰符，不会挡住按钮的点击。
             */
            if (generating) {
                GeneratingRing(Modifier.matchParentSize())
            }
        }
    }
}

/**
 * 进度环的几何。
 *
 * [RingOffset] 是环的**中心线**相对按钮边缘的外移量：
 * - `0` = 中心线正好压在边缘上（里侧一半压在按钮上、外侧一半露出来）
 * - 正值 = 往外让开（越大越松）
 * - 负值 = 往里压得更深
 *
 * 现在取 `0`：**不留空隙，且有一半压在按钮沿上** —— 环看着是"长在按钮边上"的。
 */
private val RingOffset = 0.dp
private val RingStroke = 2.dp

/** 进度环转一圈的时间。和 Material 的不确定进度环一个量级 —— 不慌不忙。 */
private const val RingSpinMs = 1100

/**
 * 生成中：一圈转动的进度环，画在按钮的**上层**。
 *
 * ### 两个几何上的坎
 *
 * 1. **节点尺寸必须是按钮那一份（32dp）。** 把盒子撑大再把按钮居中，盒子的右下角
 *    是钉在屏幕上的，按钮就会跟着往左上挪 —— 环一出现按钮跳一下。所以这里用
 *    `matchParentSize()`，环靠 `DrawScope` 默认**不裁剪**画到节点外面去。
 * 2. **必须画在按钮的后面那个兄弟位**（见调用处）。画在下面的话，按钮不透明的底
 *    和描边会把环压住，"压着按钮边缘"就做不到，只能往外让 —— 看着就是"有空隙"。
 *
 * 画法是「一圈淡色轨道 + 一段约 90° 的实色弧」：轨道让"环"始终完整，
 * 弧负责转。只画弧的话视觉上是一小段线在绕圈，读起来不像"进度"。
 */
@Composable
private fun GeneratingRing(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "generatingRing")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(RingSpinMs, easing = LinearEasing)),
        label = "generatingRingAngle",
    )
    val density = LocalDensity.current
    val offset = with(density) { RingOffset.toPx() }
    val stroke = with(density) { RingStroke.toPx() }

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f + offset
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = color.copy(alpha = 0.18f),
            radius = radius,
            center = center,
            style = Stroke(width = stroke),
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** 复制到系统剪贴板。用平台 API 而不是 Compose 的剪贴板封装 —— 后者近年改过两轮签名。 */
private fun copyToClipboard(context: android.content.Context, text: String) {
    val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager ?: return
    manager.setPrimaryClip(android.content.ClipData.newPlainText("message", text))
}

/** 顶部错误条。可手动关闭，避免一直占据视线。 */
@Composable
private fun BannerBar(message: String, onDismiss: () -> Unit) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(SoftShape)
            .background(colors.capsuleSelected)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(6.dp))
        IconCircleButton(
            icon = AppIcons.Close,
            contentDescription = stringResource(R.string.chat_banner_close),
            onClick = onDismiss,
            size = 26.dp,
            iconSize = 15.dp,
            tint = colors.processText,
        )
    }
}
