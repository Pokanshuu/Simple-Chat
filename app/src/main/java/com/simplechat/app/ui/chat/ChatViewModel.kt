package com.simplechat.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.simplechat.app.R
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.Res
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.data.StoredSettings
import com.simplechat.app.data.TITLE_MATERIAL_CHARS
import com.simplechat.app.data.TITLE_MAX_TOKENS
import com.simplechat.app.data.isAutoTitle
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.data.cleanGeneratedTitle
import com.simplechat.app.data.derivedTitle
import com.simplechat.app.data.titleSystemPrompt
import com.simplechat.app.data.userMessage
import com.simplechat.app.db.CompactionEntity
import com.simplechat.app.db.DraftEntity
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.MessageContent
import com.simplechat.app.net.ModelCatalog
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.net.OutMessageDto
import com.simplechat.app.net.ProviderKind
import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.headersFor
import androidx.compose.runtime.derivedStateOf
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * - **节流合并**：token 先进缓冲区，由独立 flusher 每 [STREAM_THROTTLE_MS] 合并提交一次，
 *   避免每个 token 都触发列表重组（§8）。
 * - **不发送 `reasoning_content`**：本期不带 `tools`，官方明确无需回传（§11.1）。
 */
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

    var banner by mutableStateOf<String?>(null)
        private set

    var models by mutableStateOf(ModelCatalog.knownModels(ProviderKind.DEEPSEEK))
        private set

    var refreshingModels by mutableStateOf(false)
        private set

    var modelHint by mutableStateOf<String?>(null)
        private set

    private var streamJob: Job? = null

    /** 会话切换任务；`send()` 会等它完成，避免把消息插进半成品列表。 */
    private var switchJob: Job? = null

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
        if (kind != previousProviderKind) {
            previousProviderKind = kind
            models = ModelCatalog.knownModels(kind)
            modelHint = null
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
    fun openConversation(id: String) {
        if (id == currentConversationId) return
        stop()
        switchJob?.cancel()

        val generation = ++switchGeneration
        currentConversationId = id
        // 标题立刻开始观察；下面那句清空只是防止旧标题残留一帧
        observeTitle(id)
        switching = true
        compaction = null
        currentConversationTitle = null

        switchJob = viewModelScope.launch {
            // 先清扫上次被中断的流式消息，否则它会以 STREAMING 状态卡在界面上
            repository.resolveDanglingStreams(id)

            val loaded = repository.loadActivePath(id)
            val loadedCompaction = repository.getCompactions(id).lastOrNull()
            val conversation = repository.findConversation(id)

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
                    enabledPresetIds = repository.getEnabledPresetIds(id),
                )
            }
            switching = false
        }
    }

    fun newConversation() {
        stop()
        cancelCompaction()
        switchJob?.cancel()
        titleObserverJob?.cancel()
        currentConversationId = null
        // 「新对话」的待用标题还在（与草稿一样，不因为点了新建就被吞掉）
        currentConversationTitle = pendingTitle
        compaction = null
        messages.clear()
        banner = null
        switching = false
        settings = settings.copy(systemPrompt = "")
    }

    // ── 设置 ──────────────────────────────────────────────

    fun updateSettings(next: ConversationSettings) {
        val previous = settings
        settings = next
        viewModelScope.launch {
            if (next.model.id != previous.model.id) settingsStore.setModel(next.model.id)
            if (next.reasoningLevel != previous.reasoningLevel) {
                settingsStore.setReasoningLevel(next.reasoningLevel.name)
            }
            if (next.temperature != previous.temperature) settingsStore.setTemperature(next.temperature)
            if (next.topP != previous.topP) settingsStore.setTopP(next.topP)
            if (next.maxTokens != previous.maxTokens) settingsStore.setMaxTokens(next.maxTokens ?: 0)
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
        viewModelScope.launch {
            chatApi.fetchModels(
                baseUrl = stored.baseUrl.ifBlank { providerKind.defaultBaseUrl },
                apiKey = stored.apiKey,
            )
                .onSuccess { ids ->
                    models = ModelCatalog.merge(providerKind, ids)
                    modelHint = Res.get(R.string.models_fetched, ids.size)
                }
                .onFailure { error ->
                    modelHint = Res.get(R.string.models_fetch_failed, error.userMessage()) +
                        if (!stored.hasKey) Res.get(R.string.models_fetch_needs_key) else ""
                }
            refreshingModels = false
        }
    }

    // ── 发送 ──────────────────────────────────────────────

    fun send(prompt: String, attachments: List<Attachment> = emptyList()) {
        val text = prompt.trim()
        // 允许「只有附件、没有文字」—— 丢一张图进来说"看看这个"是最自然的用法
        if ((text.isEmpty() && attachments.isEmpty()) || isStreaming) return

        if (!stored.hasKey && providerKind != ProviderKind.OPENCODE_GO) {
            banner = Res.get(R.string.error_no_api_key)
            return
        }

        banner = null

        viewModelScope.launch {
            // 会话还在切换 → 等它读完再发，否则消息会被插进半成品列表（顺序颠倒）
            switchJob?.join()

            val conversationId = ensureConversation()

            val user = repository.appendUserMessage(conversationId, text, attachments)
            // 首条用户消息会触发库里自动命名，顶栏同步一次
            currentConversationTitle = repository.findConversation(conversationId)?.title
            messages += user

            // 回复挂在**刚发的这条**下面
            val assistantId = repository.beginAssistantMessage(
                conversationId = conversationId,
                parentId = user.id,
                // 记下这条是谁写的 —— 事后换模型时，历史才分得清
                model = settings.model.id,
            )
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
                model = settings.model.id,
            )

            startStream(conversationId, assistantId)
        }
    }

    /**
     * 取当前会话；**已被删除就当作新对话**。
     *
     * 用户在抽屉里删掉正在看的会话时，`currentConversationId` 会短暂指向
     * 一个不存在的 id —— 此时直接往它下面插消息会撞外键约束。
     * 这里顺手把内存状态清干净，界面也就自然回到空态。
     */
    private suspend fun ensureConversation(): String {
        val existing = currentConversationId
        if (existing != null && repository.findConversation(existing) != null) return existing

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
        currentConversationTitle = repository.findConversation(id)?.title
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
        val path = repository.loadActivePath(conversationId)
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

        val existing = compaction

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

                        is StreamEvent.Error -> failure = event.message
                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
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
            repository.saveCompaction(entity)
            compaction = entity
            compacting = false
            compactPreview = ""
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
     * 用 `derivedStateOf` 缓存：流式期间每 50ms 就会重组一次，
     * 若每次都对全部消息重扫一遍，长会话里会白白吃掉帧预算。
     */
    val contextStats: ContextStats by derivedStateOf {
        val active = compaction

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

        ContextStats(
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
     * 编辑一条消息。
     *
     * 新版本挂在**原消息的父亲**下，成为它的**兄弟**：
     * 旧的那条连同它**整棵子树**原样留着，切回去就能看到。
     *
     * - [resend] = false：只换文字，新兄弟没有子树 → 路径在此结束
     * - [resend] = true：换文字后在新兄弟下再生成一条回复
     *
     * **两种角色都不删任何东西。** 这正是换成树模型换来的东西。
     */
    fun editMessage(target: UiMessage, newText: String, resend: Boolean, attachments: List<Attachment>? = null) {
        if (isStreaming) return
        val text = newText.trim()
        val kept = attachments ?: target.attachments
        if (text.isEmpty() && kept.isEmpty()) return
        val conversationId = currentConversationId ?: return

        viewModelScope.launch {
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
                // 仅改文字时保留思考内容；重发时旧思考已与新回复无关
                reasoning = target.reasoning.takeIf { !resend && target.role == MessageRole.ASSISTANT },
            )

            val replyId = if (resend) {
                // 回复挂在**新的**这条下面
                repository.beginAssistantMessage(
                    conversationId = conversationId,
                    parentId = fresh.id,
                    model = settings.model.id,
                )
            } else {
                null
            }

            reloadPath(conversationId)
            replyId?.let { startStream(conversationId, it) }
            if (replyId == null) repository.touchConversation(conversationId)
        }
    }

    /** 导出当前会话为 JSON；尚未落库的新对话返回 null。 */
    suspend fun exportCurrentConversation(): String? =
        currentConversationId?.let { repository.exportConversation(it) }

    /** 导出当前会话为 **Markdown 文稿**；尚未落库的新对话返回 null。 */
    suspend fun exportCurrentConversationMarkdown(): String? =
        currentConversationId?.let { repository.exportConversationMarkdown(it) }

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
        streamJob?.cancel()
        streamJob = null
    }

    // ── 流式实现 ──────────────────────────────────────────

    private fun startStream(conversationId: String, replyId: String) {
        isStreaming = true
        stoppedByUser = false
        lastCheckpointAt = System.currentTimeMillis()

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
            var dirty = false

            // 节流提交器：与 collect 同在主线程调度器，天然串行，无需加锁
            val flusher = launch {
                while (isActive) {
                    delay(STREAM_THROTTLE_MS)
                    if (dirty) {
                        flush(replyId, reasonBuf, contentBuf)
                        reasoningText = messages.firstOrNull { it.id == replyId }?.reasoning.orEmpty()
                        contentText = messages.firstOrNull { it.id == replyId }?.content.orEmpty()
                        dirty = false

                        // §4.4：流中每约 1.5s 落一次盘，崩溃不丢内容
                        val now = System.currentTimeMillis()
                        if (now - lastCheckpointAt >= CHECKPOINT_INTERVAL_MS) {
                            lastCheckpointAt = now
                            val r = reasoningText
                            val c = contentText
                            withContext(NonCancellable) {
                                runCatching { repository.checkpoint(replyId, c, r) }
                            }
                        }
                    }
                }
            }

            var failure: String? = null

            try {
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
                            if (reasoningStartedAt == null) {
                                reasoningStartedAt = System.currentTimeMillis()
                            }
                            reasonBuf.append(event.text)
                            dirty = true
                        }

                        is StreamEvent.Content -> {
                            if (firstTokenAt == null) firstTokenAt = System.currentTimeMillis()

                            /*
                             * 正文一出现 = 思维链已经写完。
                             *
                             * **就在这一刻**把思考栏收起来、把秒数定下来。
                             * 不能等整条流结束 —— 那时正文都写了一大半，
                             * 思考栏还挂在"正在思考"的尾巴模式上。
                             */
                            if (reasoningEndedAt == null) {
                                val endedAt = System.currentTimeMillis()
                                reasoningEndedAt = endedAt
                                val ms = endedAt - (reasoningStartedAt ?: startedAt)
                                patch(replyId) {
                                    it.copy(
                                        reasoningDone = true,
                                        reasoningSeconds = thinkingSeconds(ms),
                                    )
                                }
                            }

                            contentBuf.append(event.text)
                            contentChars += event.text.length
                            dirty = true
                        }

                        is StreamEvent.Usage -> Unit // 后续接入用量与成本展示

                        StreamEvent.Done -> Unit

                        is StreamEvent.Error -> failure = event.message
                    }
                }
            } catch (t: Throwable) {
                if (!stoppedByUser) failure = t.userMessage()
            } finally {
                flusher.cancel()
                flush(replyId, reasonBuf, contentBuf)
            }

            val finishedAt = System.currentTimeMillis()
            val status = when {
                stoppedByUser -> MessageStatus.STOPPED
                failure != null -> MessageStatus.ERROR
                else -> MessageStatus.DONE
            }
            val ttft = firstTokenAt?.minus(startedAt)
            val seconds = (finishedAt - startedAt).coerceAtLeast(1) / 1000f
            // 粗估：中文约 1 token ≈ 1.6 字符，仅用于性能仪表展示
            val tps = if (contentChars > 0) (contentChars / 1.6f) / seconds else null
            val finalContent = messages.firstOrNull { it.id == replyId }?.content.orEmpty()
            val finalReasoning = messages.firstOrNull { it.id == replyId }?.reasoning
            /*
             * **真实**思考时长 = 首条思维链 → 首条正文。
             *
             * 先前用的是 `finishedAt - startedAt`，也就是"想 + 写"的总时长，
             * 却挂在「已深度思考」后面 —— 想 5 秒、写 25 秒会显示成 30 秒。
             *
             * 没等到正文就结束（用户中断 / 出错）时，用结束时刻兜底。
             */
            val thinkingMs = reasoningStartedAt?.let { start ->
                (reasoningEndedAt ?: finishedAt) - start
            }

            patch(replyId) {
                it.copy(
                    status = status,
                    errorMessage = failure,
                    ttftMs = ttft,
                    tps = tps,
                    reasoningSeconds = thinkingMs?.let { thinkingSeconds(it) },
                )
            }

            // 终写一次：即使进程被杀，内容也已落库
            withContext(NonCancellable) {
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
                    )
                    repository.touchConversation(conversationId)
                }
            }

            if (failure != null) banner = failure
            isStreaming = false
            streamJob = null

            /*
             * 首轮回复落地后，用一个 **flash 档模型**给会话起个标题。
             *
             * 刻意放在这里而不是"发出去就生成"：拿到回复才知道这段在聊什么，
             * 只根据提问猜出来的标题经常跑偏。
             */
            if (status == MessageStatus.DONE) maybeGenerateTitle(conversationId)
        }
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

    private fun buildRequestMessages(): List<OutMessageDto> {
        val request = mutableListOf<OutMessageDto>()

        val system = settings.composedSystemPrompt(presets)
        if (system.isNotBlank()) {
            request += OutMessageDto(role = "system", content = MessageContent.text(system))
        }

        /*
         * 压缩摘要以 system 身份插在最前，并跳过被它覆盖的那些 slot。
         * 这是「只改变发给 API 的内容」的落点 —— 本地消息一条没动。
         */
        val active = compaction
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
            request += OutMessageDto(
                role = if (message.role == MessageRole.USER) "user" else "assistant",
                /*
                 * 只有 user 消息可能带图；组装规则见 `userContent`
                 * （无附件 → 纯字符串；只有文本附件 → 纯字符串；有图 → content 数组）。
                 */
                content = if (message.attachments.isEmpty()) {
                    MessageContent.text(message.content)
                } else {
                    userContent(message.content, message.attachments)
                },
            )
        }

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
        private const val STREAM_THROTTLE_MS = 50L
        private const val CHECKPOINT_INTERVAL_MS = 1_500L

        /**
         * 会话级设置的落库防抖。
         *
         * 补充提示词是**逐字输入**的，改一次写一次库没必要；
         * 停手 400ms 再写，既跟得上手感也不会把主线程拖住。
         */
        private const val SETTINGS_PERSIST_DEBOUNCE_MS = 400L

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

