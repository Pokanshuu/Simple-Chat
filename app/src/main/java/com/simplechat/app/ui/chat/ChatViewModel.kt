package com.simplechat.app.ui.chat

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simplechat.app.R
import com.simplechat.app.data.ApiKey
import com.simplechat.app.data.BackgroundGeneration
import com.simplechat.app.data.ImageRequest
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.Diagnostics
import com.simplechat.app.data.CompactionCheckpoint
import com.simplechat.app.data.compactionFingerprint
import com.simplechat.app.ui.markdown.warmMarkdownCache
import kotlinx.coroutines.Dispatchers
import com.simplechat.app.data.Res
import com.simplechat.app.data.nativeImageAdapter
import com.simplechat.app.data.imageAspectRatios
import com.simplechat.app.data.imageSizeForRatio
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.TITLE_MATERIAL_CHARS
import com.simplechat.app.data.TITLE_MAX_TOKENS
import com.simplechat.app.data.isAutoTitle
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.data.cleanGeneratedTitle
import com.simplechat.app.data.createLatestReminder
import com.simplechat.app.data.derivedTitle
import com.simplechat.app.data.titleSystemPrompt
import com.simplechat.app.data.userMessage
import com.simplechat.app.db.CompactionEntity
import com.simplechat.app.db.DraftEntity
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.ImageGenerationApi
import com.simplechat.app.net.RemoteModelMetadata
import com.simplechat.app.net.ModelCapabilityOverride
import com.simplechat.app.net.DeepSeekAsr
import com.simplechat.app.net.DeepSeekTts
import com.simplechat.app.net.ApiStyle
import com.simplechat.app.net.MessageContent
import com.simplechat.app.net.ModelCatalog
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.net.OutMessageDto
import com.simplechat.app.net.ProviderKind
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.anthropic.AnthropicRequest
import com.simplechat.app.net.headersFor
import com.simplechat.app.net.isOfficialDeepSeekEndpoint
import com.simplechat.app.net.serverErrorText
import java.util.UUID
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import com.simplechat.app.net.ImageToolCall
import com.simplechat.app.net.WebResearchSession
import com.simplechat.app.net.AgentRuntimeState
import com.simplechat.app.net.AgentSearchRoute
import com.simplechat.app.net.AgentToolOutcome
import com.simplechat.app.net.AgentToolStatus
import com.simplechat.app.net.diagnosticToken
import com.simplechat.app.net.effectiveModelId
import com.simplechat.app.net.AgentTranscript
import com.simplechat.app.net.agentLoop
import com.simplechat.app.net.ImageGenerationIntent
import com.simplechat.app.net.ImageToolOperation
import com.simplechat.app.net.blocksImageToolExecution
import com.simplechat.app.net.isExplicitImageRequest
import com.simplechat.app.net.requestsContextualImageEdit
import com.simplechat.app.net.imageGenerationIntent
import com.simplechat.app.net.mentionsRecentGeneratedImage

/**
 * 对话状态与流式调度。
 *
 * 数据流：
 * - **内存**中的 `messages` 是 UI 的唯一来源（流式增量只替换目标那一条）。
 * - **Room** 是持久层，按 §4.4 的策略**节流 checkpoint**：
 *   流中每约 1.5s 落一次盘，流结束/中断时终写一次。
 *   若每个 token 都回写 Room，一次 2000 字回复就是 2000 次磁盘事务。
 *
 * 其他关键点：
 * - **节流合并**：token 先进缓冲区，由独立 flusher 每 [STREAM_THROTTLE_MS] 合并提交一次
 *   （≈ 一帧一次，对齐官方 demo 的 rAF 节拍），避免每个 token 都触发列表重组（§8）。
 * - **不发送 `reasoning_content`**：本期不带 `tools`，官方明确无需回传（§11.1）。
 */
class ChatViewModel(
    private val chatApi: ChatApi,
    private val settingsStore: SettingsStore,
    private val repository: ChatRepository,
    private val openCodeSessionId: String,
    private val savedState: SavedStateHandle? = null,
) : ViewModel() {

    internal fun observeConversationWallpaper(id: String) = repository.observeConversationWallpaper(id)

    internal suspend fun saveConversationWallpaper(id: String, wallpaper: com.simplechat.app.data.ConversationWallpaper?) =
        repository.saveConversationWallpaper(id, wallpaper)

    val messages = mutableStateListOf<UiMessage>()
    internal val imageDrafts = mutableMapOf<String, String>()

    private var loadingLabel by mutableStateOf<Pair<String, String>?>(null)

    internal var loadingWordsUseThemeColor by mutableStateOf(true)
        private set

    internal fun loadingLabelFor(messageId: String): String? =
        loadingLabel?.takeIf { it.first == messageId }?.second

    /** 当前会话 id；为空表示尚未落库的新对话。 */
    private var activeConversationId by mutableStateOf<String?>(null)
    var currentConversationId: String?
        get() = activeConversationId
        private set(value) {
            activeConversationId = value
            savedState?.set(ActiveConversationStateKey, value)
        }

    /**
     * 当前会话标题（库里的值）。
     *
     * 顶栏**必须**用它，而不是从首条用户消息推导 —— 否则用户在抽屉里
     * 重命名之后，顶栏还显示旧标题，两边对不上。
     */
    var currentConversationTitle by mutableStateOf<String?>(null)
        private set

    /**
     * 「新对话」的**待用标题** —— 用户在发出第一条消息之前就起好的名。
     *
     * 那一刻会话行还不存在，库里没有地方写；这个名字与草稿同一个处境（§35），
     * 一起记在 `drafts` 表的 [DraftEntity.NewConversation] 行里，进程被杀也不丢。
     * [ensureConversation] 建会话时一并用上。
     */
    private var pendingTitle: String? = null
    private var pendingMemoryEnabled by mutableStateOf(true)
    private var openedMemoryEnabled by mutableStateOf(true)
    val showConversationMemorySetting: Boolean get() = stored.crossChatMemory
    val conversationMemoryEnabled: Boolean
        get() = if (currentConversationId == null) pendingMemoryEnabled else openedMemoryEnabled

    init {
        viewModelScope.launch {
            val stored = repository.findDraftTitle(DraftEntity.NewConversation)
            pendingTitle = stored
            if (currentConversationId == null && currentConversationTitle == null) {
                currentConversationTitle = stored
            }
        }
        viewModelScope.launch {
            pendingMemoryEnabled = repository.findNewConversationMemoryEnabled()
        }
    }

    /**
     * 活跃路径的**修订号**：每当路径被整段重建（换版本 / 编辑 / 删除 / 分叉）就 +1。
     *
     * 界面用它来分辨"这次列表变化是**新内容**还是**换了条分支**" ——
     * 前者要跟到底部，后者要停在原处。用 `messages.size` 分辨不出来
     * （换版本时条数常常一样）。
     */
    var pathRevision by mutableStateOf(0)
        private set

    /**
     * 是否正在切换会话（已切走、新会话的内容还在读）。
     *
     * 界面用它决定何时做交叉溶解与"落到最新一条" —— 这两件事都必须
     * 等新内容就位再做，否则溶的是旧内容、滚的是旧列表。
     */
    var switching by mutableStateOf(false)
        private set

    // ── 上下文压缩（设计文档 §9.1.1③）────────────────────
    //
    // 核心约定：**只改变发给 API 的内容，绝不删除本地消息。**
    // 被压缩掉的原文在界面上照常显示、照常可编辑、照常导出。

    /** 当前会话生效的压缩摘要。null 表示未压缩。 */
    var compaction by mutableStateOf<CompactionEntity?>(null)
        private set

    var compacting by mutableStateOf(false)
        private set
    var compactionStatus by mutableStateOf<String?>(null)
        private set

    private var compactJob: Job? = null
    private var compactGeneration = 0L

    var presets by mutableStateOf<List<Preset>>(emptyList())
        private set

    var settings by mutableStateOf(
        ConversationSettings(
            model = ModelCatalog.find(ProviderKind.DEEPSEEK, ProviderKind.DEEPSEEK.defaultModel),
        ),
    )
        private set

    var isStreaming by mutableStateOf(false)
        private set

    var imageSkipNotice by mutableStateOf<String?>(null)
        private set
    private var showImageSkipNotice = true
    private var skippedNoVision = 0
    private var skippedUnreadable = 0
    private var skippedUnsupportedFiles = 0

    var retrySkipNotice by mutableStateOf<String?>(null)
        private set

    fun dismissRetrySkipNotice() { retrySkipNotice = null }
    fun muteRetrySkipNotice() {
        dismissRetrySkipNotice()
        viewModelScope.launch { settingsStore.muteRetrySkipNotice() }
    }

    fun dismissImageSkipNotice() { imageSkipNotice = null }
    fun neverRemindImageSkip() {
        showImageSkipNotice = false
        imageSkipNotice = null
        viewModelScope.launch {
            try { settingsStore.setShowImageSkipNotice(false) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                showImageSkipNotice = true
                banner = error.userMessage()
            }
        }
    }

    var banner by mutableStateOf<String?>(null)
        private set

    var models by mutableStateOf(ModelCatalog.knownModels(ProviderKind.DEEPSEEK))
        private set

    /**
     * 用户选择的朗读音色（空 = 系统默认）。
     *
     * 单独开一个状态而不是把整个 `StoredSettings` 暴露出去：界面只需要这一个值，
     * 而 `stored` 里带着 API Key 等敏感字段。设置里换音色 → 流回来 → 朗读立即生效。
     */
    var ttsVoiceName by mutableStateOf("")
        private set

    val readAloudEnabled: Boolean get() = stored.readAloudEnabled

    var autoReadAloud by mutableStateOf(false)
        private set
    private val replyCompletions = MutableSharedFlow<CompletedReply>(extraBufferCapacity = 1)
    val completedReplies = replyCompletions.asSharedFlow()

    fun toggleAutoReadAloud() {
        val next = !autoReadAloud
        autoReadAloud = next
        viewModelScope.launch {
            try { settingsStore.setAutoReadAloud(next) }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) {
                autoReadAloud = !next
                banner = error.userMessage()
            }
        }
    }

    /**
     * DeepSeek **官方** TTS 的接入参数；null = 当前服务商不支持官方朗读。
     *
     * 只有「服务商 = DeepSeek 官方 + Base URL 指向官方域 + Key 已填」才非空 ——
     * 官方 `/v1/tools/tts` 只在官方域提供。界面据此：
     * - 设置页列不列 `Stella` / `Tide` 两个官方音色；
     * - 对话页显不显示朗读按钮（用户要求：不支持的 API 直接隐藏入口）。
     */
    var officialTts by mutableStateOf<DeepSeekTts.Config?>(null)

    /** 第三方声音配置（朗读页配置的），连同聊天 key / 端点一起下发给播放器。 */
    var thirdPartyVoiceApi by mutableStateOf<com.simplechat.app.data.VoiceApiConfig?>(null)
        private set
    var thirdPartyChatKey by mutableStateOf("")
        private set
    var thirdPartyChatBaseUrl by mutableStateOf("")
        private set

    internal var officialAsr by mutableStateOf<DeepSeekAsr.Config?>(null)
        private set

    /**
     * 实际生效的协议通道。
     *
     * 由「配置的档位 + 端点」共同决定（见 `ApiStyle.resolve`）：
     * `AUTO` 时 DeepSeek 官方走扩展通道（能拿到搜索 / 引用 / 服务端标题），
     * 其余端点走 OpenAI 兼容。
     */
    val defaultThinkingActive: Boolean
        get() {
            val model = settings.model
            if (!model.supportsThinking) return false
            if (apiStyle == ApiStyle.ANTHROPIC) return com.simplechat.app.net.claudeAdaptiveModel(model.id)?.canDisableThinking == false
            return model.reasoningWire == com.simplechat.app.net.ReasoningWire.EFFORT &&
                model.effortLevels?.none { it in com.simplechat.app.net.ReasoningWire.offLevels } == true
        }

    val supportsContinuation: Boolean
        get() = apiStyle == ApiStyle.ANTHROPIC && com.simplechat.app.net.claudeAdaptiveModel(settings.model.id) == null

    var apiStyle by mutableStateOf(ApiStyle.ANTHROPIC)
        private set

    private fun selectedSearchRoute(): AgentSearchRoute {
        val baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl }
        return AgentSearchRoute.resolve(baseUrl, effectiveModelId(baseUrl, settings.model.id), apiStyle,
            imageTool = false, independent = stored.hasConfiguredSearchApi)
    }

    val automaticWebSearch: Boolean get() = selectedSearchRoute() == AgentSearchRoute.AUTOMATIC
    val supportsWebSearch: Boolean get() = selectedSearchRoute() != AgentSearchRoute.UNAVAILABLE
    val attachmentSettings: StoredSettings get() = stored.copy(modelId = settings.model.id)

    /** Keep the optimistic switch until DataStore acknowledges this write or the profile changes. */
    private data class PendingSearchSwitch(val profileId: String, val enabled: Boolean, val writeId: String)
    private var pendingSearchSwitch by mutableStateOf<PendingSearchSwitch?>(null)
    private var searchSwitchWrite: Job? = null
    val webSearch: Boolean get() = pendingSearchSwitch
        ?.takeIf { it.profileId == stored.activeApiProfileId }?.enabled ?: stored.webSearch

    /**
     * 思考内容是否在**正文开始时自动收起**（全局显示设置）。
     *
     * 语义定案见 `TODO.md`：开着 → 正文一出现就收成一行「已深度思考 · N 秒」；
     * 关掉 → 全文保持展开，想收自己点。**只影响定稿后的初态与自动收起**，
     * 流式期间的尾巴模式（只看最后几行）与它无关。
     */
    var autoCollapseThinking by mutableStateOf(true)
        private set

    /** 长按输入框开始语音输入（设置页可关）。 */
    var holdToTalk by mutableStateOf(true)
        private set

    /**
     * 已经向服务端要过标题的会话。
     *
     * 与 [titleGeneratedFor] 同一个作用：**每个会话只试一次**。
     * 标题不值得为它反复烧 token，兜底标题本来就是能看的。
     */
    private var serverTitleRequestedFor: String? = null

    /** 切换智能搜索（输入栏那个开关）。立即落库，下次启动保持。 */
    fun toggleWebSearch() {
        // 通道不支持时不该有入口；这里再挡一道，避免其它路径把它打开
        if (!supportsWebSearch || automaticWebSearch) return
        val intent = PendingSearchSwitch(stored.activeApiProfileId, !webSearch, java.util.UUID.randomUUID().toString())
        pendingSearchSwitch = intent
        val previous = searchSwitchWrite
        searchSwitchWrite = viewModelScope.launch {
            previous?.join()
            try {
                settingsStore.setWebSearch(intent.enabled, intent.profileId, intent.writeId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (pendingSearchSwitch == intent) pendingSearchSwitch = null
                Diagnostics.log("AGENT", "search_switch_write_failed error=${error.javaClass.simpleName}")
            }
        }
    }

    var refreshingModels by mutableStateOf(false)
        private set

    var modelHint by mutableStateOf<String?>(null)
        private set

    private var streamJob: Job? = null
    private var streamGeneration = 0L
    private val imageGenerationApi = ImageGenerationApi()

    /** 会话切换任务；`send()` 会等它完成，避免把消息插进半成品列表。 */
    private var switchJob: Job? = null
    private val sendMutex = Mutex()

    /** 会话级设置（补充提示词 / 预设勾选）的防抖落库任务。 */
    private var settingsPersistJob: Job? = null

    /**
     * 会话标题的观察任务。
     *
     * 标题**只有库里的那一列是真相** —— 顶栏、抽屉、对话设置里的名称框
     * 都从它读。这里订阅当前会话那一行，三种改名方式（抽屉重命名 /
     * AI 起名 / 名称框）都从库流回来，不可能各说各话。
     *
     * 早先 `currentConversationTitle` 是个只在切会话时同步一次的内存副本：
     * 从抽屉改名后**顶栏停在旧标题上**，而且只有顶栏看得出来，
     * 越看越像"抽屉写错了"。
     */
    private var titleObserverJob: Job? = null

    /** 会话切换的世代号，用于丢弃过期的加载结果。 */
    private var switchGeneration = 0

    private var stoppedByUser = false
    private var stored by mutableStateOf(StoredSettings())
    private var previousProviderKind: ProviderKind? = null

    /** 上一次见到的端点，用来判断"模型列表该不该作废 / 换一份"。 */
    private var previousBaseUrl: String? = null

    /** 上一次见到的远端模型列表（落库的那份），同理只用来比较。 */
    private var previousModelIds: List<String> = emptyList()
    private var previousModelContextWindows: Map<String, Int> = emptyMap()
    private var previousModelMetadata: Map<String, RemoteModelMetadata> = emptyMap()
    private var previousModelOverrides: Map<String, ModelCapabilityOverride> = emptyMap()

    private val providerKind: ProviderKind
        get() = runCatching { ProviderKind.valueOf(stored.providerId) }
            .getOrDefault(ProviderKind.DEEPSEEK)

    init {
        viewModelScope.launch {
            settingsStore.flow.collect { value ->
                stored = value
                applyStored(value)
            }
        }
        viewModelScope.launch {
            repository.observePresets().collect { presets = it }
        }

        /*
         * 上下文统计：消息 / 设置 / 预设 / 压缩任何一个变了就重算，
         * 但**防抖 + 后台线程**（原因见 contextStats 的说明）。
         *
         * 用 `collectLatest` + `delay` 而不是 `debounce`：后者是 FlowPreview API，
         * 而这里要的语义就是"新输入来了就取消上一次等待"，两行代码说清楚。
         *
         * ⚠️ 这个 init 块**必须声明在 `settings` 等状态之后**：Kotlin 按声明顺序
         * 初始化属性，而 `Main.immediate` 的 `launch` 会当场执行到第一个挂起点 ——
         * 放在前面会读到还没赋值的委托（真机表现为启动即 NPE）。
         */
        viewModelScope.launch {
            var weights = emptyMap<String, MessageWeight>()
            snapshotFlow { StatsInput(messages.toList(), settings, presets, compaction) }
                .collectLatest { input ->
                    delay(ContextStatsDebounceMs)
                    val (stats, currentWeights) = withContext(Dispatchers.Default) { computeContextStats(input, weights) }
                    weights = currentWeights
                    contextStats = stats
                }
        }
    }

    private fun applyStored(value: StoredSettings) {
        val kind = runCatching { ProviderKind.valueOf(value.providerId) }
            .getOrDefault(ProviderKind.DEEPSEEK)
        /*
         * 模型列表有两个来源，都要跟：**端点换了**（服务商 / Base URL）要换一份，
         * **拉取结果变了**（设置页测试连接、或本页刷新）要跟着更新。
         *
         * 用 [ModelCatalog.pickerModels] 而不是 `knownModels`：自定义端点的内置
         * 目录是空的，早先它会把拉到的模型列表整个抹掉 —— 于是"拉取成功"之后
         * 一进设置、或重启一次，列表就空了。
         */
        val endpointChanged = kind != previousProviderKind || value.baseUrl != previousBaseUrl
        if (endpointChanged || value.modelIds != previousModelIds || value.modelContextWindows != previousModelContextWindows ||
            value.modelMetadata != previousModelMetadata || value.modelOverrides != previousModelOverrides) {
            previousProviderKind = kind
            previousBaseUrl = value.baseUrl
            previousModelIds = value.modelIds
            previousModelContextWindows = value.modelContextWindows
            previousModelMetadata = value.modelMetadata
            previousModelOverrides = value.modelOverrides
            models = ModelCatalog.pickerModels(kind, value.modelIds, value.modelContextWindows,
                value.modelMetadata, value.modelOverrides)
            // 端点换了，上一轮的"已拉取 N 个"不再成立；只是列表更新则保留提示
            if (endpointChanged) modelHint = null
        }
        apiStyle = ApiStyle.resolve(kind, value.apiStyleId, value.baseUrl)
        val pendingSwitch = pendingSearchSwitch
        if (pendingSwitch != null && (pendingSwitch.profileId != value.activeApiProfileId ||
                pendingSwitch.writeId == value.webSearchWriteId)) {
            pendingSearchSwitch = null
        }
        autoCollapseThinking = value.autoCollapseThinking
        holdToTalk = value.holdToTalk
        loadingWordsUseThemeColor = value.loadingWordsUseThemeColor
        ttsVoiceName = value.ttsVoiceName
        autoReadAloud = value.autoReadAloud
        showImageSkipNotice = value.showImageSkipNotice
        if (!showImageSkipNotice) imageSkipNotice = null
        officialAsr = DeepSeekAsr.configFor(value.providerId, value.baseUrl, value.apiKey)
        officialTts = if (DeepSeekTts.isOfficialTtsVoiceAvailable(value.providerId, value.apiKey)) {
            DeepSeekTts.Config(value.apiKey)
        } else {
            null
        }
        // 第三方声音：把配置和聊天 key / 端点一起推给朗读控制器
        thirdPartyVoiceApi = value.voiceApi
        thirdPartyChatKey = value.apiKey
        thirdPartyChatBaseUrl = value.baseUrl
        settings = settings.copy(
            model = ModelCatalog.find(kind, value.modelId, value.modelContextWindows, value.modelMetadata, value.modelOverrides),
            reasoningLevel = runCatching { ReasoningLevel.valueOf(value.reasoningLevelId) }
                .getOrDefault(ReasoningLevel.OFF),
            temperature = value.temperature,
            topP = value.topP,
            maxTokens = value.maxTokens.takeIf { it > 0 },
        )
    }

    // ── 会话切换 ──────────────────────────────────────────

    /**
     * 订阅某个会话的标题。
     *
     * 这是标题**唯一的写入者**：三种改名方式都只写库，界面侧一律由这里回流。
     * 切换会话时重订（旧的取消），所以回调里再核对一次 `currentConversationId`
     * 防止快切时旧会话的标题盖上来。
     */
    private fun observeTitle(conversationId: String) {
        titleObserverJob?.cancel()
        titleObserverJob = viewModelScope.launch {
            repository.observeConversation(conversationId).collect { entity ->
                if (currentConversationId == conversationId) {
                    currentConversationTitle = entity?.title
                }
            }
        }
    }

    /**
     * 重命名当前会话（对话设置里的名称框用）。
     *
     * 只写库，**不在这里改 `currentConversationTitle`** —— 写了会变成
     * 第二个真相，而 [observeTitle] 那边马上会把库里的值送回来。
     * 名称框自身有防抖，这里按"每次调用都落库"理解即可。
     *
     * ⚠️ **会话还没建时例外**：那一刻库里没有行可写，内存里这份就是唯一的真相
     * （§35 同一个处境）。名字先当**待用标题**记下并落库，[ensureConversation]
     * 建会话时一并用上 —— 于是"发第一条消息之前起好名"就成立了。
     */
    fun renameCurrentConversation(title: String) {
        val conversationId = currentConversationId
        if (conversationId == null) {
            val clean = title.trim().takeIf { it.isNotBlank() }
            pendingTitle = clean
            currentConversationTitle = clean
            viewModelScope.launch {
                repository.saveDraftTitle(DraftEntity.NewConversation, clean)
            }
            return
        }
        viewModelScope.launch { repository.renameConversation(conversationId, title) }
    }

    fun setConversationMemoryEnabled(enabled: Boolean) {
        if (switching) return
        val id = currentConversationId
        if (id == null) {
            pendingMemoryEnabled = enabled
            viewModelScope.launch { repository.saveNewConversationMemoryEnabled(enabled) }
        } else {
            openedMemoryEnabled = enabled
            viewModelScope.launch { repository.setConversationMemoryEnabled(id, enabled) }
        }
    }

    /**
     * 切换会话。
     *
     * **id 与列表清空是同步做的，只有"读回来填入"这一步是异步的** ——
     * 早先整个切换都在协程里、且跨了两次挂起点，于是存在两个致命窗口：
     *
     * - `currentConversationId` 已改成新会话、但列表还是旧的 → 新消息被追加进旧列表
     * - 列表已清空、但 `addAll` 还没跑 → 新消息先入列，历史被追加到它**后面**，顺序颠倒
     *
     * 另外用 [switchGeneration] 丢弃过期结果：快速连点两个会话时，
     * 先发的那次加载不能覆盖后发的。
     */
    var searchTarget by mutableStateOf<SearchTarget?>(null)
        private set
    private var searchRequest = 0L

    fun markSearchReady(request: Long) {
        searchTarget?.takeIf { it.request == request }?.let { searchTarget = it.copy(ready = true) }
    }

    fun dismissSearch() { searchTarget = null }

    fun openConversation(id: String, targetMessageId: String? = null, query: String = "", reasoningMatch: Boolean = false) {
        if (id == currentConversationId && targetMessageId == null) return
        searchTarget = targetMessageId?.let { SearchTarget(it, query, ++searchRequest, reasoningMatch = reasoningMatch) }
        imageSkipNotice = null
        retrySkipNotice = null
        stop()
        cancelCompaction()
        switchJob?.cancel()

        val generation = ++switchGeneration
        currentConversationId = id
        // 换会话 = 换一轮上下文：上一轮的用量与缓存命中率不再适用
        lastUsage = null
        // 标题立刻开始观察；下面那句清空只是防止旧标题残留一帧
        observeTitle(id)
        switching = true
        compaction = null
        currentConversationTitle = null

        switchJob = viewModelScope.launch {
            try {
            val conversation = repository.findConversation(id)
            if (generation != switchGeneration) return@launch
            if (conversation == null) {
                newConversation()
                return@launch
            }
            // 先清扫上次被中断的流式消息，否则它会以 STREAMING 状态卡在界面上
            repository.resolveDanglingStreams(id)
            if (targetMessageId != null) repository.revealSearchMessage(id, targetMessageId)

            val loaded = repository.loadActivePath(id)

            val loadedCompaction = repository.getCompactions(id).lastOrNull()
            val loadedPresetIds = repository.getEnabledPresetIds(id)

            if (generation != switchGeneration) return@launch

            /*
             * ★ **先预热，再交给界面。**
             *
             * Markdown 解析是同步的（见 `MarkdownText` 的 `MarkdownStateCache`），
             * 一条长消息第一次组合会在**主线程**逐块解析 —— 五百条消息的会话里，
             * 这一步能吃掉几十毫秒，正好压在"侧边栏收起"和转圈动画上：
             * 动画自己也会卡，转圈就白转了。
             *
             * 所以把"打开就能看到的那几条"（贴底打开 = 尾部）在**后台**先解析好，
             * 再 publish 给界面。列表真正组合时只命中缓存，主线程保持空闲，
             * 转圈因此是顺的。
             */
            if (targetMessageId == null) withContext(Dispatchers.Default) {
                for (body in historyPrewarmBodies(loaded, before = loaded.size)) warmMarkdownCache(body)
            }

            if (generation != switchGeneration) return@launch

            /*
             * ⚠️ **列表是"先读回来、再整份替换"，不是"先清空"**。
             *
             * 早先是 `messages.clear()` 同步做、`addAll` 异步做 ——
             * 中间那一帧列表是空的，界面会闪一下空态（"一起创作吧"），
             * 快速连点会话时尤其明显。
             *
             * 代价是这一小段窗口里列表还是上一个会话的内容（几十毫秒）。
             * 用户在这个窗口里发消息基本不可能；真发生了也只会体现在画面上，
             * 数据是写进新会话的，下一次 reloadPath 就对齐了。
             */
            messages.clear()
            messages.addAll(loaded)

            compaction = loadedCompaction
            openedMemoryEnabled = conversation.memoryEnabled
            // 顺手填一次，避免标题晚一帧才出来；**真相仍是 observeTitle 那条流**
            currentConversationTitle = conversation.title
            settings = settings.copy(
                mode = conversation.mode.toConversationModeSafe(),
                systemPrompt = conversation.systemPrompt.orEmpty(),
                /*
                 * ⚠️ 预设挂载**必须读回来**。
                 *
                 * 先前漏了这一步：预设只在内存里活着，切走再切回来
                 * `enabledPresetIds` 就空了 —— 「最终提示词」里预设那一段
                 * 凭空消失，请求也就少发了。
                 */
                enabledPresetIds = loadedPresetIds,
            )
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == switchGeneration) {
                    messages.clear()
                    banner = Res.get(R.string.conversation_load_failed, error.userMessage())
                }
            } finally {
                if (generation == switchGeneration) switching = false
            }
        }
    }

    fun newConversation() {
        searchTarget = null
        stop()
        cancelCompaction()
        switchJob?.cancel()
        ++switchGeneration
        titleObserverJob?.cancel()
        currentConversationId = null
        // 「新对话」的待用标题还在（与草稿一样，不因为点了新建就被吞掉）
        currentConversationTitle = pendingTitle
        compaction = null
        messages.clear()
        banner = null
        imageSkipNotice = null
        retrySkipNotice = null
        switching = false
        settings = settings.copy(systemPrompt = "")
    }

    // ── 设置 ──────────────────────────────────────────────

    fun updateSettings(next: ConversationSettings) {
        val previous = settings
        val profileId = stored.activeApiProfileId
        settings = next
        viewModelScope.launch {
            if (next.model.id != previous.model.id) settingsStore.setModel(next.model.id, profileId)
            if (next.reasoningLevel != previous.reasoningLevel) {
                settingsStore.setReasoningLevel(next.reasoningLevel.name, profileId)
            }
            if (next.temperature != previous.temperature) settingsStore.setTemperature(next.temperature, profileId)
            if (next.topP != previous.topP) settingsStore.setTopP(next.topP, profileId)
            if (next.maxTokens != previous.maxTokens) settingsStore.setMaxTokens(next.maxTokens ?: 0, profileId)
        }

        /*
         * 会话级的项（补充提示词 / 预设勾选）要落到**这个会话**上。
         *
         * 补充提示词是逐字输入的，不能每敲一个字写一次库 —— 做成防抖：
         * 停手 400ms 后写一次最新的。
         *
         * 值在**进入协程前**就取好：中途切了会话的话，`settings` 已经变成
         * 另一个会话的内容，那时再读就会把 A 的设置写进 B。
         */
        val conversationId = currentConversationId
        if (conversationId != null &&
            (next.systemPrompt != previous.systemPrompt ||
                next.enabledPresetIds != previous.enabledPresetIds)
        ) {
            val prompt = next.systemPrompt
            val presetIds = next.enabledPresetIds
            settingsPersistJob?.cancel()
            settingsPersistJob = viewModelScope.launch {
                delay(SETTINGS_PERSIST_DEBOUNCE_MS)
                repository.setConversationSettings(conversationId, prompt, presetIds)
            }
        }
    }

    fun dismissBanner() {
        banner = null
    }

    fun refreshModels() {
        if (refreshingModels) return
        refreshingModels = true
        modelHint = null
        val source = stored
        val kind = runCatching { ProviderKind.valueOf(source.providerId) }.getOrDefault(ProviderKind.DEEPSEEK)
        val profile = source.apiProfiles.firstOrNull { it.id == source.activeApiProfileId }
        viewModelScope.launch {
            try {
                val result = chatApi.fetchModelCatalog(
                    baseUrl = source.baseUrl.ifBlank { kind.defaultBaseUrl },
                    apiKey = if (kind.modelListNeedsAuth) source.apiKey else "",
                    apiStyle = source.resolvedApiStyle,
                )
                if (stored.activeApiProfileId != source.activeApiProfileId || stored.baseUrl != source.baseUrl ||
                    stored.providerId != source.providerId || stored.apiKey != source.apiKey || stored.apiStyleId != source.apiStyleId) {
                    return@launch
                }
                result.onSuccess { catalog ->
                    modelHint = Res.get(R.string.models_fetched, catalog.ids.size)
                }
                result.onFailure { error ->
                    modelHint = Res.get(R.string.models_fetch_failed, error.userMessage()) +
                        if (!stored.hasKey) Res.get(R.string.models_fetch_needs_key) else ""
                }
                // 落库：设置页的「模型」选择器读的是这份，重启后也还在
                result.getOrNull()?.let { catalog ->
                    settingsStore.updateApiProfile(source.activeApiProfileId) { current ->
                        if (profile != null && current.baseUrl == profile.baseUrl && current.providerId == profile.providerId &&
                            current.apiKeyEncrypted == profile.apiKeyEncrypted && current.apiStyleId == profile.apiStyleId) {
                            current.copy(modelIds = catalog.ids, modelContextWindows = catalog.contextWindows, modelMetadata = catalog.metadata)
                        } else current
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (stored.activeApiProfileId == source.activeApiProfileId && stored.baseUrl == source.baseUrl &&
                    stored.providerId == source.providerId && stored.apiKey == source.apiKey && stored.apiStyleId == source.apiStyleId) {
                    modelHint = Res.get(R.string.models_fetch_failed, error.userMessage()) +
                        if (!stored.hasKey) Res.get(R.string.models_fetch_needs_key) else ""
                }
            } finally {
                refreshingModels = false
            }
        }
    }

    // ── 发送 ──────────────────────────────────────────────

    fun send(prompt: String, attachments: List<Attachment> = emptyList(), onPersisted: () -> Unit = {}) {
        val text = prompt.trim()
        // 允许「只有附件、没有文字」—— 丢一张图进来说"看看这个"是最自然的用法
        if ((text.isEmpty() && attachments.isEmpty()) || isStreaming || switching) return

        if (!stored.hasKey && providerKind != ProviderKind.OPENCODE_GO) {
            banner = Res.get(R.string.error_no_api_key)
            return
        }

        /*
         * 还没选模型就发：自定义服务商的 `defaultModel` 是空串，而请求里的
         * `model` 字段一旦为空，服务端只会回一句看不懂的 400。在这里拦下来
         * 并直接说"去哪儿选"。
         */
        if (stored.modelId.isBlank()) {
            banner = Res.get(R.string.error_no_model)
            return
        }

        if (compacting) cancelCompaction()
        banner = null
        imageSkipNotice = null

        val requestedGeneration = switchGeneration
        viewModelScope.launch {
            if (!sendMutex.tryLock()) return@launch
            try {
                // 会话还在切换 → 等它读完再发，否则消息会被插进半成品列表（顺序颠倒）
                switchJob?.join()
                if (requestedGeneration != switchGeneration) return@launch
                val conversationId = ensureConversation(requestedGeneration) ?: return@launch
                val generation = requestedGeneration
                val model = settings.model.id

                val user = repository.appendUserMessage(conversationId, text, attachments)
                if (generation != switchGeneration || conversationId != currentConversationId) return@launch
                onPersisted()
                // 首条用户消息会触发库里自动命名，顶栏同步一次
                currentConversationTitle = repository.findConversation(conversationId)?.title
                if (generation != switchGeneration || conversationId != currentConversationId) return@launch
                messages += user

                // 回复挂在**刚发的这条**下面
                val assistantId = repository.beginAssistantMessage(
                    conversationId = conversationId,
                    parentId = user.id,
                    // 记下这条是谁写的 —— 事后换模型时，历史才分得清
                    model = model,
                )
                if (generation != switchGeneration || conversationId != currentConversationId) return@launch
                messages += UiMessage(
                    id = assistantId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    reasoning = "",
                    status = MessageStatus.STREAMING,
                    parentId = user.id,
                    /*
                     * 这条内存对象是**手工构造**的，不走 `MessageEntity.toUi()`，
                     * 所以字段要一个个搬 —— 漏掉一个，它就会在整段流式期间缺失。
                     * （`model` 就漏过一次：库里写了、界面上看不到。）
                     *
                     * 重新生成那条路径没这个问题：它走 `reloadPath` 从库读回来。
                     */
                    model = model,
                )

                startStream(conversationId, assistantId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.e("SimpleChat/Send", "Preparing message failed", error)
                banner = Res.get(R.string.error_send_failed, error.userMessage())
            } finally {
                sendMutex.unlock()
            }
        }
    }

    /**
     * 取当前会话；**已被删除就当作新对话**。
     *
     * 用户在抽屉里删掉正在看的会话时，`currentConversationId` 会短暂指向
     * 一个不存在的 id —— 此时直接往它下面插消息会撞外键约束。
     * 这里顺手把内存状态清干净，界面也就自然回到空态。
     */
    private suspend fun ensureConversation(generation: Int): String? {
        val existing = currentConversationId
        val exists = existing != null && repository.findConversation(existing) != null
        if (generation != switchGeneration) return null
        if (exists) return existing

        messages.clear()
        compaction = null
        val memoryEnabled = pendingMemoryEnabled
        val id = repository.createConversation(
            mode = settings.mode,
            provider = providerKind.name,
            model = settings.model.id,
            systemPrompt = settings.systemPrompt,
            // 勾好的预设要跟着一起落库，否则第一条消息之后就再也找不回来了
            enabledPresetIds = settings.enabledPresetIds,
            // 用户在发第一条消息之前就起好了名 → 直接用它，不再让 AI 起
            title = pendingTitle ?: Res.get(R.string.conversation_default_title),
            memoryEnabled = memoryEnabled,
        )
        if (generation != switchGeneration) return null
        if (pendingTitle != null) {
            // 名字是用户起的，`maybeGenerateTitle` 不该覆盖它
            titleGeneratedFor = id
            pendingTitle = null
            viewModelScope.launch {
                repository.saveDraftTitle(DraftEntity.NewConversation, null)
            }
        }
        openedMemoryEnabled = memoryEnabled
        currentConversationId = id
        pendingMemoryEnabled = true
        viewModelScope.launch { repository.saveNewConversationMemoryEnabled(true) }
        // 新会话刚建出来，标题也要开始观察（首条消息会触发库里自动命名）
        observeTitle(id)
        val title = repository.findConversation(id)?.title
        if (generation != switchGeneration) return null
        currentConversationTitle = title
        return id
    }

    /**
     * 从库里重新读一遍活跃路径。
     *
     * 树的增删改一律走这里，而不是在内存列表上原地修补 ——
     * 切换版本会**整段替换下游**（旧分支的子树让位给新分支的），
     * 原地修补要处理的组合太多，读一遍反而又快又不会错。
     */
    private suspend fun reloadPath(conversationId: String, beforePublish: () -> Unit = {}) {
        val generation = switchGeneration
        val path = repository.loadActivePath(conversationId)
        if (conversationId != currentConversationId || generation != switchGeneration) return
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            beforePublish()
            messages.clear()
            messages.addAll(path)
            pathRevision++
        }
    }

    // ── 上下文压缩 ────────────────────────────────────────

    /**
     * 压缩上下文：把较早的对话交给模型总结成「前情提要」。
     *
     * 三件事刻意分开：
     * - **不删本地消息** —— 压缩只影响 `buildRequestMessages()` 里挑哪些发出去
     * - **通常保留最近 6 条完整消息** —— 最近的对白是文风与语气的锚，
     *   被摘要成"他们继续交谈"会立刻丢失质感
     * - **摘要可读可改** —— AI 记错人名地名时用户能直接修正（见 [updateCompactionSummary]）
     */
    fun compactContext() {
        if (compacting || isStreaming || switching) return
        val conversationId = currentConversationId ?: return
        val model = settings.model
        val baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl }
        val apiKey = stored.apiKey
        val headers = providerKind.headersFor(openCodeSessionId)
        val selectedApiStyle = apiStyle
        banner = null
        imageSkipNotice = null
        compactJob = viewModelScope.launch {
            try {
                compactHistory(conversationId, model, baseUrl, apiKey, headers, selectedApiStyle)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentConversationId == conversationId) banner = compactionErrorText(error)
            } finally {
                if (!compacting && currentConversationId == conversationId) compactJob = null
            }
        }
    }

    private suspend fun compactHistory(
        conversationId: String,
        model: ModelInfo,
        baseUrl: String,
        apiKey: String,
        headers: Map<String, String>,
        selectedApiStyle: ApiStyle,
        requestWindow: Int? = null,
    ): Boolean {
        val generation = switchGeneration
        val revision = pathRevision
        val operation = ++compactGeneration
        val savedCompaction = compaction
        val sourceMessages = messages.toList()
        val existing = validCompaction(sourceMessages, savedCompaction)
        val extended = selectedApiStyle == ApiStyle.ANTHROPIC
        val promptsByStyle = CompactionStyle.entries.associateWith { style -> Res.get(when (style) {
            CompactionStyle.CREATIVE -> R.string.prompt_compact_creative
            CompactionStyle.TECHNICAL -> R.string.prompt_compact_technical
            CompactionStyle.GENERAL -> R.string.prompt_compact_system
        }) }
        val basePrompts = CompactionPrompts(
            system = promptsByStyle.getValue(CompactionStyle.GENERAL),
            existing = Res.get(R.string.prompt_compact_existing),
            newMessages = Res.get(R.string.prompt_compact_new),
            user = Res.get(R.string.prompt_user_prefix),
            assistant = Res.get(R.string.prompt_ai_prefix),
            image = Res.get(R.string.prompt_has_image),
            unreadableAttachment = Res.get(R.string.prompt_compact_attachment_skipped),
        )

        compacting = true
        compactionStatus = null

        try {
            val result = withContext(Dispatchers.Default) {
                val alreadyCovered = existing?.let { c -> sourceMessages.indexOfFirst { it.id == c.throughMessageId } } ?: -1
                val candidates = sourceMessages.asSequence().drop(alreadyCovered + 1)
                    .filter { !it.streaming && (it.content.isNotBlank() || it.attachments.isNotEmpty()) }.toList()
                val recentBudget = compactionRecentTokenBudget(model.contextWindow, requestWindow)
                val cutIndex = compactionCutIndex(candidates, recentBudget)
                if (cutIndex <= 0) throw com.simplechat.app.data.AppTextException(
                    if (existing != null) R.string.compact_no_new_history else R.string.compact_too_few,
                )
                val toSummarize = candidates.take(cutIndex)
                val style = classifyCompaction(toSummarize, existing?.summary)
                val prompts = basePrompts.copy(system = promptsByStyle.getValue(style))
                val segments = compactionSegments(toSummarize, existing?.summary, prompts)
                val fingerprint = compactionFingerprint(sequenceOf(
                    "hierarchical-compaction-v1", conversationId, model.id, model.contextWindow.toString(),
                    baseUrl, apiKey, selectedApiStyle.name, headers.toSortedMap().toString(), prompts.system,
                    existing?.throughMessageId.orEmpty(),
                ) + toSummarize.asSequence().map { it.id } + segments)
                val saved = repository.loadCompactionCheckpoint(conversationId, fingerprint)
                    ?: CompactionCheckpoint(fingerprint = fingerprint)
                val checkpoint = requestWindow?.let { saved.copy(requestWindow = minOf(saved.requestWindow, it)) } ?: saved
                val summary = compactInBatches(
                    segments, prompts, model.contextWindow, checkpoint,
                    // Other endpoints can impose lower output limits even when advertising a 1M input window.
                    harnessBudget = runCatching { java.net.URI(baseUrl).host.equals("api.deepseek.com", true) }.getOrDefault(false) &&
                        model.id in setOf("deepseek-flash", "deepseek-v4-pro", "deepseek-v4-flash", "deepseek-v4.1-flash", "deepseek-v4-flash-vision-exp"),
                    onCheckpoint = { repository.saveCompactionCheckpoint(conversationId, it) },
                    onProgress = {
                        // Log only operational metadata, never conversation content or credentials.
                        android.util.Log.i("SimpleChat/Compaction",
                            "level=${it.level} batch=${it.batch} attempt=${it.attempt} inputChars=${it.inputChars} " +
                                "processed=${it.processedChars}/${it.totalChars} window=${it.effectiveWindow} output=${it.outputTokens} recovery=${it.recovery}")
                        if (it.recovery == null) {
                            val phase = if (it.isWholeSource) Res.get(R.string.compact_whole_source) else if (it.level == 0) {
                                val percent = 100 * it.processedChars / it.totalChars.coerceAtLeast(1)
                                Res.get(R.string.compact_batch_source, percent, it.attempt)
                            } else Res.get(R.string.compact_batch_merge, it.attempt)
                            withContext(Dispatchers.Main) {
                                if (operation == compactGeneration) compactionStatus = phase
                            }
                        }
                    },
                ) { transcript, maxTokens, _ ->
                    val brief = Res.get(R.string.compact_output_target, (maxTokens / 3).coerceIn(32, 1200))
                    // Finish with the active summarization task, after the quoted history.
                    val material = transcript + "\n\n" + brief
                    val stream = if (extended) {
                        chatApi.streamMessage(
                            baseUrl = baseUrl, apiKey = apiKey,
                            request = buildAnthropicRequest(
                                model = model.id, systemPrompt = prompts.system,
                                path = listOf(UiMessage("compact", MessageRole.USER, material)),
                                searchEnabled = false, thinkingEnabled = false, requestTitle = false,
                                latestReminder = null, maxTokens = maxTokens, outputLimit = model.maxOutputTokens, temperature = 0.3,
                                deepSeekExtensions = isOfficialDeepSeekEndpoint(baseUrl),
                            ).copy(tools = emptyList()),
                            extraHeaders = headers, contextWindow = model.contextWindow, retryBeforeContent = false,
                        )
                    } else {
                        chatApi.streamChat(
                            apiStyle = selectedApiStyle,
                            baseUrl = baseUrl, apiKey = apiKey, model = model,
                            messages = listOf(
                                OutMessageDto("system", MessageContent.text(prompts.system)),
                                OutMessageDto("user", MessageContent.text(material)),
                            ),
                            thinkingEnabled = false, maxTokens = maxTokens,
                            temperature = 0.3, extraHeaders = headers, retryBeforeContent = false,
                        )
                    }
                    collectCompactionSummary(stream, maxChars = com.simplechat.app.data.MaxCompactionSummaryChars) { reason, chars, thinking ->
                        android.util.Log.i("SimpleChat/Compaction", "protocol=$apiStyle end=$reason textChars=$chars thinkingChars=$thinking maxTokens=$maxTokens")
                    }
                }
                (toSummarize.lastOrNull()?.id ?: checkNotNull(existing).throughMessageId) to summary
            }
            // A changed branch/edit invalidates the source snapshot, even if the chat ID matches.
            if (generation != switchGeneration || revision != pathRevision || savedCompaction != compaction || operation != compactGeneration) return false
            val entity = CompactionEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                conversationId = conversationId,
                throughMessageId = result.first,
                summary = result.second,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            repository.saveCompaction(entity)
            if (generation != switchGeneration || revision != pathRevision || operation != compactGeneration || savedCompaction != compaction) return false
            compaction = entity
            repository.deleteCompactionCheckpoint(conversationId)
            return true

        } finally {
            if (generation == switchGeneration && operation == compactGeneration) {
                compacting = false
                compactionStatus = null
            }
        }
    }

    private fun compactionErrorText(error: Exception): String {
        val detail = if (error is CompactionRequestFailure) when (error.kind) {
            CompactionFailureKind.INPUT_LIMIT -> Res.get(R.string.compact_input_rejected)
            CompactionFailureKind.TRANSIENT -> Res.get(R.string.compact_interrupted)
            CompactionFailureKind.TIMEOUT -> Res.get(R.string.compact_request_timeout)
            CompactionFailureKind.TRUNCATED -> Res.get(R.string.compact_truncated)
            CompactionFailureKind.EMPTY -> Res.get(R.string.compact_incomplete)
            CompactionFailureKind.PERMANENT -> error.event?.let {
                if (it.rawMessage != null || it.code != null) it.message else serverErrorText(it.message)
            } ?: Res.get(R.string.compact_incomplete)
        } else error.userMessage()
        val resource = (error as? com.simplechat.app.data.AppTextException)?.text?.resId
        return when (resource) {
            R.string.compact_request_cap, R.string.compact_deadline,
            R.string.compact_whole_limit, R.string.compact_whole_rejected -> detail
            else -> Res.get(R.string.compact_failed_detail, detail)
        }
    }

    /** 放弃压缩，恢复发送全部消息。**本地消息一条都没少。** */
    fun clearCompaction() {
        val active = compaction ?: return
        cancelCompaction()
        compaction = null
        viewModelScope.launch {
            repository.deleteCompaction(active.id)
            repository.deleteCompactionCheckpoint(active.conversationId)
        }
    }

    /** 手工修正摘要（AI 记错人名地名时用）。 */
    fun updateCompactionSummary(text: String) {
        val active = compaction ?: return
        cancelCompaction()
        val updated = active.copy(summary = text.trim(), updatedAt = System.currentTimeMillis())
        compaction = updated
        viewModelScope.launch { repository.saveCompaction(updated) }
    }

    fun cancelCompaction() {
        compactGeneration++
        compactJob?.cancel()
        compactJob = null
        compacting = false
        compactionStatus = null
    }

    // ── 上下文统计 ────────────────────────────────────────

    /**
     * 当前会话的上下文占用与性能统计。
     *
     * ### 为什么不用 `derivedStateOf`
     *
     * 这段统计要**扫一遍全部消息的正文**来估 token。写成 `derivedStateOf` 时它依赖
     * `messages` 这个快照列表 —— 而流式期间列表每 16ms 就变一次，于是它每帧都把
     * 整条会话重新扫一遍（三十万 token 的会话 ≈ 每帧几十万字符 + 若干次全量 map）。
     * 真机表现就是：长会话里一开始生成，整个界面就发涩、滑不动。
     *
     * 现在改成 `snapshotFlow` + 防抖 + 后台线程：输入栏上那行统计晚几百毫秒更新
     * 完全无所谓，而主线程不再为它做任何与正文长度相关的工作。
     */
    var contextStats by mutableStateOf(emptyContextStats())
        private set

    /**
     * **最近一次请求**的真实用量（服务端在流末尾回报的，不是本地估算）。
     *
     * 只保留最近一次：缓存命中率说的是"这一轮的前缀有没有被复用"，
     * 跨轮聚合反而会把它稀释成一个看不出变化的平均数。
     * 切换会话时清空 —— 新会话还没发过请求，显示上一轮的数字是错的。
     */
    var lastUsage by mutableStateOf<RequestUsage?>(null)
        private set

    /**
     * 记录用量。
     *
     * 两个通道都是一条流里**分两次**报的（Anthropic：`message_start` 只有输入侧、
     * `message_delta` 只有输出侧；OpenAI 通道有些网关也会拆开），所以这里做合并：
     * 有值就覆盖、没值就沿用上一次，缺字段不能把已有数据冲成 0。
     */
    private fun recordUsage(event: StreamEvent.Usage) {
        val previous = lastUsage
        lastUsage = RequestUsage(
            promptTokens = event.promptTokens.takeIf { it > 0 } ?: previous?.promptTokens ?: 0,
            completionTokens = maxOf(event.completionTokens, previous?.completionTokens ?: 0),
            cacheHitTokens = event.cacheHitTokens ?: previous?.cacheHitTokens,
        )
    }

    private fun emptyContextStats() = ContextStats(
        usedTokens = 0,
        windowTokens = settings.model.contextWindow,
        messageCount = messages.size,
        totalTokens = 0,
        compactedTokenSaving = 0,
        avgTtftMs = null,
        avgTps = null,
        totalThinkingSeconds = null,
        hasCompaction = compaction != null,
    )

    /**
     * 统计的**输入快照**。
     *
     * 刻意做成一个 data class 而不是在 flow 里直接读各状态：`snapshotFlow` 用
     * `equals` 判"有没有变"，这里一次读全，语义最清楚（消息 / 设置 / 预设 / 压缩
     * 任何一项变了都要重算）。
     */
    @Immutable
    private data class StatsInput(
        val messages: List<UiMessage>,
        val settings: ConversationSettings,
        val presets: List<Preset>,
        val compaction: CompactionEntity?,
    )

    private data class MessageWeight(val text: String, val weight: Long)

    private fun computeContextStats(input: StatsInput, previous: Map<String, MessageWeight>): Pair<ContextStats, Map<String, MessageWeight>> {
        val messages = input.messages
        val settings = input.settings
        val presets = input.presets
        val active = validCompaction(messages, input.compaction)

        // —— 实际会发给 API 的部分 ——
        val system = settings.composedSystemPrompt(presets)
        val outgoing = orderedOutgoingMessages(messages, active)
        val weights = messages.asSequence().filter { !it.streaming }.associate { message ->
            val cached = previous[message.id]?.takeIf { it.text === message.content }
            message.id to (cached ?: MessageWeight(message.content, TokenEstimate.weight(message.content)))
        }
        val summaryWeight = active?.let { TokenEstimate.weight(it.summary) } ?: 0L
        val usedWeight = TokenEstimate.weight(system) + summaryWeight + outgoing.sumOf { weights.getValue(it.id).weight }
        val attachmentTokens = outgoing.sumOf { attachmentTokenEstimate(it.attachments) }

        val ttfts = messages.mapNotNull { it.ttftMs }
        val tpsList = messages.mapNotNull { it.tps }
        val thinking = messages.mapNotNull { it.reasoningSeconds }

        /*
         * 服务端回报的用量：只算**有上报**的回复。老数据 tokensIn/out 为 null，
         * 不能按 0 计入 —— 否则"本会话消耗"会永远停在 0，看起来像没工作。
         */
        val usageMessages = messages.filter { it.role == MessageRole.ASSISTANT && it.tokensIn != null }

        return ContextStats(
            usedTokens = TokenEstimate.fromWeight(usedWeight) + attachmentTokens,
            windowTokens = settings.model.contextWindow,
            messageCount = messages.size,
            totalTokens = TokenEstimate.fromWeight(weights.values.sumOf { it.weight }),
            compactedTokenSaving = run {
                if (active == null) return@run 0
                val cut = messages.indexOfFirst { it.id == active.throughMessageId }
                if (cut < 0) return@run 0
                TokenEstimate.fromWeight(messages.take(cut + 1).sumOf { weights[it.id]?.weight ?: TokenEstimate.weight(it.content) }) -
                    TokenEstimate.fromWeight(summaryWeight)
            },
            avgTtftMs = ttfts.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            avgTps = tpsList.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            totalThinkingSeconds = thinking.takeIf { it.isNotEmpty() }?.sum(),
            hasCompaction = active != null,
            usageReplies = usageMessages.size,
            usagePromptTokens = usageMessages.sumOf { it.tokensIn ?: 0 },
            usageCompletionTokens = usageMessages.sumOf { it.tokensOut ?: 0 },
            usageCacheHitTokens = usageMessages.sumOf { it.cacheHitTokens ?: 0 },
        ) to weights
    }

    // ── 消息操作：重新生成 / 重试 / 编辑 / 删除 / 切版本 ──────────

    /**
     * 重试一条**失败**的回复。
     *
     * 与 [regenerate] 的区别：重试是**原地**重来，不新增版本 ——
     * 重试的是同一次请求，用户看到的还应该是那一格。
     *
     * 用**当前**选的模型（顺带写回 `model`）：用户很可能就是换了模型
     * 才来点重试的。所以这里不做"沿用当初那个模型"的假设。
     */
    fun retryMessage(target: UiMessage, omitAttachmentsAndTools: Boolean = false) {
        if (isStreaming || switching || target.role != MessageRole.ASSISTANT) return
        if ((target.imageGeneration || target.imageRequest != null) && !omitAttachmentsAndTools) { regenerate(target); return }
        val conversationId = currentConversationId ?: return
        viewModelScope.launch {
            if (!sendMutex.tryLock()) return@launch
            try {
                if (conversationId != currentConversationId || isStreaming || switching) return@launch
                repository.resetForRetry(target.id, settings.model.id)
                reloadPath(conversationId)
                startStream(conversationId, target.id, omitAttachmentsAndTools = omitAttachmentsAndTools)
            } finally {
                sendMutex.unlock()
            }
        }
    }

    /**
     * 重新生成**指定**的 AI 回复。
     *
     * 关键：新回复加在**同一个 slot** 上成为新版本，旧版本保留可回退，
     * 且其下游消息完全不受影响（设计文档 §4.1）。
     * 早先的实现按 `loadActiveMessages().size` 算了个新 slot，
     * 于是旧回复没被替换、新回复排到了队尾 —— 表现就是"一个接一个"。
     */
    fun regenerate(target: UiMessage) {
        if (target.imageGeneration || target.imageRequest != null) {
            val parent = messages.firstOrNull { it.id == target.parentId && it.role == MessageRole.USER } ?: return
            val request = target.imageRequest
            val referenceSource = request?.referenceMessageId?.let { id -> messages.firstOrNull { it.id == id } }
            generateImage(request?.prompt ?: parent.content, retryParentId = parent.id,
                references = when {
                    request?.usesReferences == false -> emptyList()
                    request?.referenceMessageId != null -> referenceSource?.attachments.orEmpty()
                    else -> parent.attachments
                },
                retryRequest = request)
            return
        }
        if (isStreaming || target.role != MessageRole.ASSISTANT) return
        val conversationId = currentConversationId ?: return
        val index = messages.indexOfFirst { it.id == target.id }
        if (index < 0) return
        if (compacting) cancelCompaction()

        val generation = switchGeneration
        viewModelScope.launch {
            if (!sendMutex.tryLock()) return@launch
            try {
                if (generation != switchGeneration || conversationId != currentConversationId || isStreaming || switching ||
                    messages.none { it.id == target.id }
                ) return@launch
                // 挂在**被重新生成那条的父亲**下 → 成为它的兄弟，旧回答留着
                val fresh = repository.addActiveVariant(
                    conversationId = conversationId,
                    versionOf = target.id,
                    parentId = target.parentId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    reasoning = "",
                    status = MessageStatus.STREAMING,
                )
                reloadPath(conversationId)
                startStream(conversationId, fresh.id)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = Res.get(R.string.error_send_failed, error.userMessage())
            } finally {
                sendMutex.unlock()
            }
        }
    }

    /**
     * 继续生成（续写）。
     *
     * 语义是「接着这条写下去」，不是"重新生成一段" —— 所以：
     * - **不新增版本**，就写在这一条里（与 [regenerate] 相反）；
     * - 已有内容一个字不动，新内容接在后面；
     * - 请求把这条 assistant 作为**末尾前缀**发出去，服务端据此续写。
     *
     * 准入规则见 [canContinueReply]：只有活跃路径的最后一条、被停止或被上限截断的
     * 扩展通道回复才允许续写。不满足就静默返回 —— 按钮本来也不该出现。
     */
    fun continueMessage(target: UiMessage) {
        if (target.imageGeneration || isStreaming || !supportsContinuation) return
        val conversationId = currentConversationId ?: return
        val index = messages.indexOfFirst { it.id == target.id }
        if (index < 0) return
        if (!canContinueReply(target, isLastOnPath = index == messages.lastIndex)) return
        if (compacting) cancelCompaction()

        val generation = switchGeneration
        viewModelScope.launch {
            if (!sendMutex.tryLock()) return@launch
            try {
                switchJob?.join()
                val currentIndex = messages.indexOfFirst { it.id == target.id }
                val current = messages.getOrNull(currentIndex)
                if (generation != switchGeneration || conversationId != currentConversationId || isStreaming || switching ||
                    current == null || !canContinueReply(current, isLastOnPath = currentIndex == messages.lastIndex)
                ) return@launch
                repository.resumeStreaming(target.id)
                patch(target.id) {
                    it.copy(
                        status = MessageStatus.STREAMING,
                        errorMessage = null,
                        stopReason = null,
                    )
                }
                startStream(conversationId, target.id, prefix = current)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = Res.get(R.string.error_send_failed, error.userMessage())
            } finally {
                sendMutex.unlock()
            }
        }
    }

    /**
     * 编辑一条消息。
     *
     * 两个出口，两条完全不同的路：
     *
     * - **[resend] = false（「保存」）**：**原地**改这一条的文字，不新增版本、不碰下游。
     *   用户改个错别字不该冒出 `‹ 1/2 ›`，更不该让会话下半段当场消失 ——
     *   早先这里和「保存并发送」共用 `addActiveVariant`，于是"保存"也长出一条
     *   没有子树的新分支，看起来就是"保存把后面的聊天删了"。
     *
     * - **[resend] = true（「保存并发送」）**：新版本挂在**原消息的父亲**下，成为它的
     *   **兄弟**；旧的那条连同它**整棵子树**原样留着，切回去就能看到。
     *   然后在新兄弟下再生成一条回复（分支 + 继续往下）。
     *
     * **两种角色都不删任何东西。** 这正是换成树模型换来的东西。
     */
    fun editMessage(target: UiMessage, newText: String, resend: Boolean, attachments: List<Attachment>? = null, onPersisted: () -> Unit = {}) {
        if (isStreaming || switching) return
        val text = newText.trim()
        val kept = attachments ?: target.attachments
        if (text.isEmpty() && kept.isEmpty()) return
        val conversationId = currentConversationId ?: return
        if (compacting) cancelCompaction()

        val generation = switchGeneration
        viewModelScope.launch {
            if (!sendMutex.tryLock()) return@launch
            try {
            if (generation != switchGeneration || conversationId != currentConversationId) return@launch
            if (!resend) {
                /*
                 * 「保存」：原地换文字。
                 *
                 * `timeline` 必须跟着换 —— 扩展通道的界面和请求都读它，只改 `content`
                 * 会出现"保存了却没变"（OpenAI 通道生效、Anthropic 通道不生效）。
                 * 思考 / 搜索 / 读链接块保持原序，它们描述的是这段答案的来历。
                 */
                if (compactionInvalidatedByEdit(messages.toList(), compaction, target.id)) {
                    val stale = compaction
                    if (stale != null) repository.deleteCompaction(stale.id)
                    compaction = null
                    runCatching { repository.deleteCompactionCheckpoint(conversationId) }
                        .onFailure { android.util.Log.w("SimpleChat/Compaction", "Stale checkpoint could not be deleted") }
                }
                repository.editMessageInPlace(
                    id = target.id,
                    content = text,
                    attachments = kept,
                    timeline = rewriteTimelineText(target.timeline, text),
                )
                if (generation != switchGeneration || conversationId != currentConversationId) return@launch
                onPersisted()
                reloadPath(conversationId)
                repository.touchConversation(conversationId)
                return@launch
            }
            val fresh = repository.addActiveVariant(
                conversationId = conversationId,
                versionOf = target.id,
                parentId = target.parentId,
                role = target.role,
                content = text,
                /*
                 * 附件跟着走。改文案不该把图弄丢 —— 那是最容易让人骂街的一种丢失：
                 * 用户只想改个错别字，回头发现图没了，而且旧版本也翻不回来。
                 */
                attachments = kept,
                // 重发时旧思考已与新回复无关（新回复会重新思考）
                reasoning = null,
            )
            if (generation != switchGeneration || conversationId != currentConversationId) return@launch
            onPersisted()

            // 回复挂在**新的**这条下面
            val replyId = repository.beginAssistantMessage(
                conversationId = conversationId,
                parentId = fresh.id,
                model = settings.model.id,
            )

            reloadPath(conversationId)
            startStream(conversationId, replyId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = Res.get(R.string.error_send_failed, error.userMessage())
            } finally { sendMutex.unlock() }
        }
    }

    /** 导出当前会话为 JSON；尚未落库的新对话返回 null。 */
    suspend fun exportCurrentConversation(): String? =
        currentConversationId?.let { repository.exportConversation(it) }

    /** 导出当前会话为 **Markdown 文稿**；尚未落库的新对话返回 null。 */
    suspend fun exportCurrentConversationMarkdown(): String? =
        currentConversationId?.let { repository.exportConversationMarkdown(it) }

    suspend fun exportConversationFile(file: java.io.File, conversationId: String, markdown: Boolean) {
        if (markdown) repository.exportMarkdown(file, conversationId)
        else repository.exportBackup(file, onlyConversation = conversationId)
    }

    // ── 未发送的输入草稿 ──────────────────────────────────

    /**
     * 读回草稿。[key] 是 `ChatScreen` 的 composer key —— 就是会话 id，
     * 还没建会话时是空串（`DraftEntity.NewConversation`）。
     */
    suspend fun loadDraft(key: String): String? = repository.findDraft(key)

    /** 落草稿。空文本即删行。 */
    suspend fun saveDraft(key: String, text: String) = repository.saveDraft(key, text)

    suspend fun clearDraft(key: String) = repository.clearDraft(key)

    /** Delete the selected message version, optionally including its sibling versions. */
    fun deleteMessage(target: UiMessage, onReplacement: (String?) -> Unit = {}) {
        mutateMessageHistory(setOf(target.id), onReplacement = onReplacement) { conversationId ->
            repository.deleteMessageVersions(conversationId, setOf(target.id))
            null
        }
    }

    fun refreshRestoredMessages(conversationId: String) {
        if (currentConversationId != conversationId) return
        viewModelScope.launch {
            sendMutex.lock()
            try {
                if (currentConversationId == conversationId && !isStreaming && !switching) reloadPath(conversationId)
            } finally {
                sendMutex.unlock()
            }
        }
    }

    fun deleteSelectedVersions(messageIds: List<String>, selectedGroupCount: Int, onReplacement: (String?) -> Unit = {}) {
        val ids = messageIds.toSet()
        mutateMessageHistory(ids, Res.get(R.string.share_delete_done, selectedGroupCount), onReplacement) { conversationId ->
            repository.deleteMessageVersions(conversationId, ids)
            null
        }
    }

    private fun mutateMessageHistory(
        ids: Set<String>,
        success: String? = null,
        onReplacement: (String?) -> Unit = {},
        delete: suspend (String) -> String?,
    ) {
        if (isStreaming || switching || ids.isEmpty()) return
        val conversationId = currentConversationId ?: return
        val generation = switchGeneration
        viewModelScope.launch {
            if (!sendMutex.tryLock()) return@launch
            try {
                if (generation != switchGeneration || isStreaming || switching ||
                    !messages.mapTo(HashSet()) { it.id }.containsAll(ids)
                ) return@launch
                if (compacting) cancelCompaction()
                val replacementId = delete(conversationId)
                val savedCompaction = repository.getCompactions(conversationId).lastOrNull()
                if (generation != switchGeneration) return@launch
                reloadPath(conversationId) { onReplacement(replacementId) }
                if (generation == switchGeneration) {
                    compaction = savedCompaction
                    if (success != null) banner = success
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == switchGeneration) banner = error.userMessage()
            } finally {
                sendMutex.unlock()
            }
        }
    }

    /**
     * 「从这里新建对话」（§21）。
     *
     * 把活跃路径到 [target] 为止的一段复制成一个**独立新会话**，然后打开它。
     * **原会话一个字节不动** —— 想回头，它就在列表里躺着。
     *
     * 这是**会话之间**的动作；会话**内部**的分支由「重新生成 / 编辑重发」覆盖
     * （它俩是同一个父节点下的兄弟，靠 `‹ n/m ›` 切）—— 那件事不需要再开一个入口。
     */
    fun forkFrom(target: UiMessage) {
        if (isStreaming) return
        viewModelScope.launch {
            val newId = repository.forkConversation(target.id) ?: return@launch
            openConversation(newId)
        }
    }

    /**
     * 切到同父的另一个版本。
     *
     * 路径会**整段重建** —— 旧分支的下游跟着回来，新分支的下游跟着消失。
     * 这就是"分支切换"在树模型下的天然行为。
     */
    fun switchVariant(target: UiMessage, variantIndex: Int, onReplacement: (String) -> Unit = {}) {
        if (isStreaming || switching) return
        val conversationId = currentConversationId ?: return
        if (variantIndex !in 0 until target.variantCount) return
        val generation = switchGeneration
        viewModelScope.launch {
            if (!sendMutex.tryLock()) return@launch
            try {
                if (generation != switchGeneration || isStreaming || switching || messages.none { it.id == target.id }) return@launch
                if (compacting) cancelCompaction()
                val replacementId = repository.activateVariantAt(conversationId, target.parentId, variantIndex) ?: return@launch
                if (generation != switchGeneration) return@launch
                reloadPath(conversationId) { onReplacement(replacementId) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == switchGeneration) banner = error.userMessage()
            } finally {
                sendMutex.unlock()
            }
        }
    }

    fun stop() {
        if (!isStreaming) return
        stoppedByUser = true
        ++streamGeneration
        isStreaming = false
        streamJob?.cancel()
        streamJob = null
        // 用户主动停止：前台服务立刻收掉，不留一条"正在生成"的通知
        BackgroundGeneration.sync(false)
    }

    private suspend fun executeImageRequest(request: ImageRequest, route: StoredSettings, references: List<Attachment>): com.simplechat.app.net.GeneratedImage {
        val config = route.imageGenApi
        val nativeAdapter = route.nativeImageAdapter(request.modelId)
        if (request.independent && (!config.enabled || config.adapter != request.adapter) ||
            !request.independent && nativeAdapter != request.adapter) {
            throw com.simplechat.app.data.AppTextException(R.string.image_model_unavailable)
        }
        val images = if (request.usesReferences) repository.materialize(references.filter { it.isImage }) else emptyList()
        if (request.usesReferences && (images.isEmpty() || images.any { it.data.isBlank() || it.remoteImage })) {
            throw com.simplechat.app.data.AppTextException(R.string.image_reference_unreadable)
        }
        val options = config.copy(
            modelId = request.modelId, size = request.size ?: config.size,
            aspectRatio = request.aspectRatio ?: config.aspectRatio,
            geminiImageSize = request.geminiImageSize ?: config.geminiImageSize,
        )
        return when {
            request.independent -> imageGenerationApi.generateIndependent(options, route.baseUrl, route.apiKey, request.prompt, images)
            request.adapter == "GEMINI" -> imageGenerationApi.generateGemini(
                options.copy(baseUrl = "", reuseChatKey = true), route.baseUrl, route.apiKey, request.prompt, images,
            )
            else -> imageGenerationApi.generateWithResponses(route.baseUrl, route.apiKey, request.modelId, request.prompt, images, request.size)
        }
    }

    fun generateImage(
        prompt: String, requestedAspectRatio: String? = null, retryParentId: String? = null,
        references: List<Attachment> = emptyList(), requestedModelId: String? = null,
        retryRequest: ImageRequest? = null, onPersisted: () -> Unit = {},
    ) {
        val description = prompt.trim()
        if (description.isEmpty() || isStreaming || switching) return
        val route = stored
        val selectedModel = retryRequest?.modelId ?: requestedModelId?.takeIf { it.isNotBlank() }
        val nativeModel = selectedModel ?: settings.model.id
        val nativeAdapter = route.nativeImageAdapter(nativeModel)
        val adapter = if (route.imageGenApi.enabled) route.imageGenApi.adapter else nativeAdapter
        val outputModel = if (route.imageGenApi.enabled) selectedModel ?: route.imageGenApi.modelId else nativeModel
        val ratio = requestedAspectRatio?.takeIf { it in imageAspectRatios(adapter, outputModel) }
        val explicitSize = imageSizeForRatio(adapter, outputModel, ratio)
        val size = explicitSize ?: route.imageGenApi.size
        val imageConfig = route.imageGenApi.copy(
            modelId = selectedModel ?: route.imageGenApi.modelId,
            size = size, aspectRatio = ratio ?: route.imageGenApi.aspectRatio,
        )
        if (retryRequest == null && !imageConfig.enabled && nativeAdapter == null) {
            banner = Res.get(R.string.image_model_unavailable)
            return
        }
        if (retryRequest == null && imageConfig.enabled && imageConfig.modelId.isBlank()) {
            banner = Res.get(R.string.error_no_model)
            return
        }
        if (!(retryRequest?.independent ?: imageConfig.enabled) && route.apiKey.isBlank()) {
            banner = Res.get(R.string.error_no_api_key)
            return
        }
        val generation = ++streamGeneration
        val viewGeneration = switchGeneration
        val imageModel = retryRequest?.modelId ?: if (imageConfig.enabled) imageConfig.modelId else nativeModel
        val request = retryRequest ?: ImageRequest(
            prompt = description, modelId = imageModel,
            adapter = if (imageConfig.enabled) imageConfig.adapter else checkNotNull(nativeAdapter),
            independent = imageConfig.enabled,
            size = if (imageConfig.enabled) size else explicitSize,
            aspectRatio = imageConfig.aspectRatio, geminiImageSize = imageConfig.geminiImageSize,
            usesReferences = references.any { it.isImage },
        )
        if (compacting) cancelCompaction()
        isStreaming = true
        BackgroundGeneration.sync(true)
        stoppedByUser = false
        banner = null
        streamJob = viewModelScope.launch {
            var replyId: String? = null
            try {
                if (!sendMutex.tryLock()) return@launch
                val id: String
                val reply: String
                try {
                    id = ensureConversation(viewGeneration) ?: return@launch
                    val parentId = retryParentId ?: repository.appendUserMessage(id, description, references).also { user ->
                        if (viewGeneration == switchGeneration) messages += user
                    }.id
                    ensureActive()
                    reply = repository.beginAssistantMessage(id, parentId, imageModel, imageGeneration = true, imageRequest = request)
                    replyId = reply
                    ensureActive()
                    if (viewGeneration != switchGeneration) return@launch
                    if (retryParentId != null) {
                        reloadPath(id)
                    } else messages += UiMessage(
                        id = reply, role = MessageRole.ASSISTANT, content = "", status = MessageStatus.STREAMING,
                        parentId = parentId, model = imageModel, imageGeneration = true, imageRequest = request,
                    )
                    onPersisted()
                } finally {
                    sendMutex.unlock()
                }
                val generated = executeImageRequest(request, route, references)
                if (generation != streamGeneration) throw kotlinx.coroutines.CancellationException("Image generation stopped")
                val attachment = repository.attachGeneratedImage(reply, generated.bytes, markDone = true)
                patch(reply) { it.copy(attachments = listOf(attachment), status = MessageStatus.DONE) }
                runCatching { repository.touchConversation(id) }
            } catch (error: kotlinx.coroutines.CancellationException) {
                replyId?.let { id ->
                    withContext(NonCancellable) {
                        val stopped = runCatching { repository.settleImageGeneration(id, MessageStatus.STOPPED) }.getOrDefault(false)
                        if (stopped) patch(id) { it.copy(status = MessageStatus.STOPPED) }
                    }
                }
                throw error
            } catch (error: Exception) {
                replyId?.let { id ->
                    val message = error.message ?: "Image generation failed"
                    val failed = runCatching { repository.settleImageGeneration(id, MessageStatus.ERROR, message) }.getOrDefault(false)
                    if (failed) patch(id) { it.copy(status = MessageStatus.ERROR, errorMessage = message) }
                } ?: run { banner = error.message ?: "Image generation failed" }
            } finally {
                if (generation == streamGeneration) {
                    isStreaming = false
                    streamJob = null
                    BackgroundGeneration.sync(false)
                }
            }
        }
    }

    /**
     * ViewModel 被回收（退出对话页 / Activity 结束）。
     *
     * 流随 `viewModelScope` 一起取消，前台服务也必须收掉 ——
     * 否则会留下一条永远"正在生成"的通知。
     */
    override fun onCleared() {
        BackgroundGeneration.sync(false)
        super.onCleared()
    }

    /**
     * 请求**还没发出去**就失败的收尾。
     *
     * 与流式里的失败走同一套表现（回复上挂红字 + 重试按钮），但不必经过网络层：
     * 空的流式气泡留在界面上等下一个 token，用户只会以为"卡住了"。
     */
    private fun rejectBeforeSend(replyId: String, message: String) {
        val conversationId = currentConversationId ?: return
        patch(replyId) { it.copy(status = MessageStatus.ERROR, errorMessage = message) }
        viewModelScope.launch {
            runCatching {
                repository.finishMessage(
                    id = replyId,
                    content = "",
                    reasoning = null,
                    status = MessageStatus.ERROR,
                    errorMessage = message,
                )
                repository.touchConversation(conversationId)
            }
        }
    }

    // ── 流式实现 ──────────────────────────────────────────

    private fun startStream(
        conversationId: String,
        replyId: String,
        prefix: UiMessage? = null,
        omitAttachmentsAndTools: Boolean = false,
    ) {
        if (conversationId != currentConversationId) return
        retrySkipNotice = if (omitAttachmentsAndTools && stored.showRetrySkipNotice) Res.get(R.string.retry_skipped_notice) else null
        lastUsage = null
        /*
         * ★ **发请求之前先验 Key 的字符集。**
         *
         * `Authorization` 头只接受可见 ASCII。Key 里混进中文 / 全角 / 换行时，
         * OkHttp 抛的是 `Unexpected char 0x4f55 at 8 in Authorization value` ——
         * 码点是第一个非法字符，用户既不知道是哪个字段，也不知道该改什么。
         * 在这里先拦下，把这条回复直接标成错误，文案说清楚去哪儿改。
         */
        if (!ApiKey.isHeaderSafe(stored.apiKey)) {
            rejectBeforeSend(replyId = replyId, message = Res.get(R.string.error_api_key_chars))
            return
        }
        val generation = ++streamGeneration
        if (prefix == null) {
            val phrase = stored.loadingWords.randomOrNull() ?: Res.strings(R.array.reply_waiting_phrases).random()
            loadingLabel = replyId to phrase
        }
        isStreaming = true
        // 生成期间把进程提到前台服务优先级：退到后台 / 熄屏也继续收
        BackgroundGeneration.sync(true)
        stoppedByUser = false
        val routeProvider = providerKind
        val baseUrl = stored.baseUrl.ifBlank { routeProvider.defaultBaseUrl }
        val wireModelId = effectiveModelId(baseUrl, settings.model.id)
        val options = settings.copy(model = settings.model.copy(id = wireModelId))
        val route = stored.copy(baseUrl = baseUrl, modelId = wireModelId)
        val latestUser = messages.lastOrNull { it.role == MessageRole.USER }
        val latestQuestion = latestUser?.content.orEmpty()
        val explicitReference = mentionsRecentGeneratedImage(latestQuestion)
        val previousImage = latestUser?.let { recentGeneratedImage(messages, it.id, explicitReference) }
        val attachedImages = latestUser?.attachments.orEmpty().filter { it.isImage }
        val imageIntent = if (prefix == null && !omitAttachmentsAndTools) {
            imageGenerationIntent(latestQuestion, attachedImages.isNotEmpty() || previousImage != null)
        } else ImageGenerationIntent.NONE
        val inheritedImage = previousImage.takeIf { attachedImages.isEmpty() }
        val editReferences = attachedImages.ifEmpty { inheritedImage?.attachments.orEmpty().filter { it.isImage } }
        val discussingImage = imageIntent == ImageGenerationIntent.NONE && explicitReference
        val visualFollowUp = previousImage?.attachments?.firstOrNull { it.isImage }?.takeIf {
            prefix == null && options.model.supportsVision && attachedImages.isEmpty() &&
                (discussingImage || imageIntent == ImageGenerationIntent.EDIT)
        }
        val wantsImage = imageIntent == ImageGenerationIntent.CREATE || imageIntent == ImageGenerationIntent.EDIT
        val nativeImageAdapter = route.nativeImageAdapter(options.model.id)
        val imageRouteAvailable = if (route.imageGenApi.enabled) route.imageGenApi.modelId.isNotBlank() else nativeImageAdapter != null
        val nativeGeminiTurn = wantsImage && !route.imageGenApi.enabled && nativeImageAdapter == "GEMINI"
        val selectedApiStyle = apiStyle
        val extended = selectedApiStyle == ApiStyle.ANTHROPIC

        val automaticSearch = com.simplechat.app.net.isOfficialOpenAiEndpoint(baseUrl) &&
            com.simplechat.app.net.usesAutomaticOpenAiSearch(wireModelId, selectedApiStyle)
        // The model decides intent when a client image tool is available.
        val imageToolEnabled = imageRouteAvailable && !nativeGeminiTurn && !automaticSearch &&
            prefix == null && !omitAttachmentsAndTools
        val searchRoute = AgentSearchRoute.resolve(baseUrl, wireModelId, selectedApiStyle, imageToolEnabled, route.hasConfiguredSearchApi)
        val contextualEdit = requestsContextualImageEdit(latestQuestion)
        val turnId = java.util.UUID.randomUUID().toString()
        val turnDate = java.time.LocalDate.now()
        fun liveSearchSwitch(): Boolean = stored.activeApiProfileId == route.activeApiProfileId && webSearch
        Diagnostics.log("AGENT", "turn=$turnId start profile=${diagnosticToken(route.activeApiProfileId)} " +
            "model=${diagnosticToken(wireModelId)} protocol=$selectedApiStyle route=$searchRoute switch=${liveSearchSwitch()}")
        if (imageIntent != ImageGenerationIntent.NONE || discussingImage) {
            Diagnostics.log("IMG", "intent=$imageIntent route=$imageRouteAvailable direct=$nativeGeminiTurn " +
                "tool=$imageToolEnabled references=${editReferences.size} vision=${visualFollowUp != null}")
        }
        streamJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var lastCheckpointAt = startedAt
            var firstTokenAt: Long? = null
            var contentChars = 0
            var optionalFieldsSent = false
            var canDegrade = false
            var pendingImageTool = false
            var generatedImageAttached = false
            var imageToolFailed = false
            var imageCallHandled = false
            var agentTranscript: AgentTranscript? = null
            var agentAttempt = 0
            var imageFallback: ImageRequest? = null
            var localAnswer: String? = null
            var contextRejection: CompactionRequestFailure? = null

            /*
             * 思维链的起止时刻。
             *
             * 用来算**真实思考时长** —— 先前拿的是"整条回复的总时长"，
             * 把写正文的时间也算进了「已深度思考 N 秒」里。
             */
            var reasoningStartedAt: Long? = null
            var reasoningEndedAt: Long? = null

            var reasoningText = ""
            var contentText = ""
            val reasonBuf = StringBuilder()
            var hasReasoningText = false
            val contentBuf = StringBuilder()

            /*
             * 扩展通道的块累加器。
             *
             * OpenAI 通道的回复没有"结构"（只有正文 + 思考两股流），所以这里为 null，
             * 走原来的 StringBuilder 路径。两条通道只在**收事件**时分叉，
             * 落盘、节流、性能统计全部共用，避免为了一个功能复制一整套流式调度。
             */
            val accumulator = if (extended) ReplyAccumulator() else null
            // 续写：以已有块开局，新内容往后面接（见 ReplyAccumulator.seed）
            if (accumulator != null && prefix != null) accumulator.seed(prefix.timeline)
            var timeline: List<ReplyBlock> = prefix?.timeline.orEmpty()
            var research = com.simplechat.app.net.SearchAugmentation()
            val citations = linkedMapOf<String, com.simplechat.app.net.Citation>()
            var dirty = false

            fun refreshBuffered() {
                if (accumulator != null) {
                    timeline = accumulator.blocks
                    reasoningText = accumulator.thinking
                    contentText = accumulator.answer
                } else {
                    if (hasReasoningText && reasonBuf.length != reasoningText.length) reasoningText = reasonBuf.toString()
                    if (contentBuf.length != contentText.length) contentText = contentBuf.toString()
                    if (research.blocks.isNotEmpty() || citations.isNotEmpty()) {
                        timeline = research.replyBlocks(reasoningText, contentText, reasoningStartedAt, reasoningEndedAt).map { block ->
                            if (block is ReplyBlock.Text) block.copy(citations = citations.values.toList()) else block
                        }
                    }
                }
            }

            /** 正文（或正文块）一出现 = 思维链已经写完，思考栏当场收口。 */
            fun noteBody() {
                if (reasoningEndedAt != null) return
                val endedAt = SystemClock.elapsedRealtime()
                firstTokenAt = endedAt
                reasoningEndedAt = endedAt
                val ms = reasoningStartedAt?.let { endedAt - it }
                patch(replyId) {
                    it.copy(reasoningDone = true, reasoningSeconds = ms?.let(::thinkingSeconds) ?: prefix?.reasoningSeconds)
                }
            }

            fun noteThinking() {
                if (reasoningStartedAt == null && reasoningEndedAt == null) {
                    reasoningStartedAt = SystemClock.elapsedRealtime()
                }
            }

            // 节流提交器：与 collect 同在主线程调度器，天然串行，无需加锁
            val flusher = launch {
                var checkpointJob: Job? = null
                var needsCheckpoint = false
                while (isActive) {
                    delay(STREAM_THROTTLE_MS)
                    if (dirty) {
                        refreshBuffered()
                        patch(replyId) { it.copy(reasoning = reasoningText.ifEmpty { null }, content = contentText, timeline = timeline) }
                        dirty = false
                        needsCheckpoint = true
                    }

                    val now = SystemClock.elapsedRealtime()
                    if (needsCheckpoint && checkpointJob?.isCompleted != false && now - lastCheckpointAt >= CHECKPOINT_INTERVAL_MS) {
                        lastCheckpointAt = now
                        needsCheckpoint = false
                        val r = reasoningText
                        val c = contentText
                        val t = timeline
                        checkpointJob = launch {
                            withContext(NonCancellable + Dispatchers.IO) {
                                runCatching { repository.checkpoint(replyId, c, r, t) }
                            }
                        }
                    }
                }
            }

            fun sendError(event: StreamEvent.Error): String {
                val hint = com.simplechat.app.net.providerHintRes(event.rawMessage ?: event.message)
                android.util.Log.w("SimpleChat/Send", "protocol=$apiStyle http=${event.code} category=${Res.nameOrNull(hint)} retryable=${event.retryable}")
                val rejected = compactionFailure(event)
                if (rejected.kind == CompactionFailureKind.INPUT_LIMIT) contextRejection = rejected
                if (optionalFieldsSent &&
                    com.simplechat.app.net.canRetryWithoutAttachmentsAndTools(event.code, event.rawMessage ?: event.message)
                ) canDegrade = true
                return if (event.code != null) event.message else serverErrorText(event.message)
            }
            var failure: String? = null
            var cancelled = false
            var serverTitle: String? = null

            fun turnImageRequest(prompt: String, edit: Boolean): ImageRequest {
                val config = route.imageGenApi
                return ImageRequest(
                    prompt = prompt, modelId = if (config.enabled) config.modelId else options.model.id,
                    adapter = if (config.enabled) config.adapter else checkNotNull(nativeImageAdapter),
                    independent = config.enabled, size = config.size.takeIf { config.enabled },
                    aspectRatio = config.aspectRatio, geminiImageSize = config.geminiImageSize,
                    usesReferences = edit, referenceMessageId = inheritedImage?.id.takeIf { edit },
                )
            }

            suspend fun generateTurnImage(request: ImageRequest) {
                canDegrade = false
                pendingImageTool = true
                noteBody()
                val imageReferences = if (request.usesReferences) editReferences else emptyList()
                repository.markImageGeneration(replyId, request)
                patch(replyId) { it.copy(imageGeneration = true, imageRequest = request) }
                Diagnostics.log("IMG", "request adapter=${request.adapter} references=${imageReferences.size}")
                val result = executeImageRequest(request, route, imageReferences)
                ensureActive()
                val attachment = repository.attachGeneratedImage(replyId, result.bytes)
                generatedImageAttached = true
                patch(replyId) { it.copy(attachments = listOf(attachment), imageGeneration = true) }
                Diagnostics.log("IMG", "attached")
                dirty = true
            }

            var memorySnapshot: String? = null
            suspend fun streamAttempt(textOnly: Boolean) {
                val systemContext = buildString {
                    if (searchRoute != AgentSearchRoute.UNAVAILABLE) {
                        append("The local date for interpreting relative dates in this turn is $turnDate. Preserve explicit dates from the active conversation.")
                    }
                    if (discussingImage && previousImage != null && !options.model.supportsVision) {
                        if (isNotEmpty()) append("\n\n")
                        append("The user refers to an image generated earlier in this chat. This chat model cannot view the image pixels. Do not claim to have seen the image or deny that the app generated it.")
                    }
                }
                if (nativeGeminiTurn && !textOnly) {
                    generateTurnImage(turnImageRequest(latestQuestion, imageIntent == ImageGenerationIntent.EDIT || attachedImages.isNotEmpty()))
                    return
                }
                val imageTool = ImageToolCall()
                val attempt = ++agentAttempt
                var activeRound = 0
                fun agentLog(round: Int, event: String) {
                    activeRound = round
                    Diagnostics.log("AGENT", "turn=$turnId attempt=$attempt round=$round $event")
                }
                val runImageTool: (suspend (com.simplechat.app.net.ImageToolInvocation) -> AgentToolOutcome)? =
                    if (imageToolEnabled && !textOnly) { invocation ->
                        val edit = invocation.operation == ImageToolOperation.EDIT ||
                            contextualEdit && (explicitReference || editReferences.isNotEmpty())
                        when {
                            blocksImageToolExecution(latestQuestion) -> AgentToolOutcome(AgentToolStatus.BLOCKED, "No image was generated. The request was explicitly non-generative or only asked about capability.")
                            edit && editReferences.isEmpty() -> AgentToolOutcome(AgentToolStatus.INVALID_ARGUMENTS, "No image was generated. Ask the user to attach the original image.")
                            else -> {
                                accumulator?.seed(accumulator.blocks.filterNot { it is ReplyBlock.Text })
                                contentBuf.clear()
                                refreshBuffered()
                                patch(replyId) { it.copy(content = contentText, timeline = timeline) }
                                try {
                                    generateTurnImage(turnImageRequest(invocation.prompt, edit))
                                    AgentToolOutcome.success("Image generated and attached to this reply by SimpleChat. No image pixels were sent to the chat model.")
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    imageToolFailed = true
                                    Diagnostics.log("IMG", "tool request failed: ${error.javaClass.simpleName}")
                                    AgentToolOutcome(AgentToolStatus.FAILED, "Image generation failed. No image was attached. Tell the user they can retry.")
                                }
                            }
                        }
                    } else null
                val webSession = if (!textOnly && prefix == null && searchRoute == AgentSearchRoute.INDEPENDENT) {
                    WebResearchSession(route.copy(baseUrl = route.baseUrl.ifBlank { routeProvider.defaultBaseUrl }), latestQuestion,
                        recentTurns = messages.filter { it.role == MessageRole.USER && it.id != latestUser?.id }
                            .takeLast(3).map { "user" to it.content.take(700) }) { blocks ->
                        val added = blocks.drop(research.blocks.size)
                        research = research.copy(blocks = blocks)
                        accumulator?.seed(accumulator.blocks + added)
                        refreshBuffered()
                        patch(replyId) { it.copy(timeline = timeline) }
                    }
                } else null
                val nativeQueries = mutableSetOf<String>()
                val pageUrls = HttpLink.findAll(latestQuestion).take(6).mapNotNull {
                    com.simplechat.app.net.publicPage(it.value.trimEnd('.', ',', '。', '，', ')', '）'))
                }.toSet()
                suspend fun lookupThroughFlash(input: String, fetch: Boolean): AgentToolOutcome {
                    return if (nativeQueries.size >= 3 || !nativeQueries.add(if (fetch) input else input.lowercase())) {
                        AgentToolOutcome(AgentToolStatus.BUDGET_EXCEEDED, "Web request already attempted or budget exhausted.")
                    } else {
                        val evidence = ReplyAccumulator()
                        val baseUrl = route.baseUrl.ifBlank { routeProvider.defaultBaseUrl }
                        val headers = routeProvider.headersFor(openCodeSessionId)
                        val instruction = if (fetch) "Read the supplied public URL. Return concise evidence from the page."
                            else "Use native web search for the supplied public query. Return concise evidence with source URLs."
                        val lookupRequest = buildAnthropicRequest(
                            model = options.model.id, systemPrompt = instruction,
                            path = listOf(UiMessage("web-lookup", MessageRole.USER, input)),
                            searchEnabled = !fetch, thinkingEnabled = false, requestTitle = false,
                            latestReminder = null, maxTokens = 1024, deepSeekExtensions = true,
                        )
                        var lookupFailed = false
                        var lookupStop: String? = null
                        val completed = kotlinx.coroutines.withTimeoutOrNull(35_000) {
                            agentLog(activeRound, "bridge wire protocol=ANTHROPIC tools=${lookupRequest.tools.joinToString(",") { it.name }} choice=-")
                            chatApi.streamMessage(baseUrl, route.apiKey, lookupRequest, extraHeaders = headers,
                                retryBeforeContent = false).collect { event ->
                                if (event is StreamEvent.Error) lookupFailed = true
                                if (event is StreamEvent.Stopped) lookupStop = event.reason
                                evidence.consume(event, SystemClock.elapsedRealtime())
                            }
                            true
                        }
                        agentLog(activeRound, "bridge response stop=${diagnosticToken(lookupStop)} error=$lookupFailed timeout=${completed != true}")
                        val blocks = evidence.blocks.filter { it is ReplyBlock.ToolUse || it is ReplyBlock.SearchResult || it is ReplyBlock.FetchResult }
                        if (blocks.isNotEmpty()) {
                            research = research.copy(blocks = research.blocks + blocks)
                            accumulator?.seed(accumulator.blocks + blocks)
                            refreshBuffered()
                            patch(replyId) { it.copy(timeline = timeline) }
                        }
                        if (completed == true && !lookupFailed && fetch &&
                            blocks.filterIsInstance<ReplyBlock.FetchResult>().any { !it.failed } && evidence.answer.isNotBlank()) {
                            AgentToolOutcome.success(evidence.answer.take(4000) + "\nSource: $input")
                        } else if (!fetch && completed == true && !lookupFailed && com.simplechat.app.net.hasSuccessfulWebSearch(blocks)) {
                            val sources = blocks.filterIsInstance<ReplyBlock.SearchResult>().flatMap { it.results }
                                .distinctBy { it.url }.take(12)
                            AgentToolOutcome.success(evidence.answer.take(4000) + "\nSources:\n" + sources.joinToString("\n") {
                                "${it.url} ${it.title.orEmpty().take(160)} ${it.snippet.orEmpty().take(300)}"
                            })
                        } else if (completed != true || lookupFailed || blocks.any {
                                it is ReplyBlock.SearchResult && it.failed || it is ReplyBlock.FetchResult && it.failed
                            }) {
                            AgentToolOutcome(AgentToolStatus.FAILED, "The native web request failed; no evidence was obtained.")
                        } else {
                            AgentToolOutcome(AgentToolStatus.EMPTY, "The native web tool returned no usable evidence.")
                        }
                    }
                }
                val searchThroughFlash: (suspend (String) -> AgentToolOutcome)? = if (!textOnly && searchRoute == AgentSearchRoute.FLASH_BRIDGE) { query ->
                    lookupThroughFlash(query, fetch = false)
                } else null
                val fetchThroughFlash: (suspend (String) -> AgentToolOutcome)? = if (!textOnly && route.officialFlash && imageToolEnabled &&
                    webSession == null && pageUrls.isNotEmpty()) { raw ->
                    val url = com.simplechat.app.net.publicPage(raw)?.takeIf { it in pageUrls }
                    if (url == null) AgentToolOutcome(AgentToolStatus.BLOCKED, "Only public URLs supplied in the current request can be read.")
                    else lookupThroughFlash(url, fetch = true)
                } else null
                val nativeReadAvailable = !textOnly && route.officialFlash && !imageToolEnabled &&
                    HttpLink.containsMatchIn(latestQuestion)
                fun runtimeState(): AgentRuntimeState {
                    val switchOn = liveSearchSwitch()
                    val clientSearch = !textOnly && switchOn && (webSession != null || searchThroughFlash != null)
                    val clientFetch = !textOnly && switchOn && (webSession != null || fetchThroughFlash != null)
                    val nativeSearch = !textOnly && switchOn && searchRoute.native
                    val automatic = !textOnly && automaticSearch
                    return AgentRuntimeState(
                        webConfigured = searchRoute != AgentSearchRoute.UNAVAILABLE,
                        webFetchEnabled = clientFetch,
                        imageConfigured = imageRouteAvailable,
                        imageExposed = imageToolEnabled && !textOnly,
                        clientSearchEnabled = clientSearch,
                        webSwitchOn = switchOn,
                        automaticSearchEnabled = automatic,
                        nativeSearchEnabled = nativeSearch,
                        nativeFetchEnabled = switchOn && nativeReadAvailable,
                    )
                }
                val answerStart = accumulator?.blocks.orEmpty()
                val plainAnswerStart = contentBuf.length
                val reasoningStart = reasonBuf.length
                fun discardStaleSearchAnswer() {
                    accumulator?.seed(answerStart)
                    contentBuf.setLength(plainAnswerStart)
                    reasonBuf.setLength(reasoningStart)
                    hasReasoningText = reasonBuf.isNotBlank()
                    if (!hasReasoningText && accumulator == null) reasoningText = ""
                    citations.clear()
                    contentChars = 0
                    firstTokenAt = null
                    reasoningStartedAt = null
                    reasoningEndedAt = null
                    refreshBuffered()
                    patch(replyId) { it.copy(content = contentText, reasoning = reasoningText.ifEmpty { null }, timeline = timeline) }
                    dirty = true
                }
                fun resolveSearchAnswerFailure() {
                    discardStaleSearchAnswer()
                    failure = Res.get(R.string.search_answer_unresolved)
                }
                val memoryText = memorySnapshot ?: crossChatMemoryFor(route, conversationId, latestQuestion)
                    .also { memorySnapshot = it }
                if (accumulator != null) {
                    val request = buildAnthropicRequestNow(
                        prefix, systemContext, route, options, searchRoute == AgentSearchRoute.FLASH_NATIVE && !textOnly, textOnly, memoryText,
                        generateImageTool = imageToolEnabled && !textOnly,
                        visualFollowUp = visualFollowUp,
                    )
                    val hasAttachments = !textOnly && request.messages.any {
                        it.role == "user" && it.content is kotlinx.serialization.json.JsonArray
                    }
                    agentLoop(
                        webSession, selectedApiStyle, runImageTool, searchThroughFlash, fetchThroughFlash,
                        runtime = ::runtimeState, onSearchAnswerRetry = ::discardStaleSearchAnswer,
                        onSearchAnswerUnresolved = ::resolveSearchAnswerFailure, observe = ::agentLog,
                    ) { history, offer, _ ->
                        val prepared = history.anthropic(request, offer, fetch = history.requestState.webFetchEnabled,
                            search = history.requestState.clientSearchEnabled)
                        optionalFieldsSent = prepared.tools.isNotEmpty() || prepared.title != null || hasAttachments
                        agentLog(history.round, "wire tools=${prepared.tools.joinToString(",") { it.name }} choice=-")
                        agentTranscript = history.transcript
                        imageCallHandled = imageCallHandled || history.imageHandled
                        chatApi.streamMessage(
                        baseUrl = route.baseUrl.ifBlank { routeProvider.defaultBaseUrl },
                        apiKey = route.apiKey,
                        request = prepared,
                        extraHeaders = routeProvider.headersFor(openCodeSessionId) +
                            if (!textOnly && route.supportsFileReferences && (route.filesApi.enabled || messages.any { message -> message.attachments.any { it.fileId != null } }))
                                mapOf("anthropic-beta" to "files-api-2025-04-14") else emptyMap(),
                        contextWindow = options.model.contextWindow,
                        retryBeforeContent = !history.requestState.webEnabled && !history.requestState.nativeFetchEnabled,
                    ) }.collect { event ->
                        when (event) {
                            is StreamEvent.BlockStart -> {
                                if (event.block is ReplyBlock.Text) noteBody()
                                if (event.block is ReplyBlock.Thinking) noteThinking()
                                accumulator.consume(event, SystemClock.elapsedRealtime())
                                dirty = true
                            }

                            is StreamEvent.TextDelta -> {
                                if (event.text.isNotEmpty()) {
                                    noteBody()
                                    contentChars += event.text.length
                                }
                                accumulator.consume(event, SystemClock.elapsedRealtime())
                                dirty = true
                            }

                            is StreamEvent.ThinkingDelta -> {
                                noteThinking()
                                accumulator.consume(event, SystemClock.elapsedRealtime())
                                dirty = true
                            }

                            is StreamEvent.Title -> serverTitle = event.text

                            is StreamEvent.CitationDelta, is StreamEvent.SignatureDelta,
                            is StreamEvent.BlockStop, is StreamEvent.Stopped -> {
                                imageTool.consume(event)
                                accumulator.consume(event, SystemClock.elapsedRealtime())
                                dirty = true
                            }

                            is StreamEvent.Usage -> recordUsage(event)

                            is StreamEvent.ToolCallDelta -> imageTool.consume(event)

                            StreamEvent.Done -> Unit

                            // 流内错误对象是**上游网关原文**（如 `No available channel …`）：
                            // 先翻成人话，认不出才保留原文（见 `serverErrorText`）
                            is StreamEvent.Error -> {
                                Diagnostics.log(
                                    "CHAT",
                                    "stream error code=${event.code} " +
                                        "retryable=${event.retryable} msg=${event.message.take(300)}",
                                )
                                failure = sendError(event)
                            }

                            // 块通道不该出现这两类增量；出现了说明通道配错，忽略
                            is StreamEvent.Content, is StreamEvent.Reasoning -> Unit
                        }
                    }
                } else {
                    val outgoing = buildRequestMessages(systemContext, route, options, textOnly, memoryText, visualFollowUp)
                    val builtInFlash = route.officialFlash && !textOnly
                    val readLink = builtInFlash && HttpLink.containsMatchIn(latestQuestion)
                    val title = builtInFlash && shouldRequestTitle()
                    val search = builtInFlash && searchRoute == AgentSearchRoute.FLASH_NATIVE
                    val openAiSearch = !textOnly && searchRoute == AgentSearchRoute.RESPONSES_NATIVE
                    val hasAttachments = !textOnly && outgoing.any { it.content is kotlinx.serialization.json.JsonArray }
                    agentLoop(
                        webSession, selectedApiStyle, runImageTool, searchThroughFlash, fetchThroughFlash,
                        runtime = ::runtimeState, onSearchAnswerRetry = ::discardStaleSearchAnswer,
                        onSearchAnswerUnresolved = ::resolveSearchAnswerFailure, observe = ::agentLog,
                    ) { history, offer, onOutput ->
                        agentTranscript = history.transcript
                        imageCallHandled = imageCallHandled || history.imageHandled
                        chatApi.streamChat(
                        apiStyle = selectedApiStyle,
                        baseUrl = route.baseUrl.ifBlank { routeProvider.defaultBaseUrl },
                        apiKey = route.apiKey,
                        model = options.model,
                        messages = outgoing + history.chat,
                        independentWeb = offer,
                        independentFetch = history.requestState.webFetchEnabled,
                        independentSearch = history.requestState.clientSearchEnabled,
                        agentStateInstruction = history.runtimeInstruction,
                        automaticWebSearchAllowed = history.requestState.automaticSearchEnabled,
                        onRequestMetadata = { tools, choice ->
                            optionalFieldsSent = tools.isNotEmpty() || hasAttachments || title && !history.textOnlyAnswer &&
                                history.chat.isEmpty() && history.responses.isEmpty()
                            agentLog(history.round, "wire tools=${tools.joinToString(",")} choice=${choice ?: "-"}")
                        },
                        retryBeforeContent = !history.requestState.webEnabled && !history.requestState.nativeFetchEnabled,
                        responseHistory = history.responses,
                        onResponseOutput = onOutput,
                        imageToolCompleted = history.imageHandled,
                        webToolCompleted = history.webUsed,
                        search = search && history.requestState.nativeSearchEnabled,
                        openAiSearch = openAiSearch && history.requestState.nativeSearchEnabled,
                        readLink = readLink && history.requestState.nativeFetchEnabled,
                        requestTitle = title && !history.textOnlyAnswer && history.chat.isEmpty() && history.responses.isEmpty(),
                        generateImageTool = history.imageOffered,
                        thinkingEnabled = options.thinkingEnabled,
                        reasoningEffort = options.reasoningEffort,
                        maxTokens = options.maxTokens,
                        temperature = options.temperature.toDouble(),
                        topP = options.topP.toDouble(),
                        extraHeaders = routeProvider.headersFor(openCodeSessionId),
                    ) }.collect { event ->
                        when (event) {
                            is StreamEvent.Reasoning -> {
                                // 请求档位控制发送参数，显示以服务端实际返回为准。
                                // 保留空白分片，等收到可见文字才开始计时和发布思考内容。
                                reasonBuf.append(event.text)
                                if (!hasReasoningText && event.text.isNotBlank()) {
                                    hasReasoningText = true
                                    noteThinking()
                                }
                                if (hasReasoningText) dirty = true
                            }

                            is StreamEvent.Content -> {
                                noteBody()
                                contentBuf.append(event.text)
                                contentChars += event.text.length
                                dirty = true
                            }

                            is StreamEvent.Title -> serverTitle = event.text
                            is StreamEvent.CitationDelta -> {
                                citations[event.citation.url] = event.citation
                                dirty = true
                            }

                            /* 有些网关把用量放在流末尾单独一帧 —— 这里如实收下，
                               缓存命中率就是从这里来的（见 recordUsage）。 */
                            is StreamEvent.Usage -> recordUsage(event)

                            is StreamEvent.ToolCallDelta -> imageTool.consume(event)

                            StreamEvent.Done -> Unit

                            // 流内错误对象是**上游网关原文**（如 `No available channel …`）：
                            // 先翻成人话，认不出才保留原文（见 `serverErrorText`）
                            is StreamEvent.Error -> {
                                Diagnostics.log(
                                    "CHAT",
                                    "stream error code=${event.code} " +
                                        "retryable=${event.retryable} msg=${event.message.take(300)}",
                                )
                                failure = sendError(event)
                            }

                            is StreamEvent.Stopped -> imageTool.consume(event)

                            // 块级事件只属于 Anthropic 通道；这条路径上忽略
                            else -> Unit
                        }
                    }
                }
                if (imageToolEnabled && !textOnly && !imageCallHandled) {
                    if (failure == null && imageTool.endedWithoutCall) {
                        if (contextualEdit && explicitReference && editReferences.isEmpty()) {
                            localAnswer = Res.get(R.string.image_tool_reference_missing)
                        } else if (isExplicitImageRequest(latestQuestion, imageIntent)) {
                            imageFallback = turnImageRequest(latestQuestion, imageIntent == ImageGenerationIntent.EDIT)
                            localAnswer = Res.get(R.string.image_tool_not_called)
                        }
                    }
                }
            }

            suspend fun sendAttempt(textOnly: Boolean) {
                contextRejection = null
                try {
                    streamAttempt(textOnly)
                } catch (error: com.simplechat.app.data.AppTextException) {
                    if (error.text.resId != R.string.error_context_budget) throw error
                    contextRejection = CompactionRequestFailure(CompactionFailureKind.INPUT_LIMIT)
                    failure = error.userMessage()
                }
            }

            fun canRetryThisTurn(): Boolean = research.blocks.isEmpty() && !pendingImageTool && prefix == null && contentChars == 0 && reasonBuf.isEmpty() &&
                accumulator?.answer.isNullOrEmpty() && accumulator?.thinking.isNullOrEmpty() &&
                generation == streamGeneration && !stoppedByUser

            try {
                sendAttempt(omitAttachmentsAndTools)
                var textOnly = omitAttachmentsAndTools
                if (canDegrade && !textOnly && !wantsImage && canRetryThisTurn()) {
                    textOnly = true
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    failure = null
                    research = com.simplechat.app.net.SearchAugmentation()
                    timeline = emptyList()
                    accumulator?.seed(timeline)
                    dirty = true
                    if (route.showRetrySkipNotice) retrySkipNotice = Res.get(R.string.retry_skipped_notice)
                    Diagnostics.log("AGENT", "turn=$turnId transition reason=optional_fields_rejected")
                    sendAttempt(textOnly)
                }
                val limit = contextRejection
                if (limit != null && canRetryThisTurn()) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val recoveryWindow = minOf(options.model.contextWindow, limit.inputLimit ?: 128_000)
                    try {
                        if (compactHistory(conversationId, options.model,
                                route.baseUrl.ifBlank { routeProvider.defaultBaseUrl }, route.apiKey,
                                routeProvider.headersFor(openCodeSessionId), selectedApiStyle, recoveryWindow,
                            ) && canRetryThisTurn()
                        ) {
                            failure = null
                            if (route.showRetrySkipNotice) retrySkipNotice = Res.get(R.string.context_recovery_applied)
                            Diagnostics.log("AGENT", "turn=$turnId transition reason=context_compacted")
                            sendAttempt(textOnly)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failure = listOfNotNull(failure, compactionErrorText(error)).joinToString("\n")
                    }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                cancelled = true
            } catch (t: Throwable) {
                if (!stoppedByUser) {
                    val category = (t as? com.simplechat.app.data.AppTextException)?.text?.resId
                    val http = (t as? com.simplechat.app.net.ServiceApi.HttpFailure)?.status
                    android.util.Log.w("SimpleChat/Send", "protocol=$apiStyle error=${t.javaClass.simpleName} http=$http category=${Res.nameOrNull(category)}")
                    failure = t.userMessage()
                }
            } finally {
                flusher.cancel()
            }

            val finishedAt = SystemClock.elapsedRealtime()
            agentTranscript?.let { transcript ->
                val calls = transcript.events.count { it.kind == com.simplechat.app.net.AgentTranscriptKind.TOOL_CALL }
                val results = transcript.events.count { it.kind == com.simplechat.app.net.AgentTranscriptKind.TOOL_RESULT }
                Diagnostics.log("AGENT", "turn=$turnId attempt=$agentAttempt events=${transcript.events.size} calls=$calls results=$results")
            }
            accumulator?.settle(finishedAt)
            if (reasoningStartedAt != null && reasoningEndedAt == null) reasoningEndedAt = finishedAt
            refreshBuffered()
            /*
             * 这里**不做任何"审核/撤回"处理**。
             *
             * 客户端只负责一件事：服务端发来什么就显示什么、存什么。`stop_reason` 原样记进
             * 数据库（`stopReason`），不据此删改内容 —— 早先按 Anthropic 文档的
             * "refusal 时丢弃已下发内容"做过一次，结果是一段已经写完的回复在收尾瞬间被吞掉，
             * 用户看到的只有"写完了，然后没了"。那是数据丢失，客户端没有理由替服务端做这件事。
             */
            if (failure == null && !cancelled && generation == streamGeneration &&
                !pendingImageTool && !generatedImageAttached && !imageToolFailed &&
                localAnswer == null && contentText.isBlank()
            ) {
                Diagnostics.log("CHAT", "model stream ended without answer stop=${accumulator?.stopReason}")
                failure = Res.get(R.string.error_no_content)
            }
            val status = when {
                cancelled || generation != streamGeneration -> MessageStatus.STOPPED
                generatedImageAttached -> MessageStatus.DONE
                imageToolFailed -> MessageStatus.ERROR
                failure != null -> MessageStatus.ERROR
                else -> MessageStatus.DONE
            }
            if (status == MessageStatus.DONE && com.simplechat.app.net.hasSuccessfulWebSearch(timeline) &&
                com.simplechat.app.net.deniesSearchCapability(contentText)
            ) {
                Diagnostics.log("CHAT", "suppressed answer contradicting successful web search")
                contentText = Res.get(R.string.search_answer_contradicts_tool)
                timeline = timeline.filterNot { it is ReplyBlock.Text } + ReplyBlock.Text(contentText)
            }
            val ttft = firstTokenAt?.minus(startedAt)
            val seconds = (finishedAt - startedAt).coerceAtLeast(1) / 1000f
            // 粗估：中文约 1 token ≈ 1.6 字符，仅用于性能仪表展示
            val tps = if (contentChars > 0) (contentChars / 1.6f) / seconds else null
            val finalContent = when {
                generatedImageAttached -> contentText.ifBlank { Res.get(R.string.image_generated) }
                imageToolFailed -> contentText.ifBlank { Res.get(R.string.image_generate_failed) }
                pendingImageTool -> ""
                localAnswer != null && status == MessageStatus.DONE -> localAnswer.orEmpty()
                else -> contentText
            }
            val directImageRequest = imageFallback.takeIf { status == MessageStatus.DONE }
            if ((pendingImageTool && !generatedImageAttached && !imageToolFailed) || localAnswer != null) {
                timeline = timeline.filterNot { it is ReplyBlock.Text }
            }
            val finalReasoning = reasoningText
            /*
             * **真实**思考时长 = 首条思维链 → 首条正文。
             *
             * 先前用的是 `finishedAt - startedAt`，也就是"想 + 写"的总时长，
             * 却挂在「已深度思考」后面 —— 想 5 秒、写 25 秒会显示成 30 秒。
             *
             * 没等到正文就结束（用户中断 / 出错）时，用结束时刻兜底。
             *
             * 续写且这一轮没再思考时，思考时长要**沿用上一轮的值** ——
             * 否则一条「已深度思考 · 8 秒」的回复，点一下继续生成就变成"没思考过"。
             */
            val thinkingMs = if (accumulator != null) accumulator.thinkingMillis(finishedAt) else {
                reasoningStartedAt?.let { start -> (reasoningEndedAt ?: finishedAt) - start }
            } ?: prefix?.reasoningSeconds?.times(1000)

            patch(replyId) {
                it.copy(
                    content = finalContent,
                    reasoning = finalReasoning.ifEmpty { null },
                    status = status,
                    errorMessage = failure,
                    ttftMs = ttft,
                    tps = tps,
                    reasoningSeconds = thinkingMs?.let { thinkingSeconds(it) },
                    timeline = timeline,
                    stopReason = accumulator?.stopReason,
                    imageRequest = directImageRequest ?: it.imageRequest,
                )
            }

            // 终写一次：即使进程被杀，内容也已落库
            val persisted = withContext(NonCancellable) {
                // Wait for any in-flight checkpoint so it cannot overwrite the final row.
                flusher.join()
                runCatching {
                    withContext(Dispatchers.IO) {
                        repository.finishMessage(
                            id = replyId,
                            content = finalContent,
                            reasoning = finalReasoning,
                            status = status,
                            errorMessage = failure,
                            ttftMs = ttft,
                            tps = tps,
                            thinkingMs = thinkingMs,
                            timeline = timeline,
                            stopReason = accumulator?.stopReason,
                            tokensIn = lastUsage?.promptTokens?.takeIf { it > 0 },
                            tokensOut = lastUsage?.completionTokens?.takeIf { it > 0 },
                            cacheHitTokens = lastUsage?.cacheHitTokens,
                            directImageRequest = directImageRequest,
                        )
                        repository.touchConversation(conversationId)
                    }
                }.onFailure { error ->
                    android.util.Log.e("SimpleChat/Save", "Reply persistence failed: ${error.javaClass.simpleName}")
                    Diagnostics.e("SAVE", "reply persistence failed", error)
                    if (conversationId == currentConversationId) {
                        banner = Res.get(R.string.reply_save_failed)
                        patch(replyId) { it.copy(status = MessageStatus.ERROR, errorMessage = Res.get(R.string.reply_save_failed)) }
                    }
                }.isSuccess
            }

            /*
             * 把这轮的用量写回内存里那条消息：落库的已是最新值，但界面统计读的是
             * 内存列表 —— 不补这一下，「本会话消耗」要等重新打开会话才会更新。
             */
            if (persisted && conversationId == currentConversationId) {
                patch(replyId) { message ->
                    message.copy(
                        tokensIn = lastUsage?.promptTokens?.takeIf { it > 0 },
                        tokensOut = lastUsage?.completionTokens?.takeIf { it > 0 },
                        cacheHitTokens = lastUsage?.cacheHitTokens,
                    )
                }
            }

            /*
             * 服务端给了标题就用它 —— 比另发一次请求让模型起名更省、更准，
             * 而且这是官方 Chat Demo 的做法（`title.enabled` 的返回值）。
             */
            val title = serverTitle?.trim()
            if (!title.isNullOrEmpty()) {
                withContext(NonCancellable) {
                    runCatching { repository.renameConversation(conversationId, title) }
                }
                serverTitleRequestedFor = conversationId
            }

            /*
             * 失败**不再重复弹 banner**：
             *
             * 错误已经写在这条回复的 `errorMessage` 上（消息里那行红字 + 重试按钮），
             * 再弹一条一模一样的 banner 只是噪声 —— v1.1.3 之前的截图里同一个
             * `stream was reset` 就是上下各显示一次。banner 只留给"与某条消息无关"的
             * 全局提示（未配置 Key、压缩失败等）。
             */
            if (generation == streamGeneration) {
                isStreaming = false
                streamJob = null
                // 生成结束：前台服务退回普通优先级，通知随之消失
                BackgroundGeneration.sync(false)
                if (persisted && status == MessageStatus.DONE && conversationId == currentConversationId) {
                    replyCompletions.tryEmit(CompletedReply(conversationId, replyId))
                }
            }

            /*
             * 首轮回复落地后，用一个 **flash 档模型**给会话起个标题。
             *
             * 刻意放在这里而不是"发出去就生成"：拿到回复才知道这段在聊什么，
             * 只根据提问猜出来的标题经常跑偏。
             *
             * 扩展通道已经由服务端给了标题（[serverTitle]），就不再自己发一轮请求。
             */
            if (!pendingImageTool && generation == streamGeneration && conversationId == currentConversationId && status == MessageStatus.DONE && title.isNullOrEmpty()) {
                maybeGenerateTitle(conversationId)
            }
        }
    }

    /**
     * 组装扩展通道的请求。
     *
     * 与 [`buildRequestMessages`] 的差别不只是协议：扩展通道的 `system` 只有一个字符串，
     * 所以压缩摘要只能**拼进系统提示词**，不能像 OpenAI 通道那样再塞一条 system 消息。
     */
    private suspend fun buildAnthropicRequestNow(
        prefix: UiMessage?, research: String, route: StoredSettings, options: ConversationSettings,
        searchRequested: Boolean, omitAttachmentsAndTools: Boolean, memoryText: String,
        generateImageTool: Boolean = false,
        visualFollowUp: Attachment? = null,
    ): AnthropicRequest {
        skippedNoVision = 0; skippedUnreadable = 0; skippedUnsupportedFiles = 0
        val active = validCompaction(messages, compaction)
        val previousModel = modelSwitchedFrom(messages, options.model.id)
        val modelSwitchContext = previousModel?.let {
            Res.get(R.string.model_switch_context, it, options.model.id)
        }
        val system = buildString {
            append(options.composedSystemPrompt(presets))
            if (research.isNotBlank()) appendLine().appendLine(research)
            if (modelSwitchContext != null) appendLine().appendLine(modelSwitchContext)
            if (active != null && active.summary.isNotBlank()) {
                if (isNotEmpty()) appendLine().appendLine()
                append(Res.get(R.string.prompt_recap_intro))
                append(active.summary)
            }
        }
        val requestTitle = shouldRequestTitle()

        /*
         * 续写时把这条 assistant 从历史里摘出去：它要以**末尾前缀**的身份出现，
         * 留在历史里就是同一轮回答发了两遍，服务端会把它当成两个不同的回答。
         */
        val outgoingMessages = orderedOutgoingMessages(messages, active)
        val lastUserId = outgoingMessages.lastOrNull { it.role == MessageRole.USER }?.id
        val preparedPath = outgoingMessages
            .filter { it.id != prefix?.id }
            /*
             * 附件在这里整理：落盘字节读回内存（`toDataUrl()` / image block 只认内联），
             * 图片再按真实内容校正（见 `ApiImage`）。
             *
             * ⚠️ 不能像以前那样"条数没变就复用原对象"：落盘附件的 `data` 是空的，
             * 条数当然没变 —— 复用等于把**空图片**发出去，服务端只会回
             * `unsupported image`，而且每一轮都复现（v1.1.2 的真实事故）。
             */
            .map { message ->
                val outgoing = if (message.role != MessageRole.USER) message
                else if (omitAttachmentsAndTools) message.copy(attachments = emptyList())
                else prepareOutgoingUser(message.withVisualFollowUp(lastUserId, visualFollowUp), route, options.model)
                preserveTurnWhenAttachmentsSkipped(message, outgoing)
            }

        /*
         * 跨对话记忆：与 OpenAI 通道同一策略 —— 只拼在**最后一条 user 消息**前，
         * 历史与落库内容都不动。
         */
        val path = if (memoryText.isNotBlank() && lastUserId != null) {
            preparedPath.map { message ->
                if (message.role == MessageRole.USER && message.id == lastUserId) {
                    message.copy(content = "$memoryText\n\n${message.content}")
                } else {
                    message
                }
            }
        } else {
            preparedPath
        }

        /*
         * 官方域才发 DeepSeek 扩展字段（搜索工具 / 服务端标题 /
         * latest_reminder 角色 / thinking.type=disabled）。自建网关或 Claude 兼容中转
         * 遇到这些会 400，而不是"忽略" —— 见 `buildAnthropicRequest` 的说明。
         */
        val officialDeepSeek = isOfficialDeepSeekEndpoint(
            route.baseUrl,
        )
        return buildAnthropicRequest(
            model = options.model.id,
            systemPrompt = system,
            path = path,
            searchEnabled = searchRequested,
            reasoningEffort = options.model.resolveReasoningEffort(options.reasoningEffort),
            thinkingEnabled = options.thinkingEnabled,
            requestTitle = requestTitle,
            latestReminder = latestReminder(),
            maxTokens = options.maxTokens,
            outputLimit = options.model.maxOutputTokens,
            prefix = prefix,
            deepSeekExtensions = officialDeepSeek,
            allowServerTools = !omitAttachmentsAndTools,
            includeAttachments = !omitAttachmentsAndTools,
            generateImageTool = generateImageTool,
            temperature = options.temperature.toDouble(),
            topP = options.topP.toDouble(),
        ).also { request ->
            if (request.messages.none { it.role == "user" }) throw com.simplechat.app.data.AppTextException(R.string.error_no_sendable_messages)
            if (request.title != null) serverTitleRequestedFor = currentConversationId
        }
    }

    /**
     * 本轮的时间地点提醒。
     *
     * 每次请求现算 —— "今天几号"必须跟着真实时间走，缓存一次就会在跨日之后说谎。
     */
    private fun latestReminder(): String = createLatestReminder(
        now = java.time.LocalDateTime.now(),
        cjk = Res.bool(R.bool.ui_is_cjk),
        region = stored.locationRegion,
        languageLabel = Res.get(R.string.prompt_language_name),
    )

    /**
     * 这一轮要不要请服务端顺手起个标题。
     *
     * 三条护栏与 [maybeGenerateTitle] 完全一致：只在首轮、只在标题还是兜底值、
     * 每个会话只试一次。区别只是"谁来起名"（服务端 vs 自己再发一轮请求）。
     */
    private fun shouldRequestTitle(): Boolean {
        val id = currentConversationId ?: return false
        if (serverTitleRequestedFor == id) return false
        if (messages.count { it.role == MessageRole.USER } != 1) return false
        val current = currentConversationTitle ?: return true
        if (current.isBlank() || isAutoTitle(current)) return true
        val first = messages.firstOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        return current == derivedTitle(first, Res.get(R.string.conversation_default_title))
    }


    // ── 会话标题 ──────────────────────────────────────────

    /**
     * 已经为哪个会话生成过标题。
     *
     * 在**发请求之前**就记下来：失败不再重试。标题不值得为它反复烧 token，
     * 兜底标题本来就是能看的。
     */
    private var titleGeneratedFor: String? = null
    private var titleJob: Job? = null

    /**
     * 用 flash 档模型给会话起标题。
     *
     * 三条护栏，任何一条不满足就安静退出：
     * 1. **只在首轮**（会话里只有一条用户消息）—— 聊开了再改名字会让人莫名其妙；
     * 2. **标题还没被人改过** —— 用户自己改的名字永远优先；
     * 3. 每个会话只尝试一次。
     */
    private fun maybeGenerateTitle(conversationId: String) {
        // Official routes already requested an eligible inline title. Keep the local title otherwise.
        if (isOfficialDeepSeekEndpoint(stored.baseUrl) || apiStyle == ApiStyle.ANTHROPIC) return
        if (titleGeneratedFor == conversationId) return
        if (isStreaming) return

        val users = messages.filter { it.role == MessageRole.USER }
        if (users.size != 1) return
        val firstUser = users.first()
        val reply = messages.firstOrNull {
            it.role == MessageRole.ASSISTANT && it.content.isNotBlank()
        } ?: return

        val fallback = derivedTitle(firstUser.content, Res.get(R.string.conversation_default_title))
        val current = currentConversationTitle
        if (current != null && current != fallback && !isAutoTitle(current)) {
            // 用户已经改过名（或者这就是上一轮生成的结果）→ 别覆盖
            titleGeneratedFor = conversationId
            return
        }

        titleGeneratedFor = conversationId

        val material = buildString {
            appendLine(Res.get(R.string.prompt_role_user))
            appendLine(firstUser.content.take(TITLE_MATERIAL_CHARS))
            appendLine()
            appendLine(Res.get(R.string.prompt_role_assistant))
            append(reply.content.take(TITLE_MATERIAL_CHARS))
        }
        val model = titleModel()

        titleJob = viewModelScope.launch {
            val collected = StringBuilder()
            runCatching {
                chatApi.streamChat(
                    apiStyle = if (apiStyle == ApiStyle.OPENAI_RESPONSES) apiStyle else ApiStyle.OPENAI,
                    baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                    apiKey = stored.apiKey,
                    model = model,
                    messages = listOf(
                        OutMessageDto(role = "system", content = MessageContent.text(titleSystemPrompt())),
                        OutMessageDto(role = "user", content = MessageContent.text(material)),
                    ),
                    // 起标题不需要思考，也不该等它想
                    thinkingEnabled = false,
                    maxTokens = TITLE_MAX_TOKENS,
                    temperature = 0.3,
                    extraHeaders = providerKind.headersFor(openCodeSessionId),
                ).collect { event ->
                    if (event is StreamEvent.Content) collected.append(event.text)
                }
            }

            val title = cleanGeneratedTitle(collected.toString()) ?: return@launch

            // 请求期间用户可能自己改了名 —— 落库前再确认一次
            val storedTitle = repository.findConversation(conversationId)?.title
            if (storedTitle != null && storedTitle != fallback) return@launch

            repository.renameConversation(conversationId, title)
            // 不用在这里同步顶栏：observeTitle 那条流会送回来
        }
    }

    /**
     * 起标题用哪个模型：当前服务商里**带 flash 的那个**。
     *
     * 起标题是"短、快、便宜"的活。用主力大模型既慢又贵，
     * 而且大模型更容易把标题写成一段小作文。目录里没有 flash 档就退回当前模型。
     */
    private fun titleModel(): ModelInfo {
        val catalog = models.ifEmpty { ModelCatalog.knownModels(providerKind) }
        return catalog.firstOrNull { it.id.contains("flash", ignoreCase = true) }
            ?: settings.model
    }

    private fun flush(id: String, reason: StringBuilder, content: StringBuilder) {
        if (reason.isEmpty() && content.isEmpty()) return
        val reasonDelta = reason.toString()
        val contentDelta = content.toString()
        reason.clear()
        content.clear()
        patch(id) {
            it.copy(
                reasoning = (it.reasoning.orEmpty() + reasonDelta).ifEmpty { null },
                content = it.content + contentDelta,
            )
        }
    }

    /** 只替换目标那一条，其余列表项保持引用不变 —— 避免整列表重组。 */
    private inline fun patch(id: String, transform: (UiMessage) -> UiMessage) {
        val index = messages.indexOfLast { it.id == id }
        if (index >= 0) messages[index] = transform(messages[index])
    }

    /**
     * 构造请求用的附件：**模型不支持图片时把图片摘掉**，只留文本附件。
     *
     * 服务端对非视觉模型直接 400（`messages.N.content.0: images are not supported
     * by this model`），而历史里带图的会话很常见（导入来的、换过模型的、
     * 以前用视觉模型聊过的）。让整条对话因为一张旧图发不出去，代价太大 ——
     * 所以按模型能力过滤，并给一句普通提醒（不是错误）。
     */
    private suspend fun outgoingAttachments(attachments: List<Attachment>, route: StoredSettings, model: ModelInfo): List<Attachment> {
        if (attachments.isEmpty()) return attachments
        val compatible = if (route.supportsFileReferences) attachments else attachments.mapNotNull { file ->
            when {
                file.fileId == null -> file
                !file.remoteImage && file.data.isNotBlank() -> file.copy(fileId = null, fileEndpoint = null, fileAccount = null)
                file.remoteImage && !model.supportsVision -> { skippedNoVision++; null }
                else -> { skippedUnsupportedFiles++; null }
            }
        }
        compatible.filter { it.fileId != null }.forEach { file ->
            val endpoint = route.filesApi.endpoint(route.baseUrl, route.filesApi.filesPath)
            val chatHost = java.net.URI(route.baseUrl).host
            val fileHost = java.net.URI(endpoint).host
            val account = com.simplechat.app.data.FileApiTextCache.fingerprint(route.filesApi.key(route.apiKey))
            if (file.fileEndpoint != endpoint || chatHost != fileHost || file.fileAccount != account)
                throw com.simplechat.app.data.AppTextException(R.string.service_file_endpoint_mismatch)
        }
        var filtered = compatible
        if (!model.supportsVision) {
            filtered = compatible.filterNot { it.isImage }
            skippedNoVision += compatible.count { it.isImage }
        }
        // 字节读回 + 图片按真实内容校正（格式不收的转码、坏图丢掉）。见 ApiImage
        val (prepared, unreadable) = repository.prepareRequestAttachments(filtered)
        skippedUnreadable += unreadable
        if (showImageSkipNotice) {
            imageSkipNotice = when {
                skippedUnsupportedFiles > 0 -> Res.get(R.string.attachments_skipped_unsupported, skippedUnsupportedFiles)
                skippedUnreadable > 0 -> Res.get(R.string.images_skipped_unreadable, skippedUnreadable)
                skippedNoVision > 0 -> Res.get(R.string.images_skipped_no_vision, skippedNoVision)
                else -> null
            }
        }
        return prepared
    }

    private suspend fun prepareOutgoingUser(message: UiMessage, route: StoredSettings, model: ModelInfo): UiMessage {
        val (text, attachments) = repository.prepareLongTextForRequest(message.content, message.attachments, route)
        return message.copy(content = text, attachments = outgoingAttachments(attachments, route, model))
    }

    private fun preserveTurnWhenAttachmentsSkipped(original: UiMessage, outgoing: UiMessage): UiMessage =
        if (original.role == MessageRole.USER && original.content.isBlank() && original.attachments.isNotEmpty() &&
            outgoing.attachments.isEmpty()
        ) outgoing.copy(content = Res.get(R.string.prompt_attachments_skipped)) else outgoing

    /**
     * 检索历史会话里与本轮提问相关的内容，供 [buildRequestMessages] 前置到用户消息。
     *
     * 检索失败时仍可发送；停止生成时则保留取消信号。
     */
    private suspend fun crossChatMemoryFor(route: StoredSettings, activeConversationId: String, userContent: String): String {
        if (!route.crossChatMemory || route.crossChatMemoryLimit <= 0 ||
            (currentConversationId == activeConversationId && !conversationMemoryEnabled)) {
            Diagnostics.log("MEM", "skip(disabled) limit=${route.crossChatMemoryLimit}")
            return ""
        }
        return try {
            withContext(Dispatchers.Default) { retrieveCrossChatMemory(route, activeConversationId, userContent) }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            Diagnostics.log("MEM", "skip(retrieval-failed) error=${error.javaClass.simpleName}")
            ""
        }
    }

    private suspend fun retrieveCrossChatMemory(
        route: StoredSettings,
        activeConversationId: String,
        userContent: String,
    ): String {
        if (userContent.isEmpty()) {
            Diagnostics.log("MEM", "skip(empty-query)")
            return ""
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val sessions = repository.memorySessions(
            activeConversationId, CrossChatMemory.MAX_SCAN_SESSIONS, CrossChatMemory.MAX_TITLE_CHARS,
        )
            .map { CrossChatMemory.SessionRef(it.id, it.title, it.updatedAt) }
        if (sessions.isEmpty()) {
            Diagnostics.log("MEM", "skip(no-conversations)")
            return ""
        }
        val result = CrossChatMemory.build(
            query = userContent,
            currentConversationId = activeConversationId,
            limit = route.crossChatMemoryLimit,
            sessions = sessions,
            loadMessages = { id ->
                repository.memoryMessages(id, CrossChatMemory.MAX_DEPTH, CrossChatMemory.MAX_MESSAGES,
                    CrossChatMemory.MAX_BODY_CHARS).map { CrossChatMemory.Message(it.role, it.content) }
            },
        )
        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
        Diagnostics.log(
            "MEM",
            buildString {
                append(if (result.injected) "inject" else "skip(${result.reason})")
                append(" reason=${result.reason}")
                append(" tokens=${result.queryTokens}")
                append(" candidates=${result.candidates}")
                append(" picked=${result.picked}")
                append(" fallback=${result.fallback}")
                append(" chars=${result.text.length}")
                append(" ms=$elapsed")
                append(" sessions=${sessions.size}")
                append(" limit=${route.crossChatMemoryLimit}")
            },
        )
        return result.text
    }

    private suspend fun buildRequestMessages(
        research: String, route: StoredSettings, options: ConversationSettings,
        omitAttachmentsAndTools: Boolean, memoryText: String,
        visualFollowUp: Attachment? = null,
    ): List<OutMessageDto> {
        skippedNoVision = 0; skippedUnreadable = 0; skippedUnsupportedFiles = 0
        val request = mutableListOf<OutMessageDto>()
        /*
         * 跨对话记忆只拼在**最后一条 user 消息**的发送内容前：落库消息保持原样，
         * 历史也不动（改了历史会让每轮前缀都变，缓存与可复现性一起丢）。
         */
        if (research.isNotBlank()) request += OutMessageDto("system", MessageContent.text(research))
        modelSwitchedFrom(messages, options.model.id)?.let { previousModel ->
            request += OutMessageDto("system", MessageContent.text(
                Res.get(R.string.model_switch_context, previousModel, options.model.id),
            ))
        }

        val system = options.composedSystemPrompt(presets)
        if (system.isNotBlank()) {
            request += OutMessageDto(role = "system", content = MessageContent.text(system))
        }

        /*
         * 压缩摘要以 system 身份插在最前，并跳过被它覆盖的那些 slot。
         * 这是「只改变发给 API 的内容」的落点 —— 本地消息一条没动。
         */
        val active = validCompaction(messages, compaction)
        if (active != null && active.summary.isNotBlank()) {
            request += OutMessageDto(
                role = "system",
                content = MessageContent.text(
                    Res.get(R.string.prompt_recap_intro) + active.summary,
                ),
            )
        }

        // 顺序规则见 `orderedOutgoingMessages`（纯函数，有单测钉死）
        val outgoingMessages = orderedOutgoingMessages(messages, active)
        val lastUser = outgoingMessages.lastOrNull { it.role == MessageRole.USER }
        val lastUserId = lastUser?.id
        outgoingMessages.forEach { message ->
            /*
             * 空消息不发：历史里出错/被中断的 assistant、既没正文也没附件的 user
             * 是可能存在的，而部分兼容网关对空 `content` 直接 400 ——
             * 一条空消息不值得让整段历史发不出去（见 `isSendableOutgoing`）。
             */
            val outgoing = if (message.role == MessageRole.USER) {
                if (omitAttachmentsAndTools) message.copy(attachments = emptyList())
                else prepareOutgoingUser(message.withVisualFollowUp(lastUserId, visualFollowUp), route, options.model)
            } else message
            val sendable = preserveTurnWhenAttachmentsSkipped(message, outgoing)
            if (!isSendableOutgoing(sendable)) return@forEach
            val body = if (
                memoryText.isNotBlank() && message.role == MessageRole.USER && message.id == lastUserId
            ) {
                "$memoryText\n\n${sendable.content}"
            } else {
                sendable.content
            }
            request += OutMessageDto(
                role = if (message.role == MessageRole.USER) "user" else "assistant",
                /*
                 * 只有 user 消息可能带图；组装规则见 `userContent`
                 * （无附件 → 纯字符串；只有文本附件 → 纯字符串；有图 → content 数组）。
                 *
                 * 生图结果不直接放在 assistant 消息里；需要看图时只附到本轮 user 消息。
                 */
                content = if (message.role != MessageRole.USER || sendable.attachments.isEmpty()) {
                    MessageContent.text(body)
                } else {
                    userContent(body, sendable.attachments)
                },
            )
        }

        if (request.none { it.role == "user" }) throw com.simplechat.app.data.AppTextException(R.string.error_no_sendable_messages)
        return request
    }

    /**
     * 供顶栏标题使用。
     *
     * 优先用库里的标题（用户可能重命名过）；尚未落库的新对话才从首条用户消息推导。
     */
    fun conversationTitle(): String {
        currentConversationTitle?.let { return it }
        val first = messages.firstOrNull { it.role == MessageRole.USER }?.content?.trim()
        if (first.isNullOrEmpty()) return Res.get(R.string.conversation_default_title)
        return first.lineSequence().first().trim().take(20).ifBlank { Res.get(R.string.conversation_default_title) }
    }

    companion object {
        internal const val ActiveConversationStateKey = "activeConversationId"
        /**
         * 流式增量的提交间隔（毫秒）。
         *
         * **16ms ≈ 一帧**，对齐官方 Chat Demo 的 `requestAnimationFrame` 节拍：
         * 那一层是"**至多一帧一次**"，而不是"每 N 毫秒硬跳一次"。
         *
         * 早先这里是 50（20Hz）。问题是**两把尺子**：数据层 50ms 一提交，
         * 渲染层再按自己的 50ms 采样一次，两个独立时钟互相 beat ——
         * 相邻两次屏幕更新实际落在 50～100ms 之间抖，看着就是一跳一跳。
         * 官方只有 rAF 一把尺子，所以是连续流动的。
         *
         * 单次提交的**重活由渲染层兜住**：`MarkdownText` 现在按帧耗时自适应
         * 冷却（`adaptiveCooldownMs`），一帧被拖长就自动少跳几帧。
         * 也就是说"提频"与"防卡"分别由两层各管一段，而不是靠一个保守的常数两头兼顾。
         *
         * ⚠️ 真机上若出现滚动掉帧，先量一次帧时间再回调这个值 ——
         * 而不是凭感觉改回 50（那会连"连续"一起丢掉）。
         */
        private const val STREAM_THROTTLE_MS = 16L

        private const val CHECKPOINT_INTERVAL_MS = 1_500L

        /**
         * 会话级设置的落库防抖。
         *
         * 补充提示词是**逐字输入**的，改一次写一次库没必要；
         * 停手 400ms 再写，既跟得上手感也不会把主线程拖住。
         */
        private const val SETTINGS_PERSIST_DEBOUNCE_MS = 400L

        /**
         * 上下文统计的更新防抖。
         *
         * 统计要扫全部正文估 token；流式期间消息每 16ms 变一次，逐次重算会把主线程
         * 吃穿。输入栏上那行数字晚 250ms 更新，用户完全感知不到。
         */
        private const val ContextStatsDebounceMs = 250L

        /**
         * 压缩用的系统提示词见 `prompt_compact_system`（`strings.xml`，随界面语言）。
         *
         * 反复强调"设定 / 情节 / 文风"三件事，是因为长篇创作里丢失它们的代价
         * 远大于丢失具体措辞 —— 措辞模型能再生成，人名地名和人物关系不能。
         */

        fun factory(
            chatApi: ChatApi,
            settingsStore: SettingsStore,
            repository: ChatRepository,
            openCodeSessionId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val state = createSavedStateHandle()
                ChatViewModel(chatApi, settingsStore, repository, openCodeSessionId, state).also { viewModel ->
                    state.get<String>(ActiveConversationStateKey)?.let { viewModel.openConversation(it) }
                }
            }
        }
    }
}

private fun String.toConversationModeSafe(): ConversationMode =
    runCatching { ConversationMode.valueOf(this) }.getOrDefault(ConversationMode.CREATIVE)

/**
 * 思考耗时 → 展示用秒数。
 *
 * 四舍五入，再兜底到 1：直接截断会把 0.9 秒的思考显示成「0 秒」，
 * 看着像计时坏了。
 *
 * `internal` 是为了能单测 —— 取整边界是最容易写错、又最不容易被发现的地方。
 */
internal fun thinkingSeconds(ms: Long): Long = ((ms + 500) / 1000).coerceAtLeast(1)








