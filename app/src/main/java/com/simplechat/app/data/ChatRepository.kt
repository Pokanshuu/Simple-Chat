package com.simplechat.app.data

import androidx.room.withTransaction
import com.simplechat.app.R
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.db.CompactionEntity
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.db.ConversationPresetEntity
import com.simplechat.app.db.ConversationSearchHit
import com.simplechat.app.db.DraftEntity
import com.simplechat.app.db.MessageEntity
import com.simplechat.app.db.MessageTree
import com.simplechat.app.db.PresetEntity
import com.simplechat.app.data.import.ExternalBackup
import com.simplechat.app.ui.chat.ConversationMode
import java.io.File
import com.simplechat.app.ui.chat.MessageRole
import com.simplechat.app.ui.chat.MessageStatus
import com.simplechat.app.ui.chat.Preset
import com.simplechat.app.ui.chat.UiMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 落库前的归一：空文本返回 `null`（= 删行、不留空壳），超长**截尾**到
 * [DraftEntity.MaxChars]（只截落库的那份，界面上照旧）。
 */
internal fun draftForStorage(text: String): String? =
    if (text.isEmpty()) null else text.take(DraftEntity.MaxChars)

/**
 * 待用标题的归一：`trim` 之后为空 / 全空白一律当**没起过名**。
 *
 * 空白标题比"新对话"更糟 —— 顶栏会空着一行，抽屉里那条也认不出来。
 */
internal fun titleForStorage(title: String?): String? = title?.trim()?.takeIf { it.isNotBlank() }

/**
 * 会话与消息的持久化门面。
 *
 * 两条通道刻意分开：
 * - **列表页**走 Room 的 `Flow`，天然响应式（重命名 / 置顶 / 删除立刻反映）。
 * - **对话流**以内存中的 `SnapshotStateList` 为准，按 §4.4 的策略**节流 checkpoint 落库**。
 *   若让每个 token 都回写 Room，一次 2000 字回复就是 2000 次磁盘事务。
 */
class ChatRepository(private val db: AppDatabase) {

    private val conversations = db.conversationDao()
    private val messages = db.messageDao()
    private val presets = db.presetDao()
    private val conversationPresets = db.conversationPresetDao()
    private val compactionDao = db.compactionDao()
    private val drafts = db.draftDao()

    // ── 会话列表 ──────────────────────────────────────────

    fun observeConversations(): Flow<List<ConversationEntity>> = conversations.observeAll()

    /**
     * 观察**单个会话**。
     *
     * 存在的理由只有一个：让「标题」有唯一真相。
     * 顶栏、抽屉、对话设置里的名称框都从这一列读，谁改了都从库流回来。
     */
    fun observeConversation(id: String): Flow<ConversationEntity?> = conversations.observeById(id)

    /** 全文搜索：标题或**消息正文**命中。 */
    fun searchConversations(query: String): Flow<List<ConversationSearchHit>> =
        conversations.search(query.trim())

    fun observePresets(): Flow<List<Preset>> = presets.observeAll().map { list -> list.map { it.toUi() } }

    suspend fun createConversation(
        mode: ConversationMode,
        provider: String,
        model: String,
        systemPrompt: String? = null,
        enabledPresetIds: List<String> = emptyList(),
        title: String = Res.get(R.string.conversation_default_title),
    ): String = db.withTransaction {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        conversations.upsert(
            ConversationEntity(
                id = id,
                title = title.trim().ifBlank { Res.get(R.string.conversation_default_title) },
                mode = mode.toStorage(),
                provider = provider,
                model = model,
                systemPrompt = systemPrompt?.trim()?.takeIf { it.isNotBlank() },
                createdAt = now,
                updatedAt = now,
            ),
        )
        writePresetLinks(id, enabledPresetIds)
        id
    }

    /**
     * 写入会话级设置：补充提示词 + 挂载的预设。
     *
     * ⚠️ 这两项**属于会话，不属于全局设置**。先前只在建会话那一刻写一次，
     * 之后在 ⚙ 面板里改了就不作数 —— 切走再切回来会退回旧值，
     * 预设更是从来没被写进去过（`openConversation` 也从不读它）。
     */
    suspend fun setConversationSettings(
        conversationId: String,
        systemPrompt: String,
        enabledPresetIds: List<String>,
    ) = db.withTransaction {
        conversations.setSystemPrompt(
            conversationId,
            systemPrompt.trim().takeIf { it.isNotBlank() },
        )
        writePresetLinks(conversationId, enabledPresetIds)
    }

    /** 该会话挂载的预设 id，按拼接顺序。 */
    suspend fun getEnabledPresetIds(conversationId: String): List<String> =
        conversationPresets.getFor(conversationId)
            .filter { it.enabled }
            .map { it.presetId }

    /** 预设挂载关系：**先清后写**，`sortOrder` 即拼接顺序。 */
    private suspend fun writePresetLinks(conversationId: String, presetIds: List<String>) {
        conversationPresets.clear(conversationId)
        if (presetIds.isEmpty()) return
        conversationPresets.upsertAll(
            presetIds.mapIndexed { index, presetId ->
                ConversationPresetEntity(
                    conversationId = conversationId,
                    presetId = presetId,
                    sortOrder = index,
                    enabled = true,
                )
            },
        )
    }

    suspend fun findConversation(id: String): ConversationEntity? = conversations.findById(id)

    suspend fun renameConversation(id: String, title: String) =
        conversations.rename(
            id,
            title.trim().ifBlank { Res.get(R.string.conversation_default_title) },
            System.currentTimeMillis(),
        )

    suspend fun setPinned(id: String, pinned: Boolean) =
        conversations.setPinned(id, pinned, if (pinned) System.currentTimeMillis() else null)

    suspend fun deleteConversations(ids: List<String>) {
        db.withTransaction {
            conversations.deleteAll(ids)
            // 会话没了草稿就没人读 —— 同步清掉，别留死数据
            drafts.clearAll(ids)
        }
    }

    suspend fun touchConversation(id: String) = conversations.touch(id, System.currentTimeMillis())

    // ── 会话分叉：「从这里新建对话」（§21）──────────────────

    /**
     * 把活跃路径上到 [fromMessageId] 为止的那一段，复制成一个**独立的新会话**。
     *
     * **原会话一个字节都不动** —— 这是这个功能的全部意义。
     *
     * - 只复制**当前选中的那一版**，不搬兄弟版本；新会话里全是单版本
     * - 消息 id 全部重挂（沿用旧 id 会撞主键），`parentId` 重新串成一条新链
     * - 会话级设置（模式 / Provider / 模型 / 系统提示词 / 挂载的预设）一并复制
     * - **压缩摘要不复制**：被它盖住的原文本来就在复制范围内，摘要是多余的
     * - 消息**保留原始 `createdAt`**：它记录的是"这些话什么时候写的"，
     *   重盖时间戳会让历史失真（树序本来也不依赖时间）
     *
     * @return 新会话 id；目标不在活跃路径上时返回 null
     */
    suspend fun forkConversation(fromMessageId: String): String? = db.withTransaction {
        val source = messages.findById(fromMessageId) ?: return@withTransaction null
        val origin = conversations.findById(source.conversationId) ?: return@withTransaction null

        val path = MessageTree.pathTo(messages.getAllFor(source.conversationId), fromMessageId)
        if (path.isEmpty()) return@withTransaction null

        val now = System.currentTimeMillis()
        val newId = UUID.randomUUID().toString()

        conversations.upsert(
            ConversationEntity(
                id = newId,
                title = forkedTitle(origin.title),
                mode = origin.mode,
                provider = origin.provider,
                model = origin.model,
                systemPrompt = origin.systemPrompt,
                pinned = false,
                pinnedAt = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        messages.upsertAll(
            MessageTree.forkMessages(path, conversationId = newId) {
                UUID.randomUUID().toString()
            },
        )
        writePresetLinks(newId, getEnabledPresetIds(origin.id))

        newId
    }

    /**
     * 副本标题后缀。把已有的后缀剥掉，免得"副本的副本"一路叠下去。
     * 中英两种后缀都要认 —— 会话可能建于另一种界面语言下。
     */
    private val forkSuffix = Regex("(（副本( \\d+)?）|\\(copy( \\d+)?\\))$")

    /**
     * 副本标题：`原标题（副本）`；同名已存在则 `（副本 2）`、`（副本 3）`…
     *
     * 会话列表靠标题区分，重名会让人根本认不出哪个是刚分出来的。
     * 后缀随界面语言取（`conversation_fork_suffix*`）。
     */
    private suspend fun forkedTitle(title: String): String {
        val base = title.replace(forkSuffix, "").ifBlank { title }
        val existing = conversations.allTitles().toHashSet()

        val first = base + Res.get(R.string.conversation_fork_suffix)
        if (first !in existing) return first

        var n = 2
        while (base + Res.get(R.string.conversation_fork_suffix_n, n) in existing) n++
        return base + Res.get(R.string.conversation_fork_suffix_n, n)
    }

    // ── 消息（树）─────────────────────────────────────────
    //
    // 一切以 `parentId` 为准，不再有"第几位"。当前对话 =
    // `MessageTree.activePath(all)` 走出来的那条路径。

    /** 当前对话：从根沿 active 走到底。 */
    suspend fun loadActivePath(conversationId: String): List<UiMessage> {
        val all = messages.getAllFor(conversationId)
        val counts = MessageTree.siblingCounts(all)
        return MessageTree.activePath(all).map { entity ->
            entity.toUi().copy(variantCount = counts[entity.parentId] ?: 1)
        }
    }

    /** 路径末尾节点 id；空会话返回 [MessageEntity.ROOT_PARENT]。 */
    suspend fun tailId(conversationId: String): String =
        messages.getAllFor(conversationId)
            .let { MessageTree.activePath(it).lastOrNull()?.id }
            ?: MessageEntity.ROOT_PARENT

    /**
     * 在 [parentId] 下挂一个新兄弟并激活它。
     *
     * 「发送」「重新生成」「编辑」全都走这里 —— 区别只是挂在谁下面：
     * - 发送：挂在当前路径末尾
     * - 重新生成：挂在**被重新生成那条的父亲**下（于是成为它的兄弟）
     * - 编辑：同上，旧的那条连同它的子树原样留着
     */
    private suspend fun addSibling(
        conversationId: String,
        parentId: String,
        role: MessageRole,
        content: String,
        attachments: List<Attachment> = emptyList(),
        reasoning: String? = null,
        status: MessageStatus = MessageStatus.DONE,
        /** 这条由哪个模型产生；用户消息传 null。 */
        model: String? = null,
    ): MessageEntity {
        val id = UUID.randomUUID().toString()
        messages.upsert(
            newMessageEntity(
                id = id,
                conversationId = conversationId,
                parentId = parentId,
                variantIndex = messages.nextVariantIndex(conversationId, parentId),
                role = role,
                content = content,
                attachments = attachments,
                reasoning = reasoning,
                status = status,
                model = model,
            ),
        )
        messages.activate(id, conversationId, parentId)
        return messages.findById(id)!!
    }

    /** 把实体包成 UI 模型，δ 补上兄弟数。 */
    private suspend fun toUiWithCounts(conversationId: String, entity: MessageEntity): UiMessage {
        val count = MessageTree.siblingCountsOf(messages.parentIds(conversationId))[entity.parentId] ?: 1
        return entity.toUi().copy(variantCount = count)
    }

    /**
     * 追加一条用户消息，返回它本身。
     *
     * 返回**真实 id** —— 早先调用方自己编了个 `local-user-<时间戳>`，
     * 于是内存里的这条与库里的记录对不上，任何按 id 回查都会落空。
     */
    suspend fun appendUserMessage(
        conversationId: String,
        content: String,
        attachments: List<Attachment> = emptyList(),
    ): UiMessage =
        db.withTransaction {
            val parentId = tailId(conversationId)
            val entity = addSibling(
                conversationId = conversationId,
                parentId = parentId,
                role = MessageRole.USER,
                content = content,
                attachments = attachments,
            )

            val now = System.currentTimeMillis()
            val conversation = conversations.findById(conversationId)
            // 只有正文参与标题推导 —— 一条纯图片消息不该把标题变成"image.jpg"
            val fallback = Res.get(R.string.conversation_default_title)
            val title = if (conversation != null && isAutoTitle(conversation.title)) {
                derivedTitle(content, fallback)
            } else {
                conversation?.title ?: fallback
            }
            conversations.rename(conversationId, title, now)

            toUiWithCounts(conversationId, entity)
        }

    /** 新建一条 streaming 状态的 assistant，挂在 [parentId] 下。 */
    suspend fun beginAssistantMessage(
        conversationId: String,
        parentId: String,
        model: String? = null,
    ): String =
        addSibling(
            conversationId = conversationId,
            parentId = parentId,
            role = MessageRole.ASSISTANT,
            content = "",
            reasoning = "",
            status = MessageStatus.STREAMING,
            model = model,
        ).id

    /** 流式 checkpoint。调用频率由 ViewModel 控制在 ~1.5s（§4.4）。 */
    suspend fun checkpoint(id: String, content: String, reasoning: String?) =
        messages.checkpoint(id, content, reasoning, MessageStatus.STREAMING.toStorage())

    /**
     * 把一条**失败**的消息原地重置，准备重新请求。
     *
     * 连同 `model` 一起写：重试用的是**当前**选的模型，不是当初失败的那个 ——
     * 用户很可能就是换了模型才来点重试的。
     */
    suspend fun resetForRetry(id: String, model: String?) =
        messages.resetForRetry(id, model, MessageStatus.STREAMING.toStorage())

    suspend fun finishMessage(
        id: String,
        content: String,
        reasoning: String?,
        status: MessageStatus,
        errorMessage: String? = null,
        ttftMs: Long? = null,
        tps: Float? = null,
        thinkingMs: Long? = null,
    ) = messages.finish(
        id = id,
        status = status.toStorage(),
        error = errorMessage,
        ttftMs = ttftMs,
        tps = tps,
        thinkingMs = thinkingMs,
    ).also {
        messages.checkpoint(id, content, reasoning, status.toStorage())
    }

    /**
     * 打开会话前清扫：把上次被中断的流式消息判为 STOPPED。
     *
     * 不做这一步，那条消息会以 STREAMING 状态永久留在库里 ——
     * UI 上不给操作入口，用户既删不掉也切不走。
     */
    suspend fun resolveDanglingStreams(conversationId: String) =
        messages.resolveDanglingStreams(conversationId, MessageStatus.STOPPED.toStorage())

    /**
     * 删除一条消息 —— **连同它的整棵子树**。
     *
     * 下游都是基于它生成的，留着一个"没有前文的下文"没有意义。
     * 删完若父节点下再没有 active 的兄弟，让最后一个兄弟接管，
     * 免得路径断在半途、后面全部不显示。
     */
    suspend fun deleteMessage(messageId: String) {
        val node = messages.findById(messageId) ?: return
        val all = messages.getAllFor(node.conversationId)
        messages.deleteByIds(MessageTree.subtreeIds(all, messageId))

        val remaining = messages.getAllFor(node.conversationId)
        val siblings = remaining.filter { it.parentId == node.parentId }
        if (siblings.isNotEmpty() && siblings.none { it.active }) {
            MessageTree.lastSibling(remaining, node.parentId)?.let {
                messages.activate(it.id, node.conversationId, node.parentId)
            }
        }
    }

    /** 切到某个版本。同一个父亲下只有它会 `active = 1`。 */
    suspend fun activateVariant(messageId: String, conversationId: String, parentId: String) =
        messages.activate(messageId, conversationId, parentId)

    /**
     * 在 [parentId] 下追加一个新版本并立即激活它。
     *
     * 「重新生成」与「编辑」都走这里。旧版本**连同它的子树**原样留着 ——
     * 这就是"分支"：切回去的时候，旧分支的下游也一起回来。
     */
    suspend fun addActiveVariant(
        conversationId: String,
        parentId: String,
        role: MessageRole,
        content: String,
        attachments: List<Attachment> = emptyList(),
        reasoning: String? = null,
        status: MessageStatus = MessageStatus.DONE,
        ttftMs: Long? = null,
        tps: Float? = null,
        thinkingMs: Long? = null,
    ): UiMessage = db.withTransaction {
        val entity = addSibling(
            conversationId = conversationId,
            parentId = parentId,
            role = role,
            content = content,
            attachments = attachments,
            reasoning = reasoning,
            status = status,
        )
        messages.finish(
            id = entity.id,
            status = status.toStorage(),
            error = null,
            ttftMs = ttftMs,
            tps = tps,
            thinkingMs = thinkingMs,
        )
        toUiWithCounts(conversationId, messages.findById(entity.id)!!)
    }

    /** 某个父亲下、指定版本号对应的消息 id（切版本时用）。 */
    suspend fun messageIdAt(
        conversationId: String,
        parentId: String,
        variantIndex: Int,
    ): String? = messages.getAllFor(conversationId)
        .firstOrNull { it.parentId == parentId && it.variantIndex == variantIndex }
        ?.id

    /** 串行读取某个父亲下当前激活的版本。 */
    suspend fun activeMessageAt(conversationId: String, parentId: String): UiMessage? {
        val all = messages.getAllFor(conversationId)
        val node = all.firstOrNull { it.parentId == parentId && it.active } ?: return null
        val count = MessageTree.siblingCounts(all)[parentId] ?: 1
        return node.toUi().copy(variantCount = count)
    }

    // ── 预设 ──────────────────────────────────────────────

    /**
     * 让库里的**内置预设**与代码保持一致（每次启动跑一次，很便宜）。
     *
     * 规则（不额外存"种子版本号"，靠时间戳区分）：
     *
     * - 内置预设**不存在** → 插入
     * - 存在且**没被改过**（`createdAt == updatedAt`）→ 用代码里的最新文案覆盖
     * - 存在但**被改过** → 原样不动（用户的手笔优先）
     * - 已下线的旧内置预设（[retiredPresetIds]）且没被改过 → 删掉
     *
     * 于是开发期"改文案"是顺手的：改代码 → 下次启动自动生效，**不用清应用数据**；
     * 而用户在界面上改过的预设不会被覆盖。
     *
     * ⚠️ `createdAt` 必须由 [upsertPreset] 保留 —— 它是这里唯一的"改过没"判据。
     */
    suspend fun reconcileBuiltInPresets() {
        val now = System.currentTimeMillis()
        val existing = presets.getAll().associateBy { it.id }

        builtInPresets().forEachIndexed { index, preset ->
            val row = existing[preset.id]
            // 用户改过就跳过（updatedAt 被界面那条路径推后了）
            if (row != null && row.updatedAt > row.createdAt) return@forEachIndexed
            presets.upsert(
                PresetEntity(
                    id = preset.id,
                    name = preset.name,
                    content = preset.content,
                    sortOrder = index,
                    createdAt = row?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
        }

        // 上一代的内置预设：没被改过就清掉，免得列表里留着一批用不上的条目
        retiredPresetIds.forEach { id ->
            val row = existing[id] ?: return@forEach
            if (row.updatedAt == row.createdAt) presets.delete(id)
        }
    }

    suspend fun upsertPreset(preset: Preset, order: Int) {
        val now = System.currentTimeMillis()
        // ⚠️ 保留 createdAt —— 它是 reconcileBuiltInPresets 判断"这条被改过没"的依据
        val createdAt = presets.findById(preset.id)?.createdAt ?: now
        presets.upsert(
            PresetEntity(
                id = preset.id,
                name = preset.name,
                content = preset.content,
                sortOrder = order,
                createdAt = createdAt,
                updatedAt = now,
            ),
        )
    }

    suspend fun deletePreset(id: String) = presets.delete(id)

    // ── 压缩摘要 ──────────────────────────────────────────

    suspend fun getCompactions(conversationId: String): List<CompactionEntity> =
        compactionDao.getFor(conversationId)

    suspend fun saveCompaction(compaction: CompactionEntity) = compactionDao.upsert(compaction)

    suspend fun deleteCompaction(id: String) = compactionDao.delete(id)

    // ── 未发送的输入草稿 ──────────────────────────────────

    /**
     * 读回草稿。[key] 是会话 id，还没有会话时是 [DraftEntity.NewConversation]。
     *
     * @return 没有草稿返回 `null`（调用方据此把输入框留空）。
     */
    suspend fun findDraft(key: String): String? = drafts.find(key)?.text

    /** 「新对话」的待用标题。 */
    suspend fun findDraftTitle(key: String): String? = drafts.find(key)?.title

    /**
     * 落草稿。
     *
     * - **空文本删行**（连同待用标题一起没有了才删）；
     * - 超长**截尾**到 [DraftEntity.MaxChars] —— 只影响落库的那一份，界面上照旧。
     */
    suspend fun saveDraft(key: String, text: String) = mutateDraft(key) {
        it.copy(text = draftForStorage(text).orEmpty(), updatedAt = System.currentTimeMillis())
    }

    /** 记下「新对话」的待用标题；空 / 空白即清掉。 */
    suspend fun saveDraftTitle(key: String, title: String?) = mutateDraft(key) {
        it.copy(title = titleForStorage(title), updatedAt = System.currentTimeMillis())
    }

    suspend fun clearDraft(key: String) = drafts.clear(key)

    /**
     * 行内两样东西（草稿正文 + 待用标题）共用一行，所以写入一律走这里 ——
     * 否则 `upsert` 整行覆盖会把另一样悄悄抹掉。两样都空就删行。
     */
    private suspend fun mutateDraft(key: String, transform: (DraftEntity) -> DraftEntity) {
        val current = drafts.find(key) ?: DraftEntity(key, "", System.currentTimeMillis())
        val next = transform(current)
        if (next.text.isEmpty() && next.title == null) {
            drafts.clear(key)
        } else {
            drafts.upsert(next)
        }
    }

    /** 验收用：现存草稿条数。 */
    suspend fun draftCount(): Int = drafts.count()

    // ── 备份：导出 / 导入 / 清空 ──────────────────────────

    /** 删除全部会话。消息 / 预设挂载 / 摘要靠外键级联一并清除。 */
    suspend fun deleteAllConversations() {
        db.withTransaction {
            conversations.deleteEverything()
            drafts.clearEverything()
        }
    }

    suspend fun conversationCount(): Int = conversations.count()

    /** 导出为可读的 JSON 文本。含**所有版本**，不只当前激活的。 */
    suspend fun exportBackup(): String {
        val messageByConversation = messages.getAllRaw().groupBy { it.conversationId }
        val presetByConversation = conversationPresets.getAllRaw()
            .groupBy { it.conversationId }

        val file = BackupFile(
            exportedAt = System.currentTimeMillis(),
            conversations = conversations.getAllRaw().map { row ->
                row.toBackup(
                    messages = messageByConversation[row.id].orEmpty(),
                    presetIds = presetByConversation[row.id]
                        .orEmpty()
                        .sortedBy { it.sortOrder }
                        .map { it.presetId },
                )
            },
            presets = presets.getAll().map { it.toBackup() },
        )
        return backupJson.encodeToString(file)
    }

    /**
     * 导出**单个会话**。
     *
     * 复用完整备份的格式 —— 这样导出的文件仍能被「导入所有数据」读回去，
     * 不必再维护第二套解析逻辑。
     */
    suspend fun exportConversation(conversationId: String): String? {
        val row = conversations.findById(conversationId) ?: return null
        val file = BackupFile(
            exportedAt = System.currentTimeMillis(),
            conversations = listOf(
                row.toBackup(
                    messages = messages.getAllFor(conversationId),
                    presetIds = conversationPresets.getFor(conversationId)
                        .sortedBy { it.sortOrder }
                        .map { it.presetId },
                ),
            ),
            // 单会话导出**不带全局预设** —— 导入方的预设库里未必有这些 id，
            // 带过去只会制造一堆孤儿项
            presets = emptyList(),
        )
        return backupJson.encodeToString(file)
    }

    /**
     * 把当前对话导出为 **Markdown 文稿**。
     *
     * 与 [exportConversation]（JSON 备份）是两回事，**服务的是两种人**：
     *
     * | | JSON 备份 | Markdown 文稿 |
     * |---|---|---|
     * | 给谁看 | 机器 | 人 |
     * | 内容 | 全部分支、全部版本、时间戳、性能数据 | 当前分支当前选中版本的正文 |
     * | 用途 | 导入回来一模一样 | 贴进任何编辑器继续写 |
     *
     * 因此这里**刻意不做**三件事：不带分支版本、不带思考内容、不带性能数据 ——
     * 对着一份稿子，那些都是噪音。
     */
    suspend fun exportConversationMarkdown(conversationId: String): String? {
        val conversation = conversations.findById(conversationId) ?: return null
        val path = loadActivePath(conversationId)
        if (path.isEmpty()) return null

        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))

        return buildString {
            appendLine("# ${conversation.title}")
            appendLine()
            // 头部两行的文案随界面语言（`export_meta` / `export_scope_note`）
            appendLine(
                "> ${conversation.model.ifBlank { Res.get(R.string.export_unknown_model) }} · " +
                    Res.get(R.string.export_meta, path.size, exportedAt),
            )
            appendLine("> ${Res.get(R.string.export_scope_note)}")
            path.forEach { message ->
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("**${Res.get(if (message.role == MessageRole.USER) R.string.export_role_me else R.string.export_role_ai)}**")
                appendLine()
                if (message.content.isNotBlank()) {
                    appendLine(message.content.trim())
                }
                /*
                 * 附件也带出来。
                 *
                 * 图片**只留一行占位**，不内嵌 base64 —— 一张图就是几十万字符的
                 * data URL，插进 .md 里这份文稿就没法读了。需要连图片一起带走的
                 * 场合用 JSON 备份（那是给机器的格式，图片原样在里面）。
                 */
                message.attachments.forEach { attachment ->
                    appendLine()
                    if (attachment.kind == Attachment.Kind.IMAGE) {
                        appendLine(
                            "> ${Res.get(
                                R.string.export_image_attachment,
                                attachment.name,
                                attachment.width,
                                attachment.height,
                            )}",
                        )
                    } else {
                        appendLine("**${Res.get(R.string.export_attachment, attachment.name)}**")
                        appendLine()
                        appendLine("```")
                        appendLine(attachment.data.trimEnd())
                        appendLine("```")
                    }
                }
            }
            appendLine()
            appendLine("---")
        }
    }

    /**
     * 从 JSON 文本导入。
     *
     * - [ImportMode.MERGE]：按 `id` 去重，已存在的一律跳过。多设备汇总时不会覆盖本地改动。
     * - [ImportMode.OVERWRITE]：先清空本机会话再导入，等价于「换机恢复」。
     *
     * 预设**两种模式都不删本地已有项**，只按 id 覆盖 —— 预设是用户长期积累的资产，
     * 不该因为恢复一份旧备份就被清掉。
     */
    suspend fun importBackup(text: String, mode: ImportMode): ImportResult =
        importParsed(backupJson.decodeFromString<BackupFile>(text).also {
            if (it.format != BackupFile.FORMAT) {
                throw AppTextException(R.string.err_not_our_backup, it.format)
            }
        }, mode)

    /**
     * 从**任意**备份文件导入：认得本应用的 JSON，也认得 Chatbox 的 ZIP / JSON。
     *
     * 识别与翻译交给 `ExternalBackup`（它只做"怎么读"），
     * 落库复用下面这段（它只管"怎么写"）—— 两条路径共用一套写入逻辑，
     * 就不会出现"从 A 导入加了新字段、从 B 导入忘了加"这种分叉。
     *
     * @param scratchDir ZIP 形态要落临时文件才能读（见 `ExternalBackup.parseZip`），传 `cacheDir`。
     */
    suspend fun importExternal(
        bytes: ByteArray,
        scratchDir: File,
        mode: ImportMode,
    ): Pair<ImportResult, String> {
        val parsed = ExternalBackup.parse(bytes, scratchDir)
        val result = importParsed(parsed.file, mode)
        val note = buildString {
            append(Res.get(parsed.source.nameRes))
            if (parsed.warnings.isNotEmpty()) {
                append(Res.get(R.string.import_note_separator))
                append(parsed.warnings.joinToString(Res.get(R.string.list_separator)) { it.resolve() })
            }
        }
        return result to note
    }

    /**
     * 从解析好的备份模型落库。
     *
     * - [ImportMode.MERGE]：按 `id` 去重，已存在的一律跳过。多设备汇总时不会覆盖本地改动。
     * - [ImportMode.OVERWRITE]：先清空本机会话再导入，等价于「换机恢复」。
     *
     * 预设**两种模式都不删本地已有项**，只按 id 覆盖 —— 预设是用户长期积累的资产，
     * 不该因为恢复一份旧备份就被清掉。
     */
    suspend fun importParsed(file: BackupFile, mode: ImportMode): ImportResult {
        var skipped = 0
        var importedMessages = 0

        db.withTransaction {
            if (mode == ImportMode.OVERWRITE) {
                conversations.deleteEverything()
                // 会话整表换血，草稿跟着走 —— 留着就是指向已不存在的会话的死数据
                drafts.clearEverything()
            }

            for (conversation in file.conversations) {
                if (mode == ImportMode.MERGE && conversations.findExistingId(conversation.id) != null) {
                    skipped++
                    continue
                }
                conversations.upsert(
                    ConversationEntity(
                        id = conversation.id,
                        title = conversation.title,
                        mode = conversation.mode,
                        provider = conversation.provider,
                        model = conversation.model,
                        systemPrompt = conversation.systemPrompt,
                        createdAt = conversation.createdAt,
                        updatedAt = conversation.updatedAt,
                        pinned = conversation.pinned,
                        pinnedAt = conversation.pinnedAt,
                    ),
                )
                for (message in conversation.messages) {
                    // MERGE 下同一份消息可能已存在；OVERWRITE 下 id 也可能撞，同样跳过
                    if (messages.findExistingId(message.id) != null) continue
                    messages.upsert(message.toEntity(conversation.id))
                    importedMessages++
                }

                // 恢复挂载的预设（顺序有意义：拼接顺序会影响模型对主次的判断）
                writePresetLinks(conversation.id, conversation.enabledPresetIds)
            }

            for (preset in file.presets) {
                presets.upsert(
                    PresetEntity(
                        id = preset.id,
                        name = preset.name,
                        content = preset.content,
                        sortOrder = preset.sortOrder,
                        createdAt = preset.createdAt,
                        updatedAt = preset.updatedAt.takeIf { it > 0L } ?: preset.createdAt,
                    ),
                )
            }
        }

        return ImportResult(
            conversations = file.conversations.size - skipped,
            messages = importedMessages,
            presets = file.presets.size,
            skipped = skipped,
        )
    }

    private companion object {
        /**
         * 内置预设。刻意只做「一段提示词」，不做结构化角色卡（§9.1.1①）。
         *
         * ⚠️ 这里的文案是**会被 reconcileBuiltInPresets 同步到库里**的：改完下次启动生效，
         * 不用清数据；但用户在界面上改过的预设不会被覆盖（见那个函数的规则）。
         *
         * 是**函数**而不是 val：文案随界面语言取（`preset_*`），换语言后
         * 下次启动就会把没改过的内置预设同步成新语言 —— 这正是想要的行为。
         */
        fun builtInPresets(): List<Preset> = listOf(
            Preset(
                id = "novel",
                name = Res.get(R.string.preset_novel_name),
                content = Res.get(R.string.preset_novel_body),
            ),
            Preset(
                id = "essay",
                name = Res.get(R.string.preset_prose_name),
                content = Res.get(R.string.preset_prose_body),
            ),
            Preset(
                id = "poem",
                name = Res.get(R.string.preset_poem_name),
                content = Res.get(R.string.preset_poem_body),
            ),
        )

        /**
         * 上一代内置预设的 id。
         *
         * 没被用户改过的会顺手删掉（免得预设列表里留着一批用不上的条目）；
         * 改过的留着不动 —— 那是用户的东西。
         */
        val retiredPresetIds = listOf("third-person", "slow-pace", "role-a")
    }
}



/** 供 DAO 之外使用：把实体列表转成 UI 模型。 */
fun List<MessageEntity>.toUiMessages(): List<UiMessage> = map { it.toUi() }
