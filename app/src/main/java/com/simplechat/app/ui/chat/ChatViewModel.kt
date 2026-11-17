package com.simplechat.app.ui.chat

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simplechat.app.R
import com.simplechat.app.data.ApiKey
import com.simplechat.app.data.BackgroundGeneration
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.ui.markdown.warmMarkdownCache
import kotlinx.coroutines.Dispatchers
import com.simplechat.app.data.Res
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
/** 换会话时后台预热的尾部消息条数：贴底打开先看到的就是这几条。 */
private const val WarmTailMessages = 10

/**
 * 预热时单条消息的长度上限。
 *
 * 一条几万字的消息解析要几百毫秒；替它预热不会让首屏更快（列表本身也要等它排版），
 * 只会把后台线程占满、拖慢真正该快的那几条。
 */
private const val WarmTailMaxChars = 24_000

class ChatViewModel(
    private val chatApi: ChatApi,
    private val settingsStore: SettingsStore,
    private val repository: ChatRepository,
    private val openCodeSessionId: String,
) : ViewModel() {

    val messages = mutableStateListOf<UiMessage>()

    /** 当前会话 id；为空表示尚未落库的新对话。 */
    var currentConversationId by mutableStateOf<String?>(null)
        private set

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

    init {
        viewModelScope.launch {
            val stored = repository.findDraftTitle(DraftEntity.NewConversation)
            pendingTitle = stored
            if (currentConversationId == null && currentConversationTitle == null) {
                currentConversationTitle = stored
            }
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

    /** 压缩过程中的流式预览，让用户看到"它在写什么"。 */
    var compactPreview by mutableStateOf("")
        private set

    private var compactJob: Job? = null

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
        private set

    /**
     * 实际生效的协议通道。
     *
     * 由「配置的档位 + 端点」共同决定（见 `ApiStyle.resolve`）：
     * `AUTO` 时 DeepSeek 官方走扩展通道（能拿到搜索 / 引用 / 服务端标题），
     * 其余端点走 OpenAI 兼容。
     */
    var apiStyle by mutableStateOf(ApiStyle.ANTHROPIC)
        private set

    /**
     * 当前通道能不能联网搜索 —— 它决定**要不要显示**那个开关。
     *
     * 搜索走的是扩展通道的工具（`deepseek_web_search`），OpenAI 兼容通道没有等价入口。
     * 所以通道不支持时干脆不显示，而不是留一个按下去什么都不发生的开关。
     */
    val supportsWebSearch: Boolean get() = apiStyle == ApiStyle.ANTHROPIC

    /** 智能搜索开关：这一轮要不要向服务端声明 `deepseek_web_search`。 */
    var webSearch by mutableStateOf(false)
        private set

    /**
     * 思考内容是否在**正文开始时自动收起**（全局显示设置）。
     *
     * 语义定案见 `TODO.md`：开着 → 正文一出现就收成一行「已深度思考 · N 秒」；
     * 关掉 → 全文保持展开，想收自己点。**只影响定稿后的初态与自动收起**，
     * 流式期间的尾巴模式（只看最后几行）与它无关。
     */
    var autoCollapseThinking by mutableStateOf(true)
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
        if (!supportsWebSearch) return
        val next = !webSearch
        webSearch = next
        viewModelScope.launch { settingsStore.setWebSearch(next) }
    }

    var refreshingModels by mutableStateOf(false)
        private set

    var modelHint by mutableStateOf<String?>(null)
        private set

    private var streamJob: Job? = null
    private var streamGeneration = 0L

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
    private var stored: StoredSettings = StoredSettings()
    private var previousProviderKind: ProviderKind? = null

    /** 上一次见到的端点，用来判断"模型列表该不该作废 / 换一份"。 */
    private var previousBaseUrl: String? = null

    /** 上一次见到的远端模型列表（落库的那份），同理只用来比较。 */
    private var previousModelIds: List<String> = emptyList()

    /** 流式 checkpoint 的节流计时。 */
    private var lastCheckpointAt = 0L

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
            snapshotFlow { StatsInput(messages.toList(), settings, presets, compaction) }
                .collectLatest { input ->
                    delay(ContextStatsDebounceMs)
                    val stats = withContext(Dispatchers.Default) { computeContextStats(input) }
                    contextStats = stats
                }
        }

        /*
         * 启动时**不恢复上次打开的会话**，一律停在「新对话」。
         *
         * 早先是恢复的，代价是：每次打开 App 都直接落进上一次那段对话里，
         * 想开新的得先点一下「新建对话」。而这个 App 的日常用法是
         * "想起来一句就写" —— 停在空白页比落在半篇文章里更贴近它。
         *
         * 连带把 `lastConversationId` 这条偏好整个删了：没人读的写
         * 就是死数据（同样的判断见 §4.2 与 §17.6）。
         */
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
        if (endpointChanged || value.modelIds != previousModelIds) {
            previousProviderKind = kind
            previousBaseUrl = value.baseUrl
            previousModelIds = value.modelIds
            models = ModelCatalog.pickerModels(kind, value.modelIds)
            // 端点换了，上一轮的"已拉取 N 个"不再成立；只是列表更新则保留提示
            if (endpointChanged) modelHint = null
        }
        apiStyle = ApiStyle.resolve(kind, value.apiStyleId, value.baseUrl)
        webSearch = value.webSearch
        autoCollapseThinking = value.autoCollapseThinking
        ttsVoiceName = value.ttsVoiceName
        autoReadAloud = value.autoReadAloud
        showImageSkipNotice = value.showImageSkipNotice
        if (!showImageSkipNotice) imageSkipNotice = null
        officialTts = if (DeepSeekTts.isOfficialTtsVoiceAvailable(value.providerId, value.apiKey)) {
            DeepSeekTts.Config(value.apiKey)
        } else {
            null
        }
        settings = settings.copy(
            model = ModelCatalog.find(kind, value.modelId),
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

    fun openConversation(id: String, targetMessageId: String? = null, query: String = "") {
        if (id == currentConversationId && targetMessageId == null) return
        searchTarget = targetMessageId?.let { SearchTarget(it, query, ++searchRequest) }
        imageSkipNotice = null
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
            // 先清扫上次被中断的流式消息，否则它会以 STREAMING 状态卡在界面上
            repository.resolveDanglingStreams(id)
            if (targetMessageId != null) repository.revealSearchMessage(id, targetMessageId)

            val loaded = repository.loadActivePath(id)

            val loadedCompaction = repository.getCompactions(id).lastOrNull()
            val conversation = repository.findConversation(id)
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
            withContext(Dispatchers.Default) {
                loaded.asReversed()
                    .take(WarmTailMessages)
                    // 超长消息不预热：解析它本身就要几百毫秒，预热只会把后台线程占满
                    .filter { it.content.length <= WarmTailMaxChars }
                    .forEach { message -> warmMarkdownCache(message.content) }
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
            if (conversation != null) {
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
            }
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
        viewModelScope.launch {
            val result = chatApi.fetchModels(
                baseUrl = source.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                apiKey = source.apiKey,
            )
            if (stored.activeApiProfileId != source.activeApiProfileId || stored.baseUrl != source.baseUrl) {
                refreshingModels = false
                return@launch
            }
            result.onSuccess { ids ->
                models = ModelCatalog.pickerModels(providerKind, ids)
                modelHint = Res.get(R.string.models_fetched, ids.size)
            }
            result.onFailure { error ->
                modelHint = Res.get(R.string.models_fetch_failed, error.userMessage()) +
                    if (!stored.hasKey) Res.get(R.string.models_fetch_needs_key) else ""
            }
            // 落库：设置页的「模型」选择器读的是这份，重启后也还在
            result.getOrNull()?.let { settingsStore.setModelIds(it, source.activeApiProfileId, source.baseUrl) }
            refreshingModels = false
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
        val id = repository.createConversation(
            mode = settings.mode,
            provider = providerKind.name,
            model = settings.model.id,
            systemPrompt = settings.systemPrompt,
            // 勾好的预设要跟着一起落库，否则第一条消息之后就再也找不回来了
            enabledPresetIds = settings.enabledPresetIds,
            // 用户在发第一条消息之前就起好了名 → 直接用它，不再让 AI 起
            title = pendingTitle ?: Res.get(R.string.conversation_default_title),
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
        currentConversationId = id
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
    private suspend fun reloadPath(conversationId: String) {
        val generation = switchGeneration
        val path = repository.loadActivePath(conversationId)
        if (conversationId != currentConversationId || generation != switchGeneration) return
        messages.clear()
        messages.addAll(path)
        pathRevision++
    }

    // ── 上下文压缩 ────────────────────────────────────────

    /**
     * 压缩上下文：把较早的对话交给模型总结成「前情提要」。
     *
     * 三件事刻意分开：
     * - **不删本地消息** —— 压缩只影响 `buildRequestMessages()` 里挑哪些发出去
     * - **保留最近 [KEEP_RECENT] 条完整消息** —— 最近的对白是文风与语气的锚，
     *   被摘要成"他们继续交谈"会立刻丢失质感
     * - **摘要可读可改** —— AI 记错人名地名时用户能直接修正（见 [updateCompactionSummary]）
     */
    fun compactContext() {
        if (compacting || isStreaming) return
        val conversationId = currentConversationId ?: return

        val existing = validCompaction(messages, compaction)

        /*
         * **增量滚动压缩**：只总结上次摘要之后的新增部分。
         *
         * 每次从头重压一遍的话，早先的情节会被反复摘要 ——
         * 信息每过一轮就衰减一次，几轮下来人名地名就开始漂。
         */
        val alreadyCovered = existing
            ?.let { c -> messages.indexOfFirst { it.id == c.throughMessageId } }
            ?: -1
        val candidates = messages
            .filterIndexed { index, _ -> index > alreadyCovered }
            .filter { !it.streaming && it.content.isNotBlank() }

        if (candidates.size <= KEEP_RECENT) {
            banner = Res.get(R.string.compact_too_few)
            return
        }

        // 保留最后 KEEP_RECENT 条；其余交给模型总结
        var cutIndex = candidates.size - KEEP_RECENT

        /*
         * 切割点必须落在**用户消息**上，让保留下来的第一条是 user。
         * 否则请求会出现"一个没有问题的回答"——服务端多半能接受，
         * 但语义已经不对，模型容易顺着自说自话。
         */
        while (cutIndex > 0 && candidates[cutIndex].role != MessageRole.USER) {
            cutIndex--
        }
        if (cutIndex <= 0) {
            banner = Res.get(R.string.compact_too_few)
            return
        }

        val toSummarize = candidates.take(cutIndex)
        val throughId = toSummarize.last().id
        val previousSummary = existing?.summary?.takeIf { it.isNotBlank() }

        compacting = true
        compactPreview = ""
        banner = null
        imageSkipNotice = null

        compactJob = viewModelScope.launch {
            val transcript = buildString {
                if (previousSummary != null) {
                    append(Res.get(R.string.prompt_compact_existing))
                    append(previousSummary)
                    append(Res.get(R.string.prompt_compact_new))
                }
                toSummarize.forEach { message ->
                    append(Res.get(if (message.role == MessageRole.USER) R.string.prompt_user_prefix else R.string.prompt_ai_prefix))
                    append(message.content.trim())
                    // 文本附件的内容要进摘要，否则"我上传的那份设定"会在压缩后凭空消失
                    message.attachments
                        .filter { it.kind == Attachment.Kind.TEXT }
                        .forEach { append("\n").append(it.toPromptBlock()) }
                    if (message.attachments.any { it.isImage }) append(Res.get(R.string.prompt_has_image))
                    append("\n\n")
                }
            }

            val collected = StringBuilder()
            var failure: String? = null

            try {
                chatApi.streamChat(
                    baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                    apiKey = stored.apiKey,
                    model = settings.model,
                    messages = listOf(
                        OutMessageDto(
                            role = "system",
                            content = MessageContent.text(Res.get(R.string.prompt_compact_system)),
                        ),
                        OutMessageDto(
                            role = "user",
                            content = MessageContent.text(transcript),
                        ),
                    ),
                    // 总结不需要思考，也不需要保留已存在的摘要上下文
                    thinkingEnabled = false,
                    temperature = 0.3,
                    extraHeaders = providerKind.headersFor(openCodeSessionId),
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            collected.append(event.text)
                            compactPreview = collected.toString()
                        }

                        // 流内错误对象是**上游网关原文**：先翻成人话，认不出才保留原文
                        is StreamEvent.Error -> failure = serverErrorText(event.message)
                        else -> Unit
                    }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Exception) {
                failure = t.userMessage()
            }

            val summary = collected.toString().trim()
            if (failure != null || summary.isEmpty()) {
                banner = Res.get(R.string.compact_failed_detail, failure ?: Res.get(R.string.error_no_content))
                compacting = false
                compactPreview = ""
                return@launch
            }

            val entity = CompactionEntity(
                id = existing?.id ?: UUID.randomUUID().toString(),
                conversationId = conversationId,
                throughMessageId = throughId,
                summary = summary,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            try {
                repository.saveCompaction(entity)
                if (conversationId == currentConversationId) compaction = entity
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (conversationId == currentConversationId) banner = Res.get(R.string.compact_failed_detail, error.userMessage())
            } finally {
                if (conversationId == currentConversationId) {
                    compacting = false
                    compactPreview = ""
                }
            }
        }
    }

    /** 放弃压缩，恢复发送全部消息。**本地消息一条都没少。** */
    fun clearCompaction() {
        val active = compaction ?: return
        compaction = null
        viewModelScope.launch { repository.deleteCompaction(active.id) }
    }

    /** 手工修正摘要（AI 记错人名地名时用）。 */
    fun updateCompactionSummary(text: String) {
        val active = compaction ?: return
        val updated = active.copy(summary = text.trim(), updatedAt = System.currentTimeMillis())
        compaction = updated
        viewModelScope.launch { repository.saveCompaction(updated) }
    }

    fun cancelCompaction() {
        compactJob?.cancel()
        compactJob = null
        compacting = false
        compactPreview = ""
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

    private fun computeContextStats(input: StatsInput): ContextStats {
        val messages = input.messages
        val settings = input.settings
        val presets = input.presets
        val active = validCompaction(messages, input.compaction)

        // —— 实际会发给 API 的部分 ——
        val system = settings.composedSystemPrompt(presets)
        val outgoing = orderedOutgoingMessages(messages, active)
        val inContext = buildList {
            if (system.isNotBlank()) add(system)
            if (active != null && active.summary.isNotBlank()) add(active.summary)
            outgoing.forEach { add(it.content) }
        }
        // 附件也要算进去：图片按每张上限 1024、文本附件按字数（见 RequestContent）
        val attachmentTokens = outgoing.sumOf { attachmentTokenEstimate(it.attachments) }

        // —— 本地全部内容（含被压缩掉的）——
        val allTexts = messages.filter { !it.streaming }.map { it.content }

        val ttfts = messages.mapNotNull { it.ttftMs }
        val tpsList = messages.mapNotNull { it.tps }
        val thinking = messages.mapNotNull { it.reasoningSeconds }

        return ContextStats(
            usedTokens = TokenEstimate.ofAll(inContext) + attachmentTokens,
            windowTokens = settings.model.contextWindow,
            messageCount = messages.size,
            totalTokens = TokenEstimate.ofAll(allTexts),
            compactedTokenSaving = run {
                if (active == null) return@run 0
                val cut = messages.indexOfFirst { it.id == active.throughMessageId }
                if (cut < 0) return@run 0
                TokenEstimate.ofAll(messages.take(cut + 1).map { it.content }) -
                    TokenEstimate.of(active.summary)
            },
            avgTtftMs = ttfts.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            avgTps = tpsList.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            totalThinkingSeconds = thinking.takeIf { it.isNotEmpty() }?.sum(),
            hasCompaction = active != null,
        )
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
    fun retryMessage(target: UiMessage) {
        if (isStreaming || target.role != MessageRole.ASSISTANT) return
        val conversationId = currentConversationId ?: return

        viewModelScope.launch {
            switchJob?.join()
            repository.resetForRetry(target.id, settings.model.id)
            reloadPath(conversationId)
            startStream(conversationId, target.id)
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
        if (isStreaming || target.role != MessageRole.ASSISTANT) return
        val conversationId = currentConversationId ?: return
        val index = messages.indexOfFirst { it.id == target.id }
        if (index < 0) return

        viewModelScope.launch {
            // 挂在**被重新生成那条的父亲**下 → 成为它的兄弟，旧回答留着
            val fresh = repository.addActiveVariant(
                conversationId = conversationId,
                parentId = target.parentId,
                role = MessageRole.ASSISTANT,
                content = "",
                reasoning = "",
                status = MessageStatus.STREAMING,
            )
            reloadPath(conversationId)
            startStream(conversationId, fresh.id)
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
        if (isStreaming) return
        val conversationId = currentConversationId ?: return
        val index = messages.indexOfFirst { it.id == target.id }
        if (index < 0) return
        if (!canContinueReply(target, isLastOnPath = index == messages.lastIndex)) return

        viewModelScope.launch {
            switchJob?.join()
            repository.resumeStreaming(target.id)
            patch(target.id) {
                it.copy(
                    status = MessageStatus.STREAMING,
                    errorMessage = null,
                    stopReason = null,
                )
            }
            startStream(conversationId, target.id, prefix = target)
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

    /** 删除一条消息 —— 连同它的整棵子树。 */
    fun deleteMessage(target: UiMessage) {
        if (isStreaming) return
        val conversationId = currentConversationId ?: return
        viewModelScope.launch {
            repository.deleteMessage(target.id)
            reloadPath(conversationId)
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
    fun switchVariant(target: UiMessage, variantIndex: Int) {
        if (isStreaming) return
        val conversationId = currentConversationId ?: return
        if (variantIndex !in 0 until target.variantCount) return

        viewModelScope.launch {
            val id = repository.messageIdAt(conversationId, target.parentId, variantIndex)
                ?: return@launch
            repository.activateVariant(id, conversationId, target.parentId)
            reloadPath(conversationId)
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

    private fun startStream(conversationId: String, replyId: String, prefix: UiMessage? = null) {
        if (conversationId != currentConversationId) return
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
        isStreaming = true
        // 生成期间把进程提到前台服务优先级：退到后台 / 熄屏也继续收
        BackgroundGeneration.sync(true)
        stoppedByUser = false
        lastCheckpointAt = System.currentTimeMillis()
        val extended = apiStyle == ApiStyle.ANTHROPIC

        streamJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var firstTokenAt: Long? = null
            var contentChars = 0

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
            var dirty = false

            fun publishBuffered() {
                if (reasonBuf.isNotEmpty()) { reasoningText += reasonBuf.toString(); reasonBuf.clear() }
                if (contentBuf.isNotEmpty()) { contentText += contentBuf.toString(); contentBuf.clear() }
                patch(replyId) { it.copy(reasoning = reasoningText.ifEmpty { null }, content = contentText) }
            }

            /** 正文（或正文块）一出现 = 思维链已经写完，思考栏当场收口。 */
            fun noteBody() {
                if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()
                if (reasoningEndedAt != null) return
                val endedAt = System.currentTimeMillis()
                reasoningEndedAt = endedAt
                val ms = endedAt - (reasoningStartedAt ?: startedAt)
                patch(replyId) {
                    it.copy(reasoningDone = true, reasoningSeconds = thinkingSeconds(ms))
                }
            }

            fun noteThinking() {
                if (reasoningStartedAt == null) reasoningStartedAt = System.currentTimeMillis()
            }

            // 节流提交器：与 collect 同在主线程调度器，天然串行，无需加锁
            val flusher = launch {
                while (isActive) {
                    delay(STREAM_THROTTLE_MS)
                    if (!dirty) continue

                    if (accumulator != null) {
                        // 块通道是**全量替换**：顺序与内容都由累加器持有
                        timeline = accumulator.blocks
                        patch(replyId) {
                            it.copy(
                                content = accumulator.answer,
                                reasoning = accumulator.thinking.ifEmpty { null },
                                timeline = accumulator.blocks,
                            )
                        }
                    } else {
                        publishBuffered()
                    }
                    if (accumulator != null) { reasoningText = accumulator.thinking; contentText = accumulator.answer }
                    dirty = false

                    // §4.4：流中每约 1.5s 落一次盘，崩溃不丢内容
                    val now = System.currentTimeMillis()
                    if (now - lastCheckpointAt >= CHECKPOINT_INTERVAL_MS) {
                        lastCheckpointAt = now
                        val r = reasoningText
                        val c = contentText
                        val t = timeline
                        withContext(NonCancellable) {
                            runCatching { repository.checkpoint(replyId, c, r, t) }
                        }
                    }
                }
            }

            var failure: String? = null
            var cancelled = false
            var serverTitle: String? = null

            try {
                if (accumulator != null) {
                    chatApi.streamMessage(
                        baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                        apiKey = stored.apiKey,
                        request = buildAnthropicRequestNow(prefix),
                        extraHeaders = providerKind.headersFor(openCodeSessionId),
                    ).collect { event ->
                        when (event) {
                            is StreamEvent.BlockStart -> {
                                if (event.block is ReplyBlock.Text) noteBody()
                                if (event.block is ReplyBlock.Thinking) noteThinking()
                                accumulator.consume(event, System.currentTimeMillis())
                                dirty = true
                            }

                            is StreamEvent.TextDelta -> {
                                if (event.text.isNotEmpty()) {
                                    noteBody()
                                    contentChars += event.text.length
                                }
                                accumulator.consume(event, System.currentTimeMillis())
                                dirty = true
                            }

                            is StreamEvent.ThinkingDelta -> {
                                noteThinking()
                                accumulator.consume(event, System.currentTimeMillis())
                                dirty = true
                            }

                            is StreamEvent.Title -> serverTitle = event.text

                            is StreamEvent.CitationDelta, is StreamEvent.SignatureDelta,
                            is StreamEvent.BlockStop, is StreamEvent.Stopped -> {
                                accumulator.consume(event, System.currentTimeMillis())
                                dirty = true
                            }

                            is StreamEvent.Usage -> recordUsage(event)

                            StreamEvent.Done -> Unit

                            // 流内错误对象是**上游网关原文**（如 `No available channel …`）：
                            // 先翻成人话，认不出才保留原文（见 `serverErrorText`）
                            is StreamEvent.Error -> failure = serverErrorText(event.message)

                            // 块通道不该出现这两类增量；出现了说明通道配错，忽略
                            is StreamEvent.Content, is StreamEvent.Reasoning -> Unit
                        }
                    }
                } else {
                    chatApi.streamChat(
                        baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                        apiKey = stored.apiKey,
                        model = settings.model,
                        messages = buildRequestMessages(),
                        thinkingEnabled = settings.reasoningLevel.enabled,
                        reasoningEffort = settings.reasoningEffort,
                        maxTokens = settings.maxTokens,
                        temperature = settings.temperature.toDouble(),
                        topP = settings.topP.toDouble(),
                        extraHeaders = providerKind.headersFor(openCodeSessionId),
                    ).collect { event ->
                        when (event) {
                            is StreamEvent.Reasoning -> {
                                /*
                                 * 用户选了「关闭思考」就不收思考内容。
                                 *
                                 * 请求里已经带了 `thinking.type = disabled`，但**有些模型 / 网关
                                 * 仍会吐 `reasoning_content`** —— 实测：opencode 那边已停用的
                                 * `deepseek-v4-flash` 会被路由到 `deepseek-flash`，而网关不认
                                 * 我们发过去的 disabled。
                                 *
                                 * 既然用户选的是关闭，就当它没来过：不落库、不显示，
                                 * 界面与设置保持一致。（服务端是否真的少算这部分 token，
                                 * 客户端管不着 —— 那是上游的事。）
                                 */
                                if (!settings.reasoningLevel.enabled) return@collect
                                noteThinking()
                                reasonBuf.append(event.text)
                                dirty = true
                            }

                            is StreamEvent.Content -> {
                                noteBody()
                                contentBuf.append(event.text)
                                contentChars += event.text.length
                                dirty = true
                            }

                            /* 有些网关把用量放在流末尾单独一帧 —— 这里如实收下，
                               缓存命中率就是从这里来的（见 recordUsage）。 */
                            is StreamEvent.Usage -> recordUsage(event)

                            StreamEvent.Done -> Unit

                            // 流内错误对象是**上游网关原文**（如 `No available channel …`）：
                            // 先翻成人话，认不出才保留原文（见 `serverErrorText`）
                            is StreamEvent.Error -> failure = serverErrorText(event.message)

                            // 块级事件只属于 Anthropic 通道；这条路径上忽略
                            else -> Unit
                        }
                    }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                cancelled = true
            } catch (t: Throwable) {
                if (!stoppedByUser) failure = t.userMessage()
            } finally {
                flusher.cancel()
                if (accumulator != null) {
                    accumulator.settle(System.currentTimeMillis())
                    timeline = accumulator.blocks
                    patch(replyId) {
                        it.copy(
                            content = accumulator.answer,
                            reasoning = accumulator.thinking.ifEmpty { null },
                            timeline = accumulator.blocks,
                        )
                    }
                } else {
                    publishBuffered()
                }
            }

            val finishedAt = System.currentTimeMillis()
            /*
             * 这里**不做任何"审核/撤回"处理**。
             *
             * 客户端只负责一件事：服务端发来什么就显示什么、存什么。`stop_reason` 原样记进
             * 数据库（`stopReason`），不据此删改内容 —— 早先按 Anthropic 文档的
             * "refusal 时丢弃已下发内容"做过一次，结果是一段已经写完的回复在收尾瞬间被吞掉，
             * 用户看到的只有"写完了，然后没了"。那是数据丢失，客户端没有理由替服务端做这件事。
             */
            val status = when {
                cancelled || generation != streamGeneration -> MessageStatus.STOPPED
                failure != null -> MessageStatus.ERROR
                else -> MessageStatus.DONE
            }
            val ttft = firstTokenAt?.minus(startedAt)
            val seconds = (finishedAt - startedAt).coerceAtLeast(1) / 1000f
            // 粗估：中文约 1 token ≈ 1.6 字符，仅用于性能仪表展示
            val tps = if (contentChars > 0) (contentChars / 1.6f) / seconds else null
            val finalContent = accumulator?.answer ?: contentText
            val finalReasoning = accumulator?.thinking ?: reasoningText
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
            val thinkingMs = reasoningStartedAt?.let { start ->
                (reasoningEndedAt ?: finishedAt) - start
            } ?: prefix?.reasoningSeconds?.times(1000)

            patch(replyId) {
                it.copy(
                    status = status,
                    errorMessage = failure,
                    ttftMs = ttft,
                    tps = tps,
                    reasoningSeconds = thinkingMs?.let { thinkingSeconds(it) },
                    timeline = timeline,
                    stopReason = accumulator?.stopReason,
                )
            }

            // 终写一次：即使进程被杀，内容也已落库
            val persisted = withContext(NonCancellable) {
                runCatching {
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
                    )
                    repository.touchConversation(conversationId)
                }.onFailure { error ->
                    android.util.Log.e("SimpleChat/Save", "Reply persistence failed: ${error.javaClass.simpleName}")
                    if (conversationId == currentConversationId) {
                        banner = Res.get(R.string.reply_save_failed)
                        patch(replyId) { it.copy(status = MessageStatus.ERROR, errorMessage = Res.get(R.string.reply_save_failed)) }
                    }
                }.isSuccess
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
            if (generation == streamGeneration && conversationId == currentConversationId && status == MessageStatus.DONE && title.isNullOrEmpty()) {
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
    private suspend fun buildAnthropicRequestNow(prefix: UiMessage?): AnthropicRequest {
        skippedNoVision = 0; skippedUnreadable = 0
        val active = validCompaction(messages, compaction)
        val system = buildString {
            append(settings.composedSystemPrompt(presets))
            if (active != null && active.summary.isNotBlank()) {
                if (isNotEmpty()) appendLine().appendLine()
                append(Res.get(R.string.prompt_recap_intro))
                append(active.summary)
            }
        }
        val requestTitle = shouldRequestTitle()
        if (requestTitle) serverTitleRequestedFor = currentConversationId

        /*
         * 续写时把这条 assistant 从历史里摘出去：它要以**末尾前缀**的身份出现，
         * 留在历史里就是同一轮回答发了两遍，服务端会把它当成两个不同的回答。
         */
        val path = orderedOutgoingMessages(messages, active)
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
                if (message.attachments.isEmpty()) message
                else message.copy(attachments = outgoingAttachments(message.attachments))
            }

        /*
         * 官方域才发 DeepSeek 扩展字段（safety_check / 搜索工具 / 服务端标题 /
         * latest_reminder 角色 / thinking.type=disabled）。自建网关或 Claude 兼容中转
         * 遇到这些会 400，而不是"忽略" —— 见 `buildAnthropicRequest` 的说明。
         */
        val officialDeepSeek = isOfficialDeepSeekEndpoint(
            stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
        )
        return buildAnthropicRequest(
            model = settings.model.id,
            systemPrompt = system,
            path = path,
            searchEnabled = webSearch,
            thinkingEnabled = settings.thinkingEnabled,
            requestTitle = requestTitle,
            latestReminder = latestReminder(),
            maxTokens = settings.maxTokens,
            prefix = prefix,
            deepSeekExtensions = officialDeepSeek,
            temperature = settings.temperature.toDouble(),
            topP = settings.topP.toDouble(),
        ).also { request ->
            if (request.messages.none { it.role == "user" }) throw com.simplechat.app.data.AppTextException(R.string.error_no_sendable_messages)
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
    private suspend fun outgoingAttachments(attachments: List<Attachment>): List<Attachment> {
        if (attachments.isEmpty()) return attachments
        var filtered = attachments
        if (!settings.model.supportsVision) {
            filtered = attachments.filterNot { it.isImage }
            val dropped = attachments.size - filtered.size
            skippedNoVision += dropped
        }
        // 字节读回 + 图片按真实内容校正（格式不收的转码、坏图丢掉）。见 ApiImage
        val (prepared, unreadable) = repository.prepareRequestAttachments(filtered)
        skippedUnreadable += unreadable
        if (showImageSkipNotice) {
            imageSkipNotice = when {
                skippedUnreadable > 0 -> Res.get(R.string.images_skipped_unreadable, skippedUnreadable)
                skippedNoVision > 0 -> Res.get(R.string.images_skipped_no_vision, skippedNoVision)
                else -> null
            }
        }
        return prepared
    }

    private suspend fun buildRequestMessages(): List<OutMessageDto> {
        skippedNoVision = 0; skippedUnreadable = 0
        val request = mutableListOf<OutMessageDto>()

        val system = settings.composedSystemPrompt(presets)
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
        orderedOutgoingMessages(messages, active).forEach { message ->
            /*
             * 空消息不发：历史里出错/被中断的 assistant、既没正文也没附件的 user
             * 是可能存在的，而部分兼容网关对空 `content` 直接 400 ——
             * 一条空消息不值得让整段历史发不出去（见 `isSendableOutgoing`）。
             */
            val outgoing = if (message.role == MessageRole.USER && message.attachments.isNotEmpty()) {
                message.copy(attachments = outgoingAttachments(message.attachments))
            } else message
            if (!isSendableOutgoing(outgoing)) return@forEach
            request += OutMessageDto(
                role = if (message.role == MessageRole.USER) "user" else "assistant",
                /*
                 * 只有 user 消息可能带图；组装规则见 `userContent`
                 * （无附件 → 纯字符串；只有文本附件 → 纯字符串；有图 → content 数组）。
                 *
                 * assistant 消息即使历史里挂着附件也只发正文：图片放进 assistant
                 * 在协议里是不合法的（会 400），而 AI 回复本来也不该带图。
                 */
                content = if (message.role != MessageRole.USER || message.attachments.isEmpty()) {
                    MessageContent.text(message.content)
                } else {
                    userContent(outgoing.content, outgoing.attachments)
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

        /** 压缩时保留最近多少条完整消息。 */
        private const val KEEP_RECENT = 6

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
                ChatViewModel(chatApi, settingsStore, repository, openCodeSessionId)
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








