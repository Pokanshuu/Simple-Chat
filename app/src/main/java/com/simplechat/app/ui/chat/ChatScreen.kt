package com.simplechat.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.simplechat.app.ui.settings.ConversationWallpaperEditor
import com.simplechat.app.ui.settings.toWallpaperState
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.simplechat.app.ui.theme.LocalBubbleBackdrop
import com.simplechat.app.ui.theme.rememberBubbleBackdrop
import androidx.compose.ui.graphics.layer.drawLayer
import com.simplechat.app.ui.theme.sharedSceneBackground
import com.simplechat.app.ui.theme.GlassForeground
import com.simplechat.app.ui.theme.needsLiveSurface
import com.simplechat.app.data.InterfaceControl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.nativeImageAdapter
import com.simplechat.app.data.imageAspectRatios
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.AttachmentImport
import com.simplechat.app.data.Res
import com.simplechat.app.data.userMessage
import com.simplechat.app.net.ApiStyle
import com.simplechat.app.ui.common.ReadAloudEnabled
import com.simplechat.app.ui.common.SoftShape
import com.simplechat.app.ui.common.AppConfirmDialog
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppShadow
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.LoadingIndicator
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.common.dismissKeyboardOnTouch
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.LocalWallpaper
import com.simplechat.app.ui.theme.drawWallpaper
import com.simplechat.app.ui.theme.floatingGlassBackground
import com.simplechat.app.ui.theme.rememberWallpaperBitmap
import com.simplechat.app.ui.theme.backdropSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxHeight

/** 输入栏下方最多同时展开一个面板。 */
private enum class InputPanelState { NONE, THINKING, STATS, SETTINGS, ATTACH, IMAGE_MODELS, IMAGE_RATIOS }

private data class MessagePathAnchor(val messageId: String, val offset: Int, val headRoom: Int)

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

/** 换会话时，慢过这么久才显示转圈 —— 短会话不该闪一下圈圈。 */
private const val SpinnerDelayMs = 120L

/** 等新会话内容就位的兜底上限。超过就当它已经好了，不能让界面停在透明态。 */
private const val SwitchLoadTimeoutMs = 2_000L

/** 打字时草稿落库的防抖间隔。与「会话标题」那条同档。 */
private const val DraftSaveDelayMs = 400L

/**
 * 输入栏**下面**那条渐变的高度（从屏幕底边往上量）。
 *
 * 只盖底部一条：正文滚到底就在这里化开。输入栏以上不铺任何遮罩 ——
 * 那会把正在读的字从中间洗掉。
 *
 * 36dp 那档太窄：深色页面上几乎看不出过渡，正文像被硬边切了一刀。
 * 放到 64dp 才有"化开"的感觉，也才够盖住输入栏圆角外那圈空隙。
 */
private val BottomFadeHeight = 64.dp

/**
 * 淡出带整体下移量：正文多留一点实字，贴着输入栏才淡完（尾段落在输入栏背后）。
 *
 * 取 0：羽化从**输入栏顶边**起算，不分走正文的可见高度。
 */
private val BottomFadeDrop = 0.dp

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
    onConfigureImageApi: () -> Unit = {},
    onConfigureApi: () -> Unit = {},
    constrainContentWidth: Boolean = false,
) {
    val colors = LocalChatColors.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val context = LocalContext.current

    var panel by remember { mutableStateOf(InputPanelState.NONE) }
    val inputState = remember { TextFieldState() }
    val input by remember { derivedStateOf { inputState.text.toString() } }
    fun replaceInput(text: String) {
        inputState.setTextAndPlaceCursorAtEnd(text)
    }
    var deleteTarget by remember { mutableStateOf<UiMessage?>(null) }
    var pendingDeleteGroups by remember(vm.currentConversationId) { mutableStateOf<List<MessageShareGroup>?>(null) }

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
    var fileUploads by remember { mutableStateOf(0) }

    var speechError by remember { mutableStateOf<String?>(null) }
    var voiceCancelling by remember { mutableStateOf(false) }
    var voiceTapMode by remember { mutableStateOf(false) }
    val voice = rememberVoiceInput()
    val officialAsr = vm.officialAsr
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(officialAsr) {
        voice.cancel()
        voiceCancelling = false
        voiceTapMode = false
    }
    // 音色来自设置：设置里换一个，回到对话页立即生效（见 SpeechOutput 的 applyVoice）
    val speaker = rememberSpeechOutput(
        vm.ttsVoiceName,
        vm.officialTts,
        vm.thirdPartyVoiceApi,
        vm.thirdPartyChatKey,
        vm.thirdPartyChatBaseUrl,
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    /*
     * 朗读入口是否显示：官方音色只在拿到官方配置时可用（官方 /v1/tools/tts 只在
     * 官方域提供）。不可用时**整颗按钮不出现** —— 用户要求：不支持的 API 直接隐藏。
     */
    val readAloudAvailable =
        ReadAloudEnabled && vm.readAloudEnabled &&
            (!com.simplechat.app.net.DeepSeekTts.isOfficialVoice(vm.ttsVoiceName) || vm.officialTts != null)

    LaunchedEffect(readAloudAvailable) { if (!readAloudAvailable) speaker.stop() }
    AutoReadAloudEffect(vm, readAloudAvailable, speaker::read, speaker::stop)

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) speechError = Res.get(R.string.voice_permission_denied)
    }

    LaunchedEffect(speaker.error) {
        speaker.error?.let {
            speechError = it
            speaker.consumeError()
        }
    }
    // 开始念新的一段时，上一次的朗读报错就该收掉了（失败会重新写一次）
    LaunchedEffect(speaker.speakingId) {
        if (speaker.speakingId != null) speechError = null
    }

    /** 全屏查看的图片：本条的全部图片 + 当前点开的那张。 */
    var viewingImage by remember { mutableStateOf<Triple<List<Attachment>, Attachment, Int>?>(null) }
    /** 正在看的是不是待发送附件（只有它们能裁剪）。 */
    var viewingPending by remember { mutableStateOf(false) }
    /** 正在裁剪的待发送附件 + 解出来的位图。 */
    var cropping by remember { mutableStateOf<Pair<Attachment, android.graphics.Bitmap>?>(null) }

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
    var composerExpanded by remember(composerKey) { mutableStateOf(false) }
    val pendingByConversation = remember { mutableStateMapOf<String, List<Attachment>>() }
    val imagePromptByConversation = vm.imageDrafts
    val imageFieldState = remember { TextFieldState() }
    var imageMode by rememberSaveable(composerKey) { mutableStateOf(false) }
    var imageAspectRatio by rememberSaveable(composerKey) { mutableStateOf<String?>(null) }
    var pickedImageModel by rememberSaveable(composerKey) { mutableStateOf<String?>(null) }
    val referenceImages = remember(vm) { mutableStateMapOf<String, List<Attachment>>() }
    var referencePickerOwner by rememberSaveable { mutableStateOf<String?>(null) }
    data class ComposerEditDraft(val target: UiMessage, val text: String, val previousDraft: String)
    val editingByConversation = remember { mutableStateMapOf<String, ComposerEditDraft>() }
    var composerOwner by remember { mutableStateOf<String?>(null) }

    /*
     * **要落库的那一份草稿。**
     *
     * 「修改输入」时输入框里是**编辑文本**，不是草稿 —— 存进去下次打开会变成一句
     * 没头没尾的话，而且它指向的消息也没了。所以编辑期间存的是编辑前那份。
     */
    fun draftToPersist(): String = if (editingMessage != null) inputBeforeEdit else input

    LaunchedEffect(vm.currentConversationId) {
        voice.cancel()
        voiceCancelling = false
        voiceTapMode = false
        // ① 把上一份存回它自己的会话
        composerOwner?.let { previous ->
            val draft = draftToPersist()
            pendingByConversation[previous] = pending
            imagePromptByConversation[previous] = imageFieldState.text.toString()
            val target = editingMessage
            if (target != null) {
                editingByConversation[previous] = ComposerEditDraft(target, input, inputBeforeEdit)
            } else {
                editingByConversation.remove(previous)
            }
            vm.saveDraft(previous, draft)
        }
        // ② 普通草稿走库，修改中的文字和它的目标一起留在内存。
        val edit = editingByConversation[composerKey]
        replaceInput(edit?.text ?: vm.loadDraft(composerKey).orEmpty())
        pending = pendingByConversation[composerKey].orEmpty()
        imageFieldState.setTextAndPlaceCursorAtEnd(imagePromptByConversation[composerKey].orEmpty())
        editingMessage = edit?.target
        inputBeforeEdit = edit?.previousDraft.orEmpty()
        composerOwner = composerKey

        // ③ 这些是"指向某条消息"的瞬时状态 —— 跨会话留着是危险的，一律清掉
        aiEditTarget = null
        deleteTarget = null
        viewingImage = null
        attachError = null
        panel = InputPanelState.NONE
    }

    LaunchedEffect(composerKey) {
        snapshotFlow { composerOwner to imageFieldState.text.toString() }.collect { (owner, draft) ->
            if (owner == composerKey) {
                if (draft.isBlank()) imagePromptByConversation.remove(composerKey)
                else imagePromptByConversation[composerKey] = draft
            }
        }
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
    DisposableEffect(lifecycleOwner) {
        fun flush() {
            draftScope.launch { composerOwner?.let { vm.saveDraft(it, draftToPersist()) } }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                voice.cancel()
                voiceCancelling = false
                voiceTapMode = false
                flush()
            }
            if (event == Lifecycle.Event.ON_RESUME) voice.refreshAvailability()
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
        replaceInput(inputBeforeEdit)
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
    BackHandler(enabled = imageMode && panel == InputPanelState.NONE && !composerExpanded) { imageMode = false }
    BackHandler(enabled = panel != InputPanelState.NONE) { panel = InputPanelState.NONE }
    BackHandler(enabled = messageIndex.isOpen) { messageIndex.collapse() }
    BackHandler(enabled = editingMessage != null) { cancelEdit() }
    BackHandler(enabled = composerExpanded && panel == InputPanelState.NONE) { composerExpanded = false }
    BackHandler(enabled = aiEditTarget != null) { aiEditTarget = null }
    BackHandler(enabled = voice.state.active) {
        voice.cancel()
        voiceCancelling = false
        voiceTapMode = false
    }



    // ── 导出当前对话 ──────────────────────────────────────
    val scope = rememberCoroutineScope()
    var exportHint by remember { mutableStateOf<String?>(null) }

    /** 两个导出口共用：拿到目标位置后写文件。 */
    suspend fun exportTo(uri: Uri, markdown: Boolean) {
        val id = vm.currentConversationId
        var saved = false
        var staged: java.io.File? = null
        try {
            if (id == null) { exportHint = Res.get(R.string.chat_export_empty); return }
            val file = java.io.File.createTempFile("chat-export-", ".tmp", context.cacheDir)
            staged = file
            withContext(Dispatchers.IO) {
                vm.exportConversationFile(file, id, markdown)
                com.simplechat.app.data.copyExportFile(file, {
                    context.contentResolver.openOutputStream(uri, "wt") ?: error(Res.get(R.string.error_write_failed))
                })
            }
            saved = true
            exportHint = Res.get(R.string.export_done)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            exportHint = Res.get(R.string.export_failed, error.userMessage())
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                staged?.delete()
                if (!saved) runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
        }
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
    // - 图片走 PickMultipleVisualMedia（系统相册选择器，可多选）
    // - 文件走 OpenMultipleDocuments（可多选）
    // 刻意不申请 READ_MEDIA_IMAGES 之类的权限 —— 为了一个附件功能
    // 弹一堆系统授权，性价比太差。

    /** 附件读取失败/超限时的统一反馈。 */
    fun onImportFailed(message: String?) {
        attachError = message ?: Res.get(R.string.attach_read_failed)
    }

    /*
     * 多选之后，"一次选择"不再等于"一个附件"，于是有三件事要在这里说清楚：
     *
     * ① **读到一个就贴一个**：十几张图等整批读完才出现，会让人以为没反应；
     * ② **一个失败不影响其余**：失败的攒到最后报一句，成功的照常留在待发区
     *    （"选错了 20 个里的 1 个"不该让另外 19 个白读）；
     * ③ **归属是发起那一刻的会话**：读 PDF 要好几秒，期间完全可能切走 ——
     *    直接写 `pending` 会把附件塞进**新会话**的待发区。
     */
    fun addPending(owner: String, added: List<Attachment>) {
        if (added.isEmpty()) return
        if (composerOwner == owner) {
            pending = pending + added
        } else {
            pendingByConversation[owner] = pendingByConversation[owner].orEmpty() + added
        }
    }

    /**
     * 逐条读一批 URI。
     *
     * [read] 统一成 `(附件列表, 有损说明)`：图片产出一条、PDF 一页一条、
     * docx 产出一条 —— 调用方不必为每种来源各写一遍循环与报错。
     */
    suspend fun importBatch(
        owner: String,
        uris: List<Uri>,
        read: suspend (Uri) -> Result<Pair<List<Attachment>, String?>>,
    ) {
        var failed = 0
        var firstFailure: Throwable? = null
        var note: String? = null

        for (uri in uris) {
            read(uri).onSuccess { (attachments, resultNote) ->
                addPending(owner, attachments)
                // 有损提示（如 PDF 只带了前 12 页）留第一条就够了
                if (note == null) note = resultNote
            }.onFailure {
                failed++
                if (firstFailure == null) firstFailure = it
            }
        }

        // 已经切去别的会话就不再打扰：这是"那一批"的事，不该弹到别人头上
        if (composerOwner != owner) return

        if (failed == 0) {
            note?.let { attachError = it }
            return
        }
        attachError = if (uris.size == 1) {
            firstFailure?.userMessage()
        } else {
            Res.get(R.string.attach_import_partial, failed, firstFailure?.userMessage().orEmpty())
        }
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
                .onSuccess { addPending(composerKey, listOf(it)) }
                .onFailure { onImportFailed(it.userMessage()) }
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
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val owner = composerKey
        scope.launch {
            importBatch(owner, uris) { uri ->
                AttachmentImport.image(context, uri)
                    .map { listOf(it) to (null as String?) }
            }
        }
    }

    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        val owner = referencePickerOwner
        referencePickerOwner = null
        if (owner == null || uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            for (uri in uris) {
                if (referenceImages[owner].orEmpty().size >= 3) break
                AttachmentImport.image(context, uri).onSuccess { image ->
                    val current = referenceImages[owner].orEmpty()
                    if (current.size < 3) referenceImages[owner] = current + image
                }.onFailure { error -> onImportFailed(error.userMessage()) }
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val owner = composerKey
        val source = vm.attachmentSettings
        scope.launch {
            if (source.useRemoteFiles) fileUploads++
            try {
            /*
             * 一个入口认三种东西：PDF（渲染成图）、docx（抽文本）、
             * 以及其余一律按纯文本试。格式按**文件头**判定，不看扩展名。
             */
            importBatch(owner, uris) { uri ->
                val imported = if (source.useRemoteFiles) com.simplechat.app.data.RemoteFileImport.document(context, uri, source)
                    else AttachmentImport.document(context, uri)
                imported.map { it.attachments to it.note }
            }
            } finally { if (source.useRemoteFiles) fileUploads-- }
        }
    }

    /** 打开文件选择器（可多选）。MIME 过滤刻意放到最宽，真正的把关在 `AttachmentImport`。 */
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
     *
     * 放到**后台线程**算：它要遍历整条路径（长会话几千条），而这件事与"首帧能看到
     * 消息"无关 —— 放在主线程上，正好和列表首次布局抢那一帧。
     */
    val messageIndexEntries by produceState(
        initialValue = emptyList<MessageIndexEntry>(),
        vm.currentConversationId,
        vm.messages.size,
    ) {
        value = buildMessageIndexSnapshot(vm.messages)
    }

    val density = LocalDensity.current
    val inputAreaHeight = remember { mutableStateOf(0) }
    val anchorTopSpace = remember(listState) { mutableStateOf(0) }
    val messagePadding = remember(density, inputAreaHeight, anchorTopSpace) {
        object : PaddingValues by PaddingValues(horizontal = 16.dp, vertical = 12.dp) {
            override fun calculateTopPadding() = with(density) { anchorTopSpace.value.toDp() } + 12.dp
            override fun calculateBottomPadding() = with(density) { inputAreaHeight.value.toDp() } + 12.dp
        }
    }

    /** 正在切换会话。此时列表被整份替换，禁止切换逻辑之外的打扰（见下方切换 LaunchedEffect）。 */
    var switchingConversation by remember { mutableStateOf(false) }

    val textSelection = remember(vm) { MessageTextSelection() }
    val shareSelection = remember(vm) { MessageShareSelection() }
    var deleteSelectionMode by remember(vm) { mutableStateOf(false) }
    val canDeleteMessages = !vm.isStreaming && !vm.switching
    // LazyColumn may measure its previous item provider before recomposition applies.
    // Keep the row indices and the messages they index in the same immutable snapshot.
    val renderedMessages = vm.messages.toList()
    val messageRows = messageListEntries(renderedMessages, shareSelection.active)
    var pathAnchor by remember(listState) { mutableStateOf<MessagePathAnchor?>(null) }

    /** 换会话期间列表还没就位 —— 用来决定要不要显示转圈。 */
    var loadingConversation by remember { mutableStateOf(false) }

    /** 真正显示转圈。 */
    var showLoading by remember { mutableStateOf(false) }

    /*
     * 转圈的时机：**慢过一小会儿才转**。
     *
     * 先前是"读完库之后才知道是不是超长对话"，可那时慢的地方已经过去了 ——
     * 用户看到的是一段没有反馈的空白。现在改成按**耗时**判：
     * 大多数会话几十毫秒就出来了，来不及转圈；真慢的（几百条消息、带图片）才会亮 ——
     * 这也正好是用户看到"侧边栏动画卡住"的那一段。
     */
    LaunchedEffect(loadingConversation) {
        if (!loadingConversation) {
            showLoading = false
            return@LaunchedEffect
        }
        delay(SpinnerDelayMs)
        showLoading = true
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
    var searchPlacementReset by remember(vm.currentConversationId) { androidx.compose.runtime.mutableLongStateOf(0L) }
    fun resumeFollowing() {
        vm.dismissSearch()
        searchPlacementReset++
        anchorTopSpace.value = 0
        detached = false
    }
    SideEffect { if (vm.searchTarget != null) detached = true }

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
                    if (listState.isAtBottom()) resumeFollowing()
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
        fun needsFollow() = !detached && vm.searchTarget == null && !vm.switching && !pathRebuilding &&
            !switchingConversation && !listState.isScrollInProgress && listState.canScrollForward
        while (true) {
            // Wake on layout growth or resumed following, not on every frame spent reading history.
            if (!needsFollow()) snapshotFlow { needsFollow() }.first { it }
            var previousFrame = 0L
            while (needsFollow()) {
                val frame = withFrameNanos { it }
                if (!needsFollow()) break
                val elapsed = if (previousFrame == 0L) 16_666_667L else frame - previousFrame
                previousFrame = frame
                listState.followBottom(elapsed)
            }
        }
    }

    /** 追加新消息后跟到底部（用户没离开时）。 */
    LaunchedEffect(vm.messages.size) {
        if (vm.searchTarget != null) return@LaunchedEffect
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

    // The branch control is at a fixed offset from the row top. Apply the placement
    // with the new item provider in its first measure, without a second visible scroll.
    val pathRev = vm.pathRevision
    val appliedPathRev = remember(listState) { intArrayOf(pathRev) }
    SideEffect {
        if (pathRev == appliedPathRev[0]) return@SideEffect
        appliedPathRev[0] = pathRev
        pathAnchor?.let { anchor ->
            val index = messageRows.indexOfFirst { it.key == anchor.messageId }
            if (index >= 0) {
                anchorTopSpace.value = anchor.headRoom
                listState.requestScrollToItem(index, anchor.headRoom - anchor.offset)
            }
        }
        pathAnchor = null
        pathRebuilding = false
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
        val enteredFromSearch = vm.searchTarget != null
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
            if (enteredFromSearch || vm.searchTarget != null) {
                dissolve.snapTo(1f)
                return@LaunchedEffect
            }


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
                    if (vm.searchTarget != null) break
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
    LaunchedEffect(vm.searchTarget?.request, vm.switching) {
        val target = vm.searchTarget ?: return@LaunchedEffect
        if (vm.switching || target.ready) return@LaunchedEffect
        detached = true
        focusManager.clearFocus()
        keyboard?.hide()
        val index = messageRows.indexOfFirst { it.key == target.messageId }
        if (index >= 0) {
            listState.scrollToItem(index)
            withFrameNanos { }
            vm.markSearchReady(target.request)
        }
    }

    /** 收起键盘与焦点。 */
    fun dismissInput() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    fun anchorOnPathChange(message: UiMessage, replacementId: String?, deleting: Set<String> = emptySet(),
                           visibleItems: List<androidx.compose.foundation.lazy.LazyListItemInfo> = listState.layoutInfo.visibleItemsInfo) {
        val targetKey = if (replacementId != null) messageBranchKey(message) else message.id
        val target = visibleItems.firstOrNull { it.key == targetKey }
        fun atOffset(id: String, offset: Int): MessagePathAnchor {
            // Let the list clamp the requested position to the new content bounds.
            // Padding below the anchor would keep the removed continuation as blank space.
            val baseOffset = offset + anchorTopSpace.value
            val viewportHeight = listState.layoutInfo.viewportSize.height
            return MessagePathAnchor(id, baseOffset, baseOffset.coerceIn(0, viewportHeight))
        }
        val anchor = if (target != null && replacementId != null) {
            atOffset(targetKey, target.offset)
        } else {
            val candidates = visibleItems.filter { it.key is String && it.key !in deleting &&
                vm.messages.any { row -> row.id == it.key && !row.deletedNavigation } }
            val neighbor = candidates.firstOrNull { it.index > (target?.index ?: -1) } ?: candidates.lastOrNull()
            neighbor?.let { atOffset(it.key as String, it.offset) }
        }
        pathAnchor = anchor
        pathRebuilding = true
        detached = true
    }

    /**
     * 「保存并发送」。
     *
     * **不需要二次确认**：树模型下旧的那条（连同它整棵子树）原样留在旧分支里，
     * 一条都不会丢。想回到旧分支，切一下版本就行。
     */
    fun requestResend(target: UiMessage, text: String, attachments: List<Attachment>, onPersisted: () -> Unit = {}) {
        // 编辑并重发 = 从这条起重建路径，下游换成一条空的流式回复 → 同「重新生成」，不锚
        resumeFollowing()
        vm.editMessage(target, text, resend = true, attachments = attachments, onPersisted = onPersisted)
    }

    /** 展开面板时收起键盘 —— 键盘与面板同时占屏会让输入区几乎没有空间。 */
    fun openPanel(target: InputPanelState) {
        composerExpanded = false
        dismissInput()
        panel = if (panel == target) InputPanelState.NONE else target
    }

    var excerpt by remember(vm.currentConversationId) { mutableStateOf<MessageShareDocument?>(null) }
    val shareMessages = vm.messages.toList()
    val visibleShareMessages = shareMessages.filterNot { it.deletedNavigation }
    val sharingGroups = remember(visibleShareMessages) { messageShareGroups(visibleShareMessages) }
    val deletionGroups = remember(visibleShareMessages) { visibleShareMessages.map { MessageShareGroup(it.id, listOf(it)) } }
    val shareGroups = if (deleteSelectionMode) deletionGroups else sharingGroups
    LaunchedEffect(vm.currentConversationId) {
        textSelection.dismiss()
        shareSelection.close()
        deleteSelectionMode = false
        pendingDeleteGroups = null
    }
    LaunchedEffect(canDeleteMessages) {
        if (!canDeleteMessages) {
            deleteTarget = null
            pendingDeleteGroups = null
            if (deleteSelectionMode) {
                shareSelection.close()
                deleteSelectionMode = false
            }
        }
    }
    LaunchedEffect(shareGroups) { if (shareSelection.active) shareSelection.reconcile(shareGroups) }
    BackHandler(enabled = shareSelection.active) {
        shareSelection.close()
        deleteSelectionMode = false
    }

    fun beginSelection(message: UiMessage, forDeletion: Boolean) {
        textSelection.dismiss()
        dismissInput()
        panel = InputPanelState.NONE
        messageIndex.collapse()
        detached = true
        vm.dismissSearch()
        deleteSelectionMode = forDeletion
        shareSelection.begin(message.id, if (forDeletion) deletionGroups else sharingGroups)
    }

    /*
     * 壁纸：只在聊天页画。解码放 IO，按最外层尺寸 cover 绘制（不拉伸变形）。
     * 设置里换图 / 删图时 file 变了会重新解码。
     */
    val conversationId = vm.currentConversationId
    val conversationWallpaper = conversationId?.let { id ->
        androidx.compose.runtime.key(id) {
            remember(vm, id) { vm.observeConversationWallpaper(id) }.collectAsStateWithLifecycle(initialValue = null).value
        }
    }
    var showWallpaperEditor by remember(conversationId) { mutableStateOf(false) }
    val wallpaper = conversationWallpaper?.toWallpaperState(context) ?: LocalWallpaper.current
    val wallpaperBitmap = rememberWallpaperBitmap(wallpaper)

    /*
     * 悬浮控件实时玻璃（docs/32 §32.3.3）：不支持 / 省电时不创建源图层。
     * 源里要垫「页面底色 + 壁纸」，壁纸按整个聊天页的尺寸和位置画，
     * 否则输入栏里透出来的壁纸会和屏幕上的错位。
     */
    val liveBlur = com.simplechat.app.ui.theme.rememberLiveBlurAvailable()
    val outlineVisible by remember(messageIndex) { derivedStateOf { messageIndex.revealed > 0f } }
    val glassSource = com.simplechat.app.ui.theme.rememberBackdropSource(
        liveBlur && (needsLiveSurface(InterfaceControl.INPUT, InterfaceControl.INPUT_PANEL, InterfaceControl.SCROLL_BUTTON) ||
            (outlineVisible && needsLiveSurface(InterfaceControl.MESSAGE_INDEX))),
    )
    val wallpaperLayer = if (wallpaperBitmap != null) rememberGraphicsLayer() else null
    val bubbleBackdrop = rememberBubbleBackdrop(wallpaperLayer)
    val wallpaperFade = remember(wallpaper.file) { Animatable(0f) }
    LaunchedEffect(wallpaper.file, wallpaperBitmap) {
        if (wallpaperBitmap != null) wallpaperFade.animateTo(1f, tween(280, easing = MotionEasing))
    }
    val pageFrame = remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    // Limit the reading column without shrinking the wallpaper or its acrylic sampling layer.
    val contentWidth = if (constrainContentWidth) Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 10.dp) else Modifier
    Column(
        modifier = modifier
            .fillMaxSize()
            .then(
                if (glassSource != null) Modifier.onGloballyPositioned { pageFrame.value = it.boundsInRoot() } else Modifier,
            )
            .background(colors.pageBackground)
            /*
             * 壁纸画在背景色之上、内容之下：`drawBehind` 就是这一层。
             *
             * 画面调节交给 Android 的 ColorMatrix（亮度 / 对比度 / 饱和度），
             * 适配方式与位置在绘制矩形里算 —— 用 nativeCanvas 是因为 Compose 的
             * `drawImage` 不接受 colorFilter，而重新编码一张位图来调色既慢又占内存。
             */
            .sharedSceneBackground(wallpaperLayer, alpha = { wallpaperFade.value }, bubbleBackdrop = bubbleBackdrop) { wallpaperBitmap?.let { drawWallpaper(it, wallpaper, colors.pageBackground) } }
            .dismissMessageSelectionOnTap(textSelection),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (shareSelection.active) {
            Box(contentWidth) {
                MessageShareTopBar(
                    shareSelection,
                    shareGroups,
                    onClose = {
                        shareSelection.close()
                        deleteSelectionMode = false
                    },
                    showClose = !deleteSelectionMode,
                )
            }
        } else ChatTopBar(
            title = vm.conversationTitle(),
            autoReadAloud = vm.autoReadAloud,
            readAloudAvailable = readAloudAvailable,
            onToggleAutoReadAloud = {
                if (vm.autoReadAloud) speaker.stop()
                vm.toggleAutoReadAloud()
            },
            onMenu = {
                dismissInput()
                onOpenDrawer()
            },
            onNewChat = {
                dismissInput()
                onNewConversation()
                panel = InputPanelState.NONE
                // 新对话 = "我要看新的"，恢复跟随
                resumeFollowing()
            },
            onDoubleTap = {
                detached = true
                scope.launch { listState.scrollToItem(0) }
            },
            // 点顶栏任意处也收键盘
            modifier = contentWidth.dismissKeyboardOnTouch(clearFocus = { !textSelection.isActive }),
        )

        val backdrop: @Composable () -> Unit = {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        Modifier.backdropSource(glassSource) {
                                drawRect(colors.pageBackground)
                                val frame = pageFrame.value
                                if (wallpaperLayer != null && glassSource != null) {
                                    val dx = frame.left - glassSource.originInRoot.x
                                    val dy = frame.top - glassSource.originInRoot.y
                                    translate(dx, dy) { drawLayer(wallpaperLayer) }
                                }
                        },
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = dissolve.value }
                        // 点消息区空白处收键盘；不消费事件，气泡照常可点
                        .dismissKeyboardOnTouch(clearFocus = { !textSelection.isActive })
                ) {
                    if (renderedMessages.isEmpty()) {
                        // 空态也要避开输入栏，否则"一起创作吧"会压在输入框上
                        EmptyState(
                            modifier = Modifier.fillMaxSize(),
                            bottomObstruction = { inputAreaHeight.value },
                        )
                    } else {
                        MessageList(
                            messages = renderedMessages,
                            rows = messageRows,
                            loadingLabelFor = vm::loadingLabelFor,
                            onStopImage = vm::stop,
                            loadingWordsUseThemeColor = vm.loadingWordsUseThemeColor,
                            textSelection = textSelection,
                            shareSelection = shareSelection,
                            onShareExcerpt = { message, selectedText ->
                                textSelection.dismiss()
                                dismissInput()
                                val quote = UiMessage(message.id, message.role, selectedText)
                                val footnote = buildList {
                                    add(Res.get(R.string.share_excerpt_date, java.time.LocalDate.now().toString()))
                                    if (message.role == MessageRole.ASSISTANT) add(Res.get(R.string.share_excerpt_ai))
                                }
                                excerpt = MessageShareDocument(
                                    Res.get(R.string.share_excerpt),
                                    listOf(MessageShareGroup(quote.id, listOf(quote))),
                                    footnote,
                                )
                            },
                            onShare = { beginSelection(it, forDeletion = false) },
                            onDeleteSelectionStart = if (!canDeleteMessages) null else
                                { message -> beginSelection(message, forDeletion = true) },
                            searchTarget = vm.searchTarget,
                            searchBottomObstructionPx = { inputAreaHeight.value },
                            searchPlacementReset = searchPlacementReset,
                            onDismissSearch = vm::dismissSearch,
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
                            contentPadding = messagePadding,
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
                                resumeFollowing()
                                vm.regenerate(message)
                            },
                            supportsContinuation = vm.supportsContinuation,
                            onContinue = { message ->
                                /*
                                 * 续写**跟底**：新内容接在这条末尾，用户按它就是想看接下来写什么。
                                 * 与「重新生成」同一个判断（那条把内容清空，锚不住）。
                                 */
                                resumeFollowing()
                                vm.continueMessage(message)
                            },
                            speakingId = if (readAloudAvailable) speaker.speakingId else null,
                            readAloudAvailable = readAloudAvailable,
                            autoCollapseThinking = vm.autoCollapseThinking,
                            onSpeak = { message ->
                                // 朗读正文（不是时间线里的过程文字）：用户要听的是回答本身
                                if (readAloudAvailable) speaker.toggle(message.id, message.content)
                            },
                            onEdit = { message ->
                                if (composerOwner == vm.currentConversationId.orEmpty()) {
                                    if (message.role == MessageRole.ASSISTANT) {
                                        aiEditTarget = message
                                    } else if (editingMessage?.id != message.id) {
                                        // 再次点中正在编辑的气泡时，保留输入栏里的修改稿。
                                        if (editingMessage == null) inputBeforeEdit = input
                                        editingMessage = message
                                        replaceInput(message.content)
                                        // 附件跟着进输入栏 —— 改错别字不该顺手把图丢了
                                        pending = message.attachments
                                        panel = InputPanelState.NONE
                                    }
                                }
                            },
                            onDelete = if (canDeleteMessages) ({ message -> deleteTarget = message }) else null,
                            onFork = { message -> vm.forkFrom(message) },
                            onVariantChange = { message, index ->
                                // 切版本也重建路径（旧分支下游让位）—— 锚住操作栏那一行，
                                // 内容够长时保留操作栏位置。短分支则按实际列表边界定位。
                                val visibleItems = listState.layoutInfo.visibleItemsInfo
                                vm.switchVariant(message, index) { anchorOnPathChange(message, it, visibleItems = visibleItems) }
                            },
                            onOpenImage = { message, attachment ->
                                val imageEntries = vm.messages.flatMap { item ->
                                    item.attachments.filter { it.isImage }.map { item.id to it }
                                }
                                val index = imageEntries.indexOf(message.id to attachment)
                                viewingImage = Triple(imageEntries.map { it.second }, attachment, index)
                            },
                            onChooseModel = { openPanel(InputPanelState.THINKING) },
                            onRetryWithoutAttachmentsAndTools = { message ->
                                resumeFollowing()
                                vm.retryMessage(message, omitAttachmentsAndTools = true)
                            },
                            onRetry = { message ->
                                // 同「重新生成」：换成一条空的流式回复 → 不锚，跟到底部看它写
                                resumeFollowing()
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
                    LoadingIndicator(
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
                 * 输入栏**下面**那一层渐变：只铺屏幕最底部的一条，正文滚到这里就化开。
                 *
                 * 输入栏以上**一律不铺** —— 之前那层从输入栏顶边往上羽化的带子，
                 * 会把正在读的那行字从中间洗掉，比硬边还难看。正文应该一直清晰到
                 * 输入栏的边，然后被输入栏自己盖住。
                 */
                    // 有壁纸时不铺：渐变终点是实色页面底，会在屏幕最底部压出一条白 / 黑带（docs/32 A5）
                    if (wallpaper.file == null) Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(BottomFadeHeight)
                            .background(
                                Brush.verticalGradient(
                                    /*
                                     * 起点不透明 0 而是 0.28，末段再收满：
                                     * 两段线性的渐变在中间那一段太"透"，正文走到这里
                                     * 会有半截字一直若隐若现。加中间站之后是**越往下越实**，
                                     * 而不是"忽然开始变淡"。
                                     */
                                    colorStops = arrayOf(
                                        0f to colors.pageBackground.copy(alpha = 0f),
                                        0.35f to colors.pageBackground.copy(alpha = 0.28f),
                                        0.68f to colors.pageBackground.copy(alpha = 0.68f),
                                        1f to colors.pageBackground,
                                    ),
                                ),
                            ),
                    )
            }
        }

        excerpt?.let { snapshot ->
            MessageShareTools(shareSelection, emptyList(), snapshot.title, excerpt = snapshot,
                onCloseExcerpt = { excerpt = null })
        }
        val composer: @Composable () -> Unit = {
            if (shareSelection.active && deleteSelectionMode) {
                MessageDeleteBottomBar(shareSelection.selectedIds.size, onCancel = {
                    shareSelection.close()
                    deleteSelectionMode = false
                },
                    onDelete = { pendingDeleteGroups = shareSelection.selected(shareGroups) })
            } else if (shareSelection.active) {
                MessageShareTools(shareSelection, shareGroups, vm.conversationTitle(),
                    modifier = Modifier.fillMaxWidth())
            } else Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                vm.retrySkipNotice?.let { notice ->
                    BannerBar(notice, vm::dismissRetrySkipNotice, vm::muteRetrySkipNotice)
                }
                vm.imageSkipNotice?.let { message ->
                    BannerBar(message = message, onDismiss = vm::dismissImageSkipNotice,
                        onNeverRemind = vm::neverRemindImageSkip)
                }
                vm.banner?.let { message ->
                    BannerBar(message = message, onDismiss = { vm.dismissBanner() })
                }

                attachError?.let { message ->
                    BannerBar(message = message, onDismiss = { attachError = null })
                }
                if (fileUploads > 0) Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LoadingIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.service_uploading), modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelMedium)
                }

                speechError?.let { message ->
                    BannerBar(message = message, onDismiss = { speechError = null })
                }

                // 待发送附件排在输入栏**上面**：先看到"我挂了什么"，再看到输入框
                if (!imageMode) PendingAttachmentsRow(
                    attachments = pending,
                    onRemove = { spec -> pending = pending - spec },
                    onOpen = { spec ->
                        val images = pending.filter { it.isImage }
                        viewingPending = true
                        viewingImage = Triple(images, spec, images.indexOf(spec))
                    },
                )

                fun submitComposer() {
                    val draftKey = composerOwner ?: return
                    if (draftKey != vm.currentConversationId.orEmpty()) return
                    val target = editingMessage
                    val attached = pending
                    val submitted = inputState.text.toString()
                    val clearPersistedInput = {
                        // Clear only the submitted draft, after the message transaction committed.
                        if (composerOwner == draftKey && editingMessage?.id == target?.id &&
                            inputState.text.toString() == submitted && pending == attached
                        ) {
                            composerExpanded = false
                            if (target != null) {
                                cancelEdit()
                            } else {
                                replaceInput("")
                                pending = emptyList()
                                draftScope.launch { vm.clearDraft(draftKey) }
                            }
                            dismissInput()
                        }
                    }

                    // Unsupported attachments are filtered for this turn; keep the text turn alive.
                    /*
                     * 主动发消息 = "我要看新内容"：清掉"已离开底部"标记。
                     *
                     * ⚠️ **这里不能直接 `scrollToBottom()`** —— 消息是异步落库的，
                     * 滚动发出时它还没进列表，下标 0 还是上一条回复；等它进来时
                     * 下标整体后移，Compose 又按 key 把原条目钉住，于是人停在上条
                     * 回复的结尾（实测就是这个 bug）。
                     * 交给上面那个"条数变化才滚"的 effect —— 等它真的进列表再滚。
                     */
                    resumeFollowing()
                    if (target != null) {
                        // 输入栏的发送键就是「保存并发送」，不再让用户做二次选择
                        requestResend(target, submitted, attached, clearPersistedInput)
                    } else {
                        vm.send(submitted, attached, clearPersistedInput)
                    }
                }

                val imageRoute = vm.attachmentSettings
                val imageConfig = imageRoute.imageGenApi
                val showImageEntry = imageConfig.enabled || imageConfig.nativeResponsesEnabled
                val imageModels = if (imageMode) {
                    if (imageConfig.enabled) {
                        val matchingCatalog = runCatching { imageConfig.connectionBase(imageRoute.baseUrl) }.getOrNull() == imageConfig.modelCatalogBase
                        (listOf(imageConfig.modelId) + imageConfig.modelIds.takeIf { matchingCatalog }.orEmpty())
                            .filter { it.isNotBlank() }.distinct().map { it to it }
                    } else {
                        (listOf(vm.settings.model) + vm.models).distinctBy { it.id }
                            .filter { imageRoute.nativeImageAdapter(it.id) != null }
                            .map { it.id to it.displayName }
                    }
                } else emptyList()
                val imageModel = imageModels.firstOrNull { it.first == pickedImageModel } ?: imageModels.firstOrNull()
                val imageAdapter = if (imageConfig.enabled) imageConfig.adapter else imageModel?.let { imageRoute.nativeImageAdapter(it.first) }
                val imageRatios = imageAspectRatios(imageAdapter, imageModel?.first)
                val imageReferences = referenceImages[composerKey].orEmpty()
                if (imageMode) PendingAttachmentsRow(
                    attachments = imageReferences,
                    onRemove = { referenceImages[composerKey] = imageReferences - it },
                    onOpen = { viewingImage = Triple(imageReferences, it, imageReferences.indexOf(it)) },
                )
                val canSubmitImage = imageModel != null && (imageConfig.enabled || imageRoute.hasKey)
                fun submitImage(prompt: String) {
                    val owner = composerOwner ?: return
                    if (owner != composerKey || !canSubmitImage || prompt.isBlank()) return
                    val ratio = imageAspectRatio?.takeIf { it in imageRatios }
                    resumeFollowing()
                    vm.generateImage(prompt, requestedAspectRatio = ratio, references = imageReferences,
                        requestedModelId = imageModel?.first) {
                        if (referenceImages[owner].orEmpty() == imageReferences) referenceImages.remove(owner)
                        if (imagePromptByConversation[owner] == prompt) imagePromptByConversation.remove(owner)
                        if (composerOwner == owner && imageFieldState.text.toString() == prompt) {
                            imageFieldState.setTextAndPlaceCursorAtEnd("")
                            composerExpanded = false
                            imageMode = false
                            dismissInput()
                        }
                    }
                }

                val canUseVoice = !vm.isStreaming && (if (imageMode) canSubmitImage else editingMessage == null) &&
                    composerOwner == composerKey && (officialAsr != null || voice.state.systemAvailable)
                fun startVoice(tapMode: Boolean): Boolean {
                    val imageVoice = imageMode
                    val field = if (imageVoice) imageFieldState else inputState
                    if (!canUseVoice || field.text.isNotEmpty() || voice.state.active) return false
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        return false
                    }
                    val owner = composerKey
                    speechError = null
                    val started = voice.start(officialAsr, onResult = { transcript ->
                        voiceTapMode = false
                        if (owner == vm.currentConversationId.orEmpty() && composerOwner == owner &&
                            imageMode == imageVoice && field.text.isEmpty() && (imageVoice || editingMessage == null)
                        ) {
                            if (imageVoice) {
                                field.setTextAndPlaceCursorAtEnd(transcript)
                                submitImage(transcript)
                            } else {
                                replaceInput(transcript)
                                submitComposer()
                            }
                        }
                    }, onError = {
                        voiceTapMode = false
                        speechError = it
                    })
                    if (started) {
                        voiceTapMode = tapMode
                        dismissInput()
                        panel = InputPanelState.NONE
                    }
                    return started
                }

                InputBar(
                    fieldState = if (imageMode) imageFieldState else inputState,
                    imageMode = imageMode,
                    sendAllowed = !imageMode || canSubmitImage,
                    imageControls = {
                        CapsuleButton(
                            text = imageModel?.second ?: stringResource(R.string.image_api_pick_model),
                            modifier = Modifier.weight(1f, fill = false),
                            trailingIcon = AppIcons.ChevronDown,
                            selected = panel == InputPanelState.IMAGE_MODELS,
                            onClick = { openPanel(InputPanelState.IMAGE_MODELS) },
                        )
                        CapsuleButton(
                            text = imageAspectRatio?.takeIf { it in imageRatios } ?: stringResource(R.string.image_aspect_ratio_label),
                            trailingIcon = AppIcons.ChevronDown,
                            selected = panel == InputPanelState.IMAGE_RATIOS,
                            enabled = imageRatios.isNotEmpty(),
                            onClick = { openPanel(InputPanelState.IMAGE_RATIOS) },
                        )
                    },
                    expanded = composerExpanded,
                    onToggleExpanded = if (composerOwner == composerKey) {
                        {
                            panel = InputPanelState.NONE
                            textSelection.dismiss()
                            composerExpanded = !composerExpanded
                        }
                    } else null,
                    editingLabel = when {
                        imageMode -> stringResource(R.string.image_generate_title)
                        editingMessage != null -> stringResource(R.string.msg_edit_input)
                        else -> null
                    },
                    onCancelEdit = {
                        if (imageMode) {
                            voice.cancel()
                            voiceTapMode = false
                            voiceCancelling = false
                            imageMode = false
                            panel = InputPanelState.NONE
                        } else cancelEdit()
                    },
                    // 面板展开时输入栏投影淡出，两块合看成一体
                    panelExpanded = panel != InputPanelState.NONE,
                    contextStats = vm.contextStats,
                    statsPanelOpen = panel == InputPanelState.STATS,
                    onOpenStatsPanel = { openPanel(InputPanelState.STATS) },
                    holdToTalkEnabled = canUseVoice && vm.holdToTalk,
                    voiceState = voice.state,
                    voiceLevel = { voice.level },
                    voiceSourceLabel = stringResource(
                        if (officialAsr != null) R.string.voice_source_official else R.string.voice_source_system,
                    ),
                    voiceCancelling = voiceCancelling,
                    voiceTapMode = voiceTapMode,
                    onVoiceHoldStart = { startVoice(tapMode = false) },
                    onVoiceHoldFinish = { send -> if (send) voice.finish() else voice.cancel() },
                    onVoiceSlide = { cancelling ->
                        voiceCancelling = cancelling
                        if (cancelling && voice.state.holding) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onVoiceTap = { if (voiceTapMode) voice.finish() else startVoice(tapMode = true) },
                    thinkingEnabled = vm.settings.thinkingEnabled || vm.defaultThinkingActive,
                    // 搜索也在这个面板里，开着就算激活（见 InputBar 的说明）
                    webSearchEnabled = vm.automaticWebSearch || (vm.supportsWebSearch && vm.webSearch),
                    thinkingPanelOpen = panel == InputPanelState.THINKING,
                    onOpenThinkingPanel = { openPanel(InputPanelState.THINKING) },
                    settingsPanelOpen = panel == InputPanelState.SETTINGS,
                    onOpenSettingsPanel = { openPanel(InputPanelState.SETTINGS) },
                    streaming = vm.isStreaming,
                    onSend = { if (imageMode) submitImage(imageFieldState.text.toString()) else submitComposer() },
                    onStop = { vm.stop() },
                    // ⊕ 真的能用了：展开与其它面板同一个容器（浮在输入栏上方的卡片）
                    canAttach = !imageMode || imageReferences.size < 3,
                    onAttachClick = {
                        if (imageMode) {
                            referencePickerOwner = composerKey
                            panel = InputPanelState.NONE
                            referencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } else openPanel(InputPanelState.ATTACH)
                    },
                    attachPanelOpen = panel == InputPanelState.ATTACH,
                    pendingCount = if (imageMode) 0 else pending.size,
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
                        onGenerateImage = if (showImageEntry) ({
                            panel = InputPanelState.NONE
                            textSelection.dismiss()
                            if (imageFieldState.text.isBlank() && editingMessage == null && inputState.text.isNotBlank()) {
                                imageFieldState.setTextAndPlaceCursorAtEnd(inputState.text.toString())
                            }
                            imageMode = true
                        }) else null,
                    )
                }

                InputPanel(imageMode && panel == InputPanelState.IMAGE_MODELS, Modifier.padding(top = 10.dp)) {
                    ImageOptionRows(
                        title = stringResource(R.string.image_api_pick_model),
                        options = imageModels,
                        selected = imageModel?.first,
                        onSelect = { pickedImageModel = it; panel = InputPanelState.NONE },
                    )
                    if (imageConfig.enabled || imageModels.isEmpty()) {
                        CapsuleButton(
                            stringResource(R.string.image_configure),
                            onClick = onConfigureImageApi,
                            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
                        )
                    }
                }
                InputPanel(imageMode && panel == InputPanelState.IMAGE_RATIOS, Modifier.padding(top = 10.dp)) {
                    ImageOptionRows(
                        title = stringResource(R.string.image_aspect_ratio_label),
                        options = listOf("" to stringResource(R.string.image_size_default)) + imageRatios.map { it to it },
                        selected = imageAspectRatio?.takeIf { it in imageRatios }.orEmpty(),
                        showAspectRatioIcons = true,
                        onSelect = { imageAspectRatio = it.ifEmpty { null }; panel = InputPanelState.NONE },
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
                        showWebSearch = vm.supportsWebSearch,
                        webSearchEnabled = vm.webSearch,
                        automaticWebSearch = vm.automaticWebSearch,
                        onConfigureApi = { panel = InputPanelState.NONE; onConfigureApi() },
                        onToggleWebSearch = { vm.toggleWebSearch() },
                        supportsExplicitThinkingOff = vm.apiStyle == ApiStyle.ANTHROPIC &&
                            com.simplechat.app.net.claudeAdaptiveModel(vm.settings.model.id)?.canDisableThinking != false,
                    )
                }

                // ── 面板二：统计 + 上下文压缩 ───────────────────
                InputPanel(
                    visible = panel == InputPanelState.STATS,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    ContextPanelContent(
                        stats = vm.contextStats,
                        usage = vm.lastUsage,
                        showUsageCost = com.simplechat.app.net.isOfficialDeepSeekEndpoint(vm.attachmentSettings.baseUrl),
                        compaction = vm.compaction,
                        compacting = vm.compacting,
                        compactionStatus = vm.compactionStatus,
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
                        officialPro = vm.settings.model.id == com.simplechat.app.net.DEEPSEEK_PRO &&
                            com.simplechat.app.net.isOfficialDeepSeekEndpoint(vm.attachmentSettings.baseUrl),
                        onSettingsChange = { vm.updateSettings(it) },
                        presets = vm.presets,
                        conversationId = vm.currentConversationId,
                        title = vm.conversationTitle(),
                        /*
                         * 「新对话」也**可以**起名 —— 名字先当待用标题记下（§35），
                         * 建会话时一并落库，AI 起名那条路不再覆盖它。
                         */
                        onTitleChange = { vm.renameCurrentConversation(it) },
                        memoryEnabled = vm.conversationMemoryEnabled.takeIf { vm.showConversationMemorySetting },
                        memoryToggleEnabled = !vm.switching,
                        onMemoryChange = vm::setConversationMemoryEnabled,
                        onWallpaperClick = if (conversationId == null || vm.switching) null else ({
                            dismissInput()
                            panel = InputPanelState.NONE
                            showWallpaperEditor = true
                        }),
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

        }

        val backToBottom: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize()) {
                // Outside the recorded scene: this button samples that scene itself.
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
                    visible = panel == InputPanelState.NONE && !shareSelection.active && vm.messages.isNotEmpty() && detached && !atBottom,
                    // 生成中在按钮外圈转一圈进度环；生成完自动消失
                    generating = vm.isStreaming,
                    onClick = {
                        // 用户显式要"看最新的" —— 这是少数几个该主动滚动的地方之一
                        resumeFollowing()
                        scope.launch {
                            // Remove the search-only padding before computing the normal content bottom.
                            withFrameNanos { }
                            listState.scrollToBottom()
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 12.dp)
                        .offset { IntOffset(0, -inputAreaHeight.value) },
                )
            }
        }
        val overlay: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize()) {
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
                            val rowIndex = messageRows.indexOfFirst { it.key == renderedMessages.getOrNull(entry.index)?.id }
                            if (rowIndex >= 0) listState.scrollToItem(rowIndex, 0)
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
        androidx.compose.runtime.CompositionLocalProvider(
            com.simplechat.app.ui.theme.LocalBackdropSource provides glassSource,
            LocalBubbleBackdrop provides bubbleBackdrop,
        ) {
        Layout(
            modifier = contentWidth
                .weight(1f)
                .then(if (shareSelection.active) Modifier else Modifier.messageIndexDrag(messageIndex)),
            contents = listOf(backdrop, backToBottom, composer, overlay),
        ) { (backdrop, backToBottom, composer, overlay), constraints ->
            val input = composer.single().measure(constraints.copy(minHeight = 0))
            val height = input.height
            val previousHeight = Snapshot.withoutReadObservation {
                inputAreaHeight.value
            }
            if (height != previousHeight) {
                // Reserve space and preserve the old bottom anchor in the same measure pass.
                // A reader who scrolled up keeps the visible message anchored to the top.
                Snapshot.withoutReadObservation {
                    if (height > previousHeight && previousHeight > 0 && !detached &&
                        vm.searchTarget == null && !vm.switching && !switchingConversation &&
                        listState.isAtBottom()
                    ) {
                        listState.advanceBottomForObstruction(height - previousHeight)
                    }
                }
                inputAreaHeight.value = height
            }
            val background = backdrop.single().measure(constraints)
            val scrollButton = backToBottom.single().measure(constraints)
            val foreground = overlay.single().measure(constraints)
            layout(background.width, background.height) {
                background.placeRelative(0, 0)
                scrollButton.placeRelative(0, 0)
                input.placeRelative(0, background.height - height)
                foreground.placeRelative(0, 0)
            }
        }
        }
    }
    if (showWallpaperEditor && conversationId != null) {
        ConversationWallpaperEditor(
            current = conversationWallpaper,
            onCommit = { vm.saveConversationWallpaper(conversationId, it) },
            onDismiss = { showWallpaperEditor = false },
        )
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

    viewingImage?.let { (all, one, index) ->
        AttachmentViewerDialog(
            images = all,
            initial = one,
            initialIndex = index,
            onDismiss = { viewingImage = null; viewingPending = false },
            onCrop = if (viewingPending) { target ->
                viewingImage = null
                viewingPending = false
                scope.launch {
                    val bitmap = com.simplechat.app.data.AttachmentImport.decodeImage(context, target)
                    if (bitmap != null) cropping = target to bitmap
                }
            } else null,
        )
    }

    // ── 待发送附件裁剪（通用裁剪器，docs/32 §32.6.5）──────────────

    cropping?.let { (target, bitmap) ->
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        com.simplechat.app.ui.common.ImageCropper(
            image = image,
            aspects = com.simplechat.app.ui.common.attachmentCropAspects(),
            onDismiss = { cropping = null },
            onConfirm = { rect ->
                cropping = null
                scope.launch {
                    val updated = com.simplechat.app.data.AttachmentImport.crop(
                        bitmap,
                        target,
                        android.graphics.Rect(Math.round(rect.left), Math.round(rect.top), Math.round(rect.right), Math.round(rect.bottom)),
                    )
                    pending = pending.map { if (it === target) updated else it }
                }
            },
        )
    }

    // ── 删除单条确认 ──────────────────────────────────────

    deleteTarget?.takeIf { canDeleteMessages }?.let { target ->
        AppConfirmDialog(
            title = stringResource(R.string.msg_delete_title),
            message = if (target.role == MessageRole.ASSISTANT) {
                stringResource(R.string.msg_delete_reply_body, stringResource(R.string.msg_delete_body))
            } else {
                stringResource(R.string.msg_delete_body)
            },
            confirmText = stringResource(R.string.msg_delete_all_versions),
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                vm.deleteMessage(target) { anchorOnPathChange(target, it, setOf(target.id)) }
            },
        )
    }

    pendingDeleteGroups?.takeIf { canDeleteMessages }?.let { selectedGroups ->
        AppConfirmDialog(
            title = stringResource(R.string.share_delete_title),
            message = stringResource(R.string.share_delete_warning, selectedGroups.size),
            confirmText = stringResource(R.string.share_delete_confirm),
            destructive = true,
            onDismiss = { pendingDeleteGroups = null },
            onConfirm = {
                val messageIds = selectedGroups.flatMap { it.messages }.map { it.id }.distinct()
                pendingDeleteGroups = null
                shareSelection.close()
                deleteSelectionMode = false
                vm.deleteSelectedVersions(messageIds, selectedGroups.size) {
                    selectedGroups.firstOrNull()?.messages?.firstOrNull()?.let { anchorOnPathChange(it, null, messageIds.toSet()) }
                }
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
internal fun BackToBottomButton(
    visible: Boolean,
    generating: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassForeground(InterfaceControl.SCROLL_BUTTON) {
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
                            ambientColor = AppShadow.FloatingAmbient,
                            spotColor = AppShadow.FloatingSpot,
                        )
                        .clip(CircleShape)
                        .floatingGlassBackground(colors.card, InterfaceControl.SCROLL_BUTTON)
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
internal fun BannerBar(message: String, onDismiss: () -> Unit, onNeverRemind: (() -> Unit)? = null) {
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
        if (onNeverRemind != null) TextButton(onClick = onNeverRemind) {
            Text(stringResource(R.string.images_never_remind), maxLines = 1)
        }
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


