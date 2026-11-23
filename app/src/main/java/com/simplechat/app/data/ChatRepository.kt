package com.simplechat.app.data

import android.content.Context
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import android.util.Base64
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.room.withTransaction
import com.simplechat.app.R
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.db.AttachmentRefEntity
import com.simplechat.app.db.CompactionEntity
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.db.ConversationPresetEntity
import com.simplechat.app.db.UsageTotals
import com.simplechat.app.db.ConversationSearchHit
import com.simplechat.app.db.DraftEntity
import com.simplechat.app.db.MemoryMessage
import com.simplechat.app.db.MemorySession
import com.simplechat.app.db.MessageEntity
import com.simplechat.app.db.MessageTree
import com.simplechat.app.db.PresetEntity
import com.simplechat.app.data.import.DeepSeekHistory
import com.simplechat.app.data.import.DeepSeekShare
import com.simplechat.app.data.import.ExternalBackup
import com.simplechat.app.data.import.ImportSession
import com.simplechat.app.data.import.ImportSessionSource
import com.simplechat.app.data.import.OwnBackupStream
import com.simplechat.app.data.archive.ChatArchive
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.ReplyBlocks
import com.simplechat.app.ui.chat.ConversationMode
import java.io.File
import java.io.Writer
import java.io.StringWriter
import java.util.zip.ZipFile
import com.simplechat.app.ui.chat.MessageRole
import com.simplechat.app.ui.chat.MessageStatus
import com.simplechat.app.ui.chat.Preset
import com.simplechat.app.ui.chat.UiMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
class ChatRepository(private val db: AppDatabase, private val context: Context) {

    private val conversations = db.conversationDao()
    private val messages = db.messageDao()
    private val presets = db.presetDao()
    private val conversationPresets = db.conversationPresetDao()
    private val compactionDao = db.compactionDao()
    private val drafts = db.draftDao()
    private val attachmentRefs = db.attachmentRefDao()
    private val compactionCheckpoints = CompactionCheckpointStore(File(context.noBackupFilesDir, "compaction"))

    // ── 会话列表 ──────────────────────────────────────────

    fun observeConversations(): Flow<List<ConversationEntity>> = conversations.observeAll()

    internal suspend fun memorySessions(currentId: String, limit: Int, titleChars: Int): List<MemorySession> =
        conversations.memorySessions(currentId, limit, titleChars)

    internal suspend fun memoryMessages(
        conversationId: String, maxDepth: Int, maxMessages: Int, maxChars: Int,
    ): List<MemoryMessage> = messages.memoryMessages(conversationId, maxDepth, maxMessages, maxChars)

    /**
     * 观察**单个会话**。
     *
     * 存在的理由只有一个：让「标题」有唯一真相。
     * 顶栏、抽屉、对话设置里的名称框都从这一列读，谁改了都从库流回来。
     */
    fun observeConversation(id: String): Flow<ConversationEntity?> = conversations.observeById(id)

    /** Full-text search across titles and message bodies, optionally including inactive branches. */
    fun searchConversations(
        query: String,
        limit: Int = Int.MAX_VALUE,
        includeInactiveBranches: Boolean = false,
        includeReasoning: Boolean = false,
    ): Flow<List<ConversationSearchHit>> =
        conversations.search(query.trim(), includeInactiveBranches, limit, includeReasoning)

    fun observePresets(): Flow<List<Preset>> = presets.observeAll().map { list -> list.map { it.toUi() } }

    suspend fun createConversation(
        mode: ConversationMode,
        provider: String,
        model: String,
        systemPrompt: String? = null,
        enabledPresetIds: List<String> = emptyList(),
        title: String = Res.get(R.string.conversation_default_title),
        memoryEnabled: Boolean = true,
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
                memoryEnabled = memoryEnabled,
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

    internal fun observeConversationWallpaper(id: String): Flow<ConversationWallpaper?> =
        conversations.observeWallpaper(id).distinctUntilChanged().map(ConversationWallpaper::decode)

    internal suspend fun saveConversationWallpaper(id: String, wallpaper: ConversationWallpaper?) {
        if (conversations.setWallpaper(id, wallpaper?.encode()) != 1) {
            throw java.io.IOException("Conversation is no longer available")
        }
    }

    suspend fun findConversation(id: String): ConversationEntity? = conversations.findById(id)

    internal suspend fun setConversationMemoryEnabled(id: String, enabled: Boolean) =
        conversations.setMemoryEnabled(id, enabled)

    suspend fun revealSearchMessage(conversationId: String, messageId: String) = db.withTransaction {
        val path = MessageTree.ancestry(MessageTree.navigable(messages.getLightFor(conversationId)), messageId)
        check(path.lastOrNull()?.let { it.id == messageId && it.visible } == true) { "Search message is no longer available" }
        path.forEach { messages.activate(it.id, conversationId, it.parentId) }
    }

    suspend fun renameConversation(id: String, title: String) =
        conversations.rename(
            id,
            title.trim().ifBlank { Res.get(R.string.conversation_default_title) },
            System.currentTimeMillis(),
        )

    suspend fun setPinned(id: String, pinned: Boolean) =
        conversations.setPinned(id, pinned, if (pinned) System.currentTimeMillis() else null)

    /**
     * 删除会话 = **移入回收站**（软删除）。
     *
     * 与 [deleteConversations] 的区别：不碰消息 / 附件 / 引用表 —— 恢复时要原样回来。
     * 真删交给 [purgeExpiredTrash]（15 天）或回收站里的「彻底删除」。
     */
    suspend fun trashConversations(ids: List<String>) {
        if (ids.isEmpty()) return
        db.withTransaction { conversations.moveToTrash(ids, System.currentTimeMillis()) }
    }

    /** 从回收站恢复（清掉 deletedAt）。 */
    suspend fun restoreConversations(ids: List<String>) {
        if (ids.isEmpty()) return
        db.withTransaction { conversations.restoreFromTrash(ids) }
    }

    /** 回收站列表（Flow）。 */
    fun observeTrash(): Flow<List<ConversationEntity>> = conversations.observeTrash()

    /**
     * 回收站超过 [TRASH_RETENTION_DAYS] 天的会话真删，连同附件与草稿。
     *
     * 在 App 启动时跑一次即可：15 天粒度不需要后台定时器，
     * 而 WorkManager 为一个纯本地清理引入一整个依赖也不值。
     *
     * @return 被清理的会话数
     */
    suspend fun purgeExpiredTrash(now: Long = System.currentTimeMillis()): Int {
        val threshold = now - TRASH_RETENTION_DAYS * 24L * 60 * 60 * 1000
        val expired = runCatching { conversations.expiredTrashIds(threshold) }.getOrDefault(emptyList())
        if (expired.isEmpty()) return 0
        // 复用真删逻辑：附件引用、草稿、压缩检查点一次清干净
        deleteConversations(expired)
        return expired.size
    }

    suspend fun deleteConversations(ids: List<String>) {
        /*
         * 落盘图片的路径从**引用表**查，不读 `messages.attachments` ——
         * 那一列是几兆的大 JSON，读它必然撞 `CursorWindow` 的 2MB 上限
         * （实测「删除所有历史对话」就是这么失败的：
         * `Row too big to fit into CursorWindow requiredPos=954`）。
         */
        val files = ids.flatMap { attachmentRefs.pathsOf(it) }
        val wallpapers = db.withTransaction {
            val removed = ids.chunked(500).flatMap { conversations.wallpaperOverrides(it) }.mapNotNull(ConversationWallpaper::decode)
            conversations.deleteAll(ids)
            // 会话没了草稿就没人读 —— 同步清掉，别留死数据
            drafts.clearAll(ids)
            attachmentRefs.clearAll(ids)
            removed
        }
        withContext(Dispatchers.IO) { wallpapers.forEach { WallpaperFiles.deleteWallpaper(context, it.fileName) } }
        files.distinct().forEach { path ->
            if (!attachmentRefs.containsPath(path) && !messages.referencesAttachment(path)) AttachmentFiles.delete(context, path)
        }
        ids.forEach { deleteCompactionCheckpoint(it) }
    }

    /**
     * 写库前把**内联**的图片字节搬到磁盘。
     *
     * 备份里图片是 base64 内联的（格式不变），直接写进库又会回到"一行几兆、
     * 读不出来"的老问题。所有导入路径（自家备份 / Chatbox / DeepSeek）都经过
     * [writeStreamedSession]，所以在这里统一落盘，等于给整条链路上了保险。
     */
    private fun MessageEntity.withOffloadedImages(createdPaths: MutableSet<String>? = null): MessageEntity {
        val decoded = Attachments.decode(attachments)
        if (decoded.none { it.isImage && !it.isStored }) return this
        val offloaded = decoded.map { attachment ->
            if (!attachment.isImage || attachment.isStored) return@map attachment
            val bytes = runCatching { Base64.decode(attachment.data, Base64.NO_WRAP) }.getOrNull()
                ?: return@map attachment
            val stored = AttachmentFiles.save(context, bytes.inputStream(), attachment.mime)
            createdPaths?.add(stored)
            attachment.copy(data = "", path = stored)
        }
        return copy(attachments = Attachments.encode(offloaded))
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
        val source = messages.exportHeader(fromMessageId) ?: return@withTransaction null
        val origin = conversations.findById(source.conversationId) ?: return@withTransaction null

        val path = MessageTree.pathTo(loadMessagesSafely(source.conversationId), fromMessageId)
            .filter { it.trashedAt == null && !it.isDeletedNode }
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
                memoryEnabled = origin.memoryEnabled,
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
        val sharedPaths = path.flatMap { Attachments.decode(it.attachments).mapNotNull { attachment -> attachment.path } }.distinct()
        attachmentRefs.upsertAll(sharedPaths.map { AttachmentRefEntity(it, newId) })
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
        // 与导入的"同名新命名"共用一套编号规则（`uniqueCopyTitle`，纯函数有单测）
        return uniqueCopyTitle(
            title = base,
            taken = conversations.allTitles().toHashSet(),
            suffix = Res.get(R.string.conversation_fork_suffix),
            suffixN = { n -> Res.get(R.string.conversation_fork_suffix_n, n) },
        )
    }

    // ── 消息（树）─────────────────────────────────────────
    //
    // 一切以 `parentId` 为准，不再有"第几位"。当前对话 =
    // `MessageTree.activePath(all)` 走出来的那条路径。

    /**
     * 当前对话：从根沿 active 走到底。
     *
     * ### 为什么要先读"骨架"
     *
     * 打开长会话最贵的不是"走树"，而是**把用不到的消息正文读出来**：
     * 每「重新生成」一次，树上就多一条被放弃的回复，而界面只显示其中一条；
     * `SELECT *` 会把这些分支的正文（还带 `attachments` / `timeline` 两段 JSON）
     * 全部读进内存。几十条消息看不出差别，几千条的会话就是几十兆 ——
     * 真机上就是"点进去转很久，甚至闪退"。
     *
     * 现在分两步：先只读骨架（每行几十字节）走出活跃路径，再按 id 只取这条路径的正文。
     * 被放弃的分支因此**一次都不会被读出来**，内存与 IO 都只与"当前分支有多大"成正比。
     */
    suspend fun loadActivePath(conversationId: String): List<UiMessage> {
        val light = runCatching { messages.getLightFor(conversationId) }
            .getOrElse { error ->
                // 骨架查询只读几列、不可能撞 CursorWindow；真出别的错就退回整读
                if (error is SQLiteBlobTooBigException) return loadActivePathFull(conversationId)
                throw error
            }
        if (light.isEmpty()) return emptyList()
        return projectActivePath(light, ::loadMessagesByIds)
    }

    private suspend fun projectActivePath(
        light: List<com.simplechat.app.db.MessageLight>,
        load: suspend (List<String>) -> List<MessageEntity>,
    ): List<UiMessage> {
        val navigable = MessageTree.navigable(light)
        val pathIds = MessageTree.activePathIds(navigable)
        if (pathIds.isEmpty()) return emptyList()
        val skeleton = navigable.associateBy { it.id }
        val siblings = navigable.groupBy { it.parentId }.mapValues { (_, group) -> group.sortedBy { it.variantIndex }.map { it.id } }
        /*
         * 映射放到后台线程。
         *
         * `toUi()` 要解 timeline / attachments 两段 JSON 并构造字符串，几百条消息
         * 累计是几十毫秒级的活 —— 在调用方（主线程）上做，正好卡在"侧边栏收起来"
         * 那段动画里，看起来就是动画一卡、然后内容才出现。
         */
        return withContext(Dispatchers.Default) {
            val byId = load(pathIds.filter { skeleton.getValue(it).visible }).associateBy { it.id }
            pathIds.mapNotNull { id ->
                val row = skeleton.getValue(id)
                val options = siblings.getValue(row.parentId)
                val entity = byId[id]
                if (entity != null) entity.toUiWithPosition(options)
                else if (!row.visible) UiMessage(
                    id = row.id, parentId = row.parentId,
                    role = if (row.role == "user") MessageRole.USER else MessageRole.ASSISTANT,
                    content = "", variantIndex = options.indexOf(id), variantCount = options.size,
                    deletedNavigation = true,
                ) else null
            }
        }
    }

    /** 活跃路径的正文：按 id 分批取（SQLite 的绑定变量数有上限）。 */
    private suspend fun loadMessagesByIds(ids: List<String>): List<MessageEntity> = try {
        ids.chunked(MessageQueryChunk).flatMap { chunk -> messages.getByIds(chunk) }
    } catch (error: SQLiteBlobTooBigException) {
        // Oversized text and legacy inline images are restored in byte slices, without truncation.
        ids.map { id -> readMessageForExport(id) }
    }

    private suspend fun loadActivePathFull(conversationId: String): List<UiMessage> {
        val all = loadMessagesSafely(conversationId)
        val byId = all.associateBy { it.id }
        val light = all.map { row ->
            com.simplechat.app.db.MessageLight(row.id, row.parentId, row.variantIndex, row.active,
                row.role, row.status, row.createdAt, row.versionGroup, row.trashedAt, row.trashOperationId, row.isDeletedNode)
        }
        return projectActivePath(light) { ids -> ids.mapNotNull(byId::get) }
    }

    /**
     * 读一个会话的全部消息，**带超大行的兜底**。
     *
     * Android 的 `CursorWindow` 是 2MB：某一条消息的附件列（图片 base64）超过它，
     * 整条 SQL 就抛 `SQLiteBlobTooBigException`，那个会话从列表点进去直接闪退 ——
     * 一次导入几万条历史之后，这种行必然存在。
     *
     * 整行读不了时按字节切片恢复全部字段；不截断正文或附件。
     */
    private suspend fun loadMessagesSafely(conversationId: String): List<MessageEntity> =
        runCatching { messages.getAllFor(conversationId) }
            .getOrElse { error ->
                if (error !is SQLiteBlobTooBigException) throw error
                messages.idsFor(conversationId).map { readMessageForExport(it) }
            }

    /**
     * 路径末尾节点 id；空会话返回 [MessageEntity.ROOT_PARENT]。
     *
     * 只读骨架：每次追加消息都会调用它，而它要的只是"最后一条是谁"。
     */
    suspend fun tailId(conversationId: String): String =
        MessageTree.activePathIds(MessageTree.navigable(messages.getLightFor(conversationId))).lastOrNull()
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
        imageGeneration: Boolean = false,
        imageRequest: ImageRequest? = null,
        versionGroup: String? = null,
    ): MessageEntity {
        val id = UUID.randomUUID().toString()
        val entity = newMessageEntity(
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
            imageGeneration = imageGeneration,
            imageRequest = imageRequest,
        ).copy(versionGroup = versionGroup).withOffloadedImages()
        messages.upsert(entity)
        messages.activate(id, conversationId, parentId)
        val refs = Attachments.decode(entity.attachments).mapNotNull { it.path }.distinct()
            .map { AttachmentRefEntity(path = it, conversationId = conversationId) }
        if (refs.isNotEmpty()) attachmentRefs.upsertAll(refs)
        // The inserted entity is already available. Reading it back can exceed CursorWindow
        // for a large prompt or several attachments, before the network request even starts.
        return entity
    }

    /** The stored order can have gaps after deletions or imports. UI arrows use positions. */
    private fun MessageEntity.toUiWithPosition(siblingIds: List<String>): UiMessage =
        toUi().copy(variantIndex = siblingIds.indexOf(id).coerceAtLeast(0), variantCount = siblingIds.size.coerceAtLeast(1))

    private suspend fun toUiWithCounts(conversationId: String, entity: MessageEntity): UiMessage =
        entity.toUiWithPosition(MessageTree.navigable(messages.getLightFor(conversationId))
            .filter { it.parentId == entity.parentId }.sortedBy { it.variantIndex }.map { it.id })

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
        imageGeneration: Boolean = false,
        imageRequest: ImageRequest? = null,
    ): String =
        addSibling(
            conversationId = conversationId,
            parentId = parentId,
            role = MessageRole.ASSISTANT,
            content = "",
            reasoning = "",
            status = MessageStatus.STREAMING,
            model = model,
            imageGeneration = imageGeneration,
            imageRequest = imageRequest,
        ).id

    /** 流式 checkpoint。调用频率由 ViewModel 控制在 ~1.5s（§4.4）。 */
    suspend fun checkpoint(
        id: String,
        content: String,
        reasoning: String?,
        timeline: List<ReplyBlock> = emptyList(),
    ) = messages.checkpoint(
        id = id,
        content = content,
        reasoning = reasoning,
        status = MessageStatus.STREAMING.toStorage(),
        timeline = ReplyBlocks.encode(timeline),
    )

    /**
     * 把一条**失败**的消息原地重置，准备重新请求。
     *
     * 连同 `model` 一起写：重试用的是**当前**选的模型，不是当初失败的那个 ——
     * 用户很可能就是换了模型才来点重试的。
     */
    suspend fun resetForRetry(id: String, model: String?) =
        messages.resetForRetry(id, model, MessageStatus.STREAMING.toStorage())

    /**
     * 续写：原地转回流式，**内容保留**。
     *
     * 与 [resetForRetry] 相对：那个是"这次请求重来"，这个是"接着上一次写"。
     */
    suspend fun resumeStreaming(id: String) =
        messages.resumeStreaming(id, MessageStatus.STREAMING.toStorage())

    suspend fun finishMessage(
        id: String,
        content: String,
        reasoning: String?,
        status: MessageStatus,
        errorMessage: String? = null,
        ttftMs: Long? = null,
        tps: Float? = null,
        thinkingMs: Long? = null,
        timeline: List<ReplyBlock> = emptyList(),
        stopReason: String? = null,
        /** 服务端回报的用量；没报就留 null，不要填 0（0 会被当成真实值参与计价）。 */
        tokensIn: Int? = null,
        tokensOut: Int? = null,
        cacheHitTokens: Int? = null,
        directImageRequest: ImageRequest? = null,
    ) = messages.finish(
        id = id,
        content = content,
        reasoning = reasoning,
        status = status.toStorage(),
        error = errorMessage,
        ttftMs = ttftMs,
        tps = tps,
        thinkingMs = thinkingMs,
        timeline = ReplyBlocks.encode(timeline),
        stopReason = stopReason,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        cacheHitTokens = cacheHitTokens,
        directImageRequest = directImageRequest?.encode(),
    )

    internal suspend fun attachGeneratedImage(messageId: String, bytes: ByteArray, markDone: Boolean = false): Attachment =
        withContext(Dispatchers.IO) {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val mime = bounds.outMimeType
            require(mime in setOf("image/png", "image/jpeg", "image/webp") && bounds.outWidth > 0 &&
                bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 25_000_000) {
                "Invalid or oversized generated image"
            }
            val sample = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1)
            }
            val preview = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sample)
                ?: throw IllegalArgumentException("Generated image cannot be decoded")
            preview.recycle()
            currentCoroutineContext().ensureActive()
            val extension = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
            val path = AttachmentFiles.save(context, bytes.inputStream(), mime.orEmpty())
            val attachment = Attachment(
                kind = Attachment.Kind.IMAGE,
                name = "generated-${UUID.randomUUID().toString().take(8)}.${extension}",
                mime = mime.orEmpty(),
                data = "",
                width = bounds.outWidth,
                height = bounds.outHeight,
                path = path,
            )
            try {
                db.withTransaction {
                    val conversationId = messages.conversationIdOf(messageId)
                        ?: throw IllegalStateException("Generated image message no longer exists")
                    check(messages.attachGeneratedImage(messageId, Attachments.encode(listOf(attachment)),
                        if (markDone) "DONE" else "STREAMING") == 1) { "Image request is no longer active" }
                    attachmentRefs.upsertAll(listOf(AttachmentRefEntity(path = path, conversationId = conversationId)))
                }
            } catch (error: Throwable) {
                AttachmentFiles.delete(context, path)
                throw error
            }
            attachment
        }

    internal suspend fun settleImageGeneration(id: String, status: MessageStatus, error: String? = null): Boolean =
        messages.settleImageGeneration(id, status.toStorage(), error) == 1

    suspend fun markImageGeneration(messageId: String, request: ImageRequest) =
        messages.markImageGeneration(messageId, request.encode())

    /** 全库累计用量（设置 → 用量与余额）。 */
    suspend fun usageTotals(): UsageTotals = messages.usageTotals()

    suspend fun dailyUsage(since: Long) = messages.dailyUsage(since)

    /**
     * 打开会话前清扫：把上次被中断的流式消息判为 STOPPED。
     *
     * 不做这一步，那条消息会以 STREAMING 状态永久留在库里 ——
     * UI 上不给操作入口，用户既删不掉也切不走。
     */
    suspend fun resolveDanglingStreams(conversationId: String) =
        messages.resolveDanglingStreams(conversationId, MessageStatus.STOPPED.toStorage())

    /** Remove every selected message version while preserving all downstream branches. */
    fun observeMessageTrash() = messages.observeMessageTrash()

    suspend fun deleteMessageVersions(conversationId: String, messageIds: Set<String>) {
        if (messageIds.isEmpty()) return
        db.withTransaction {
            check(conversations.findById(conversationId)?.let { it.deletedAt == null } == true)
            val tree = messages.getLightFor(conversationId)
            val groups = messageIds.map { id ->
                checkNotNull(tree.firstOrNull { it.id == id && it.visible }).groupKey
            }.toSet()
            val targets = tree.filter { it.visible && it.groupKey in groups }
            val ids = targets.mapTo(HashSet()) { it.id }
            val now = System.currentTimeMillis()
            val operation = java.util.UUID.randomUUID().toString()
            compactionDao.getFor(conversationId).filter { summary ->
                MessageTree.ancestry(tree, summary.throughMessageId).any { it.id in ids }
            }.forEach { compactionDao.delete(it.id) }
            ids.toList().chunked(MessageQueryChunk).forEach { messages.setMessageTrash(it, now, operation) }
            repairMessageNavigation(conversationId, tree.map { if (it.id in ids) it.copy(trashedAt = now) else it })
        }
        deleteCompactionCheckpoint(conversationId)
    }

    suspend fun changeMessageTrash(entries: List<com.simplechat.app.db.TrashedMessage>, restore: Boolean) {
        val releasedPaths = db.withTransaction {
            val released = HashSet<String>()
            entries.groupBy { it.conversationId }.forEach { (conversationId, selected) ->
                if (conversations.findById(conversationId)?.let { it.deletedAt == null } != true) return@forEach
                val keys = selected.map { it.operationId to it.groupKey }.toSet()
                val tree = messages.getLightFor(conversationId)
                val ids = tree.filter { it.trashedAt != null && (it.trashOperationId to it.groupKey) in keys }
                    .mapTo(HashSet()) { it.id }
                if (ids.isEmpty()) return@forEach
                if (restore) {
                    ids.toList().chunked(MessageQueryChunk).forEach { messages.setMessageTrash(it, null, null) }
                } else {
                    ids.toList().chunked(MessageQueryChunk).forEach { messages.scrubMessageNodes(it) }
                    attachmentRefs.pathsOf(conversationId).forEach { path ->
                        attachmentRefs.removeIfUnused(conversationId, path)
                        released += path
                    }
                }
                val updated = tree.map { row ->
                    if (row.id in ids) row.copy(trashedAt = null, trashOperationId = null, isDeletedNode = !restore) else row
                }
                val keep = MessageTree.reachable(updated, includeTrash = true)
                updated.filter { it.isDeletedNode && it.id !in keep }.map { it.id }
                    .chunked(MessageQueryChunk).forEach { messages.deleteByIds(it) }
                repairMessageNavigation(conversationId, updated.filter { !it.isDeletedNode || it.id in keep })
            }
            released
        }
        withContext(Dispatchers.IO) {
            for (path in releasedPaths) {
                if (!attachmentRefs.containsPath(path) && !messages.referencesAttachment(path)) AttachmentFiles.delete(context, path)
            }
        }
    }

    private suspend fun repairMessageNavigation(conversationId: String, tree: List<com.simplechat.app.db.MessageLight>) {
        val reachable = MessageTree.reachable(tree)
        for ((parent, children) in tree.groupBy { it.parentId }) {
            val active = children.filter { it.active }
            if (active.size == 1 && active.single().id in reachable) continue
            val options = children.filter { it.id in reachable }.sortedBy { it.variantIndex }
            if (options.isEmpty()) continue
            val oldIndex = active.firstOrNull()?.variantIndex
            val replacement = if (oldIndex == null) options.first()
                else options.firstOrNull { it.variantIndex >= oldIndex } ?: options.last()
            messages.activate(replacement.id, conversationId, parent)
        }
    }

    suspend fun activateVariantAt(conversationId: String, parentId: String, position: Int): String? = db.withTransaction {
        val tree = MessageTree.navigable(messages.getLightFor(conversationId))
        val id = tree.filter { it.parentId == parentId }.sortedBy { it.variantIndex }
            .getOrNull(position)?.id ?: return@withTransaction null
        messages.activate(id, conversationId, parentId)
        id
    }

    /**
     * 在 [parentId] 下追加一个新版本并立即激活它。
     *
     * 「重新生成」与「编辑后重发」都走这里。旧版本**连同它的子树**原样留着 ——
     * 这就是"分支"：切回去的时候，旧分支的下游也一起回来。
     *
     * ⚠️ 单纯的「保存」**不走这里**（那是原地改文字，见 [editMessageInPlace]）：
     * 只改个错别字不该长出一条新分支，更不该让会话下半段当场消失。
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
        versionOf: String? = null,
    ): UiMessage = db.withTransaction {
        val entity = addSibling(
            conversationId = conversationId,
            parentId = parentId,
            role = role,
            content = content,
            attachments = attachments,
            reasoning = reasoning,
            status = status,
            versionGroup = versionOf?.let { id ->
                messages.exportHeader(id)?.let { it.versionGroup ?: it.parentId }
            },
        )
        messages.finish(
            id = entity.id,
            content = entity.content,
            reasoning = entity.reasoning,
            status = status.toStorage(),
            error = null,
            ttftMs = ttftMs,
            tps = tps,
            thinkingMs = thinkingMs,
            timeline = null,
            stopReason = null,
            tokensIn = null,
            tokensOut = null,
            cacheHitTokens = null,
        )
        toUiWithCounts(conversationId, entity.copy(ttftMs = ttftMs, tps = tps, thinkingMs = thinkingMs))
    }

    /**
     * 「保存」编辑后的回复：**原地**改文字，不新增版本。
     *
     * 与 [addActiveVariant] 是两种语义，不能混：
     * - 这里 = 用户只是在改这条（错别字、语气、删掉一段），旧文本不需要留着；
     * - 那边 = 换一份新的回答（重新生成 / 编辑后继续生成），旧的那份要能切回去。
     *
     * 原地改还有一个必须做对的副作用：`timeline` 里的正文块要一起换，否则
     * 扩展通道的界面与请求都还在用旧文本（见 `rewriteTimelineText`）。
     * 传 `timeline = null` 表示这条本来就没有内容块（OpenAI 通道 / 老数据）。
     */
    suspend fun editMessageInPlace(
        id: String,
        content: String,
        attachments: List<Attachment> = emptyList(),
        timeline: List<ReplyBlock> = emptyList(),
    ) = db.withTransaction {
        messages.editContent(
            id = id,
            content = content,
            attachments = Attachments.encode(attachments),
            timeline = ReplyBlocks.encode(timeline),
        )
        // 落盘附件的引用登记：改了附件也要保证删会话时能一起清干净
        val conversationId = messages.exportHeader(id)?.conversationId
        val refs = attachments.mapNotNull { it.path }.distinct()
        if (conversationId != null && refs.isNotEmpty()) {
            attachmentRefs.upsertAll(refs.map { AttachmentRefEntity(path = it, conversationId = conversationId) })
        }
    }

    /** 串行读取某个父亲下当前激活的版本。 */
    suspend fun activeMessageAt(conversationId: String, parentId: String): UiMessage? {
        // 同样走兜底：这条路径也在"点开会话"的链路上，超大行一样会崩
        val all = loadMessagesSafely(conversationId)
        val node = all.firstOrNull { it.parentId == parentId && it.active } ?: return null
        return toUiWithCounts(conversationId, node)
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

    internal suspend fun loadCompactionCheckpoint(id: String, fingerprint: String): CompactionCheckpoint? =
        withContext(Dispatchers.IO) { compactionCheckpoints.load(id, fingerprint) }

    internal suspend fun saveCompactionCheckpoint(id: String, state: CompactionCheckpoint) = withContext(Dispatchers.IO) {
        // A full disk must not invalidate a completed API response or the active summary.
        try {
            compactionCheckpoints.save(id, state)
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            android.util.Log.w("SimpleChat/Compaction", "Checkpoint could not be saved (${error.javaClass.simpleName})")
        }
    }

    internal suspend fun deleteCompactionCheckpoint(id: String) = withContext(Dispatchers.IO) {
        compactionCheckpoints.delete(id)
    }

    // ── 未发送的输入草稿 ──────────────────────────────────

    /**
     * 读回草稿。[key] 是会话 id，还没有会话时是 [DraftEntity.NewConversation]。
     *
     * @return 没有草稿返回 `null`（调用方据此把输入框留空）。
     */
    suspend fun findDraft(key: String): String? = drafts.find(key)?.text

    /** 「新对话」的待用标题。 */
    suspend fun findDraftTitle(key: String): String? = drafts.find(key)?.title

    internal suspend fun findNewConversationMemoryEnabled(): Boolean =
        drafts.find(DraftEntity.NewConversation)?.memoryEnabled ?: true

    internal suspend fun saveNewConversationMemoryEnabled(enabled: Boolean) = mutateDraft(DraftEntity.NewConversation) {
        it.copy(memoryEnabled = enabled, updatedAt = System.currentTimeMillis())
    }

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
    private suspend fun mutateDraft(key: String, transform: (DraftEntity) -> DraftEntity) = db.withTransaction {
        val current = drafts.find(key) ?: DraftEntity(key, "", System.currentTimeMillis())
        val next = transform(current)
        if (next.text.isEmpty() && next.title == null && next.memoryEnabled) {
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
        // 全清也要把落盘图片一起删干净：路径同样只查引用表，不碰 messages 的大列
        val files = attachmentRefs.allPaths()
        val wallpapers = db.withTransaction {
            val removed = conversations.allWallpaperOverrides().mapNotNull(ConversationWallpaper::decode)
            conversations.deleteEverything()
            drafts.clearEverything()
            attachmentRefs.clearEverything()
            removed
        }
        withContext(Dispatchers.IO) { wallpapers.forEach { WallpaperFiles.deleteWallpaper(context, it.fileName) } }
        files.forEach { AttachmentFiles.delete(context, it) }
        withContext(Dispatchers.IO) { compactionCheckpoints.clear() }
        withContext(Dispatchers.IO) { File(context.filesDir, "file-api-cache").deleteRecursively() }
    }

    suspend fun conversationCount(): Int = conversations.count()

    /** 导出为可读的 JSON 文本。含**所有版本**，不只当前激活的。 */
    suspend fun exportBackup(): String = backupJson.encodeToString(fullBackup())

    /** File-backed JSON export uses the same bounded message pipeline as ZIP. */
    suspend fun exportBackup(file: File, onProgress: (Float) -> Unit = {}, onlyConversation: String? = null) = withContext(Dispatchers.IO) {
        db.withTransaction {
            onProgress(0f)
            val rows = if (onlyConversation == null) conversations.getAllRaw()
                else listOfNotNull(conversations.findById(onlyConversation))
            check(onlyConversation == null || rows.isNotEmpty()) { "Conversation no longer exists" }
            val exportIds = rows.associate { it.id to exportableIds(it.id) }
            val total = exportIds.values.sumOf { it.size }
            val headers = rows.map { it.toBackup(emptyList(), getEnabledPresetIds(it.id)) }
            val linkedPresetIds = headers.flatMap { it.enabledPresetIds }.toSet()
            val presetRows = presets.getAll().filter { onlyConversation == null || it.id in linkedPresetIds }.map { it.toBackup() }
            var written = 0
            file.outputStream().buffered().writer(Charsets.UTF_8).use { writer ->
                writeBackupStream(writer, System.currentTimeMillis(), headers, presetRows,
                    attachmentSource = { AttachmentFiles.open(context, it) }) { id, sink ->
                    for (messageId in exportIds.getValue(id)) {
                        currentCoroutineContext().ensureActive()
                        val message = readMessageForExport(messageId).toBackup()
                        sink(message)
                        written++
                        onProgress(0.99f * written / total.coerceAtLeast(1))
                    }
                }
            }
            check(written == total) { "Export count mismatch" }
            onProgress(1f)
        }
    }

    suspend fun exportMarkdown(file: File, conversationId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val row = checkNotNull(conversations.findById(conversationId)) { "Conversation no longer exists" }
            val ids = visiblePathIds(conversationId)
            file.writer(Charsets.UTF_8).buffered().use { writeConversationMarkdown(row, ids, it) }
        }
    }

    suspend fun exportDeepSeekArchive(file: File, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val rows = conversations.getAllRaw()
            val headers = rows.map { it.toBackup(emptyList(), emptyList()) }
            val sessions = headers.map { com.simplechat.app.data.archive.DeepSeekArchive.Session(it, messages.getLightFor(it.id)) }
            val total = sessions.sumOf { it.tree.size }.coerceAtLeast(1)
            var written = 0
            file.outputStream().buffered().use { output ->
                com.simplechat.app.data.archive.DeepSeekArchive.writeTo(output, sessions,
                    attachmentSource = { AttachmentFiles.open(context, it) },
                    readMessage = { id ->
                        currentCoroutineContext().ensureActive()
                        readMessageForExport(id).toBackup()
                    },
                    onMessage = { onProgress(0.95f * ++written / total) },
                    storedImageSize = { AttachmentFiles.storedSize(context, it) },
                )
            }
            onProgress(1f)
        }
    }

    /**
     * 导出为 **ZIP 归档**：`manifest.json` + `backup.json` + 每个会话的 Markdown 文稿。
     *
     * 与 [exportBackup] 是**同一份数据**的两种形态 —— 消息、附件、预设、分支一个不少，
     * 只是多了压缩外壳与可读文稿。图省事少写一套模型的收益在于：
     * 以后加字段只改一处，不会出现"JSON 备份有、ZIP 备份没有"这种分叉。
     */
    suspend fun exportArchive(file: File, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        // One database snapshot: concurrent sends cannot move the export's count or branch halfway through.
        db.withTransaction {
            onProgress(0f)
            val rows = conversations.getAllRaw()
            val exportedAt = System.currentTimeMillis()
            val presetRows = presets.getAll().map { it.toBackup() }
            val headers = rows.map { row -> row.toBackup(emptyList(), getEnabledPresetIds(row.id)) }
            val exportIds = rows.associate { it.id to exportableIds(it.id) }
            val totalMessages = exportIds.values.sumOf { it.size }
            var written = 0
            var documentsWritten = 0
            val documents = rows.mapIndexed { index, row ->
                ChatArchive.StreamDocument(ChatArchive.documentName(index + 1, row.title)) { output ->
                    val ids = visiblePathIds(row.id)
                    writeConversationMarkdown(row, ids, output.writer(Charsets.UTF_8).buffered())
                    documentsWritten++
                    onProgress(0.8f + 0.2f * documentsWritten / rows.size.coerceAtLeast(1))
                }
            }
            file.outputStream().buffered().use { output ->
                ChatArchive.writeTo(
                    output = output,
                    exportedAt = exportedAt,
                    conversationCount = rows.size,
                    messageCount = totalMessages,
                    presetCount = presetRows.size,
                    documents = documents,
                ) { backupOutput ->
                    writeBackupStream(backupOutput.writer(Charsets.UTF_8).buffered(), exportedAt, headers, presetRows,
                        attachmentSource = { AttachmentFiles.open(context, it) }) { id, sink ->
                        for (messageId in exportIds.getValue(id)) {
                            currentCoroutineContext().ensureActive()
                            val message = readMessageForExport(messageId).toBackup()
                            // Materialize only this message's images; never retain all images in the archive.
                            sink(message)
                            written++
                            onProgress(0.8f * written / totalMessages.coerceAtLeast(1))
                        }
                    }
                }
            }
            check(written == totalMessages) { "Export message count changed: $written/$totalMessages" }
            onProgress(1f)
        }
    }

    private suspend fun readMessageForExport(id: String): MessageEntity {
        val header = checkNotNull(messages.exportHeader(id)) { "Missing export message: $id" }
        if (header.trashedAt != null || header.isDeletedNode) return header.asExportStructure()
        return restoreExportMessage(header, ExportTextChunkBytes) { start ->
            checkNotNull(messages.exportTextChunk(id, start, ExportTextChunkBytes)) { "Missing export message: $id" }
        }
    }

    private fun MessageEntity.asExportStructure(): MessageEntity = MessageEntity(
        id = id, conversationId = conversationId, parentId = parentId, variantIndex = variantIndex,
        active = active, role = role, content = "", createdAt = createdAt, versionGroup = versionGroup,
        isDeletedNode = true,
    )

    private suspend fun exportableIds(conversationId: String): List<String> =
        MessageTree.navigable(messages.getLightFor(conversationId)).map { it.id }

    private suspend fun visiblePathIds(conversationId: String): List<String> {
        val tree = MessageTree.navigable(messages.getLightFor(conversationId))
        val visible = tree.filter { it.visible }.mapTo(HashSet()) { it.id }
        return MessageTree.activePathIds(tree).filter { it in visible }
    }

    /** 完整的备份模型（JSON 导出与 ZIP 归档共用）。 */
    private suspend fun fullBackup(): BackupFile {
        val rows = conversations.getAllRaw()
        val presetByConversation = conversationPresets.getAllRaw()
            .groupBy { it.conversationId }

        return BackupFile(
            exportedAt = System.currentTimeMillis(),
            conversations = rows.map { row ->
                row.toBackup(
                    messages = exportableIds(row.id).map { readMessageForExport(it) }.withInlineAttachments(),
                    presetIds = presetByConversation[row.id]
                        .orEmpty()
                        .sortedBy { it.sortOrder }
                        .map { it.presetId },
                )
            },
            presets = presets.getAll().map { it.toBackup() },
        )
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
                    messages = exportableIds(conversationId).map { readMessageForExport(it) }.withInlineAttachments(),
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
        val ids = visiblePathIds(conversationId)
        if (ids.isEmpty()) return null
        val writer = StringWriter()
        writeConversationMarkdown(conversation, ids, writer)
        return writer.toString()
    }

    private suspend fun writeConversationMarkdown(conversation: ConversationEntity, ids: List<String>, writer: Writer) {
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        with(writer) {
            appendLine("# ${conversation.title}")
            appendLine()
            // 头部两行的文案随界面语言（`export_meta` / `export_scope_note`）
            appendLine(
                "> ${conversation.model.ifBlank { Res.get(R.string.export_unknown_model) }} · " +
                    Res.get(R.string.export_meta, ids.size, exportedAt),
            )
            appendLine("> ${Res.get(R.string.export_scope_note)}")
            ids.forEach { id ->
                currentCoroutineContext().ensureActive()
                val message = readMessageForExport(id).toUi()
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
            flush()
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
    suspend fun importBackup(text: String, mode: ImportMode): ImportReport =
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
    ): Pair<ImportReport, String> = importExternal(ExternalBackup.parse(bytes, scratchDir), mode)

    /**
     * 从**磁盘上的文件**导入 —— 大文件（几百 MB 的 DeepSeek 导出）必须走这条。
     *
     * 整包读进 `ByteArray` 会 OOM 或长时间无响应，而 `ZipFile` 只需要文件本身。
     * 调用方负责在导入结束后删掉临时文件。
     */
    suspend fun importExternal(
        file: File,
        scratchDir: File,
        mode: ImportMode,
        onProgress: (Float) -> Unit = {},
    ): Pair<ImportReport, String> = try {
        importExternalFile(file, scratchDir, mode, onProgress)
    } catch (error: AppTextException) {
        throw error
    } catch (error: java.util.zip.ZipException) {
        // 包体损坏（CRC / 结构错）。给用户一句能行动的话，而不是 `Invalid CEN header`
        throw AppTextException(R.string.err_archive_damaged)
    } catch (error: java.io.EOFException) {
        // 典型是 `Unexpected end of ZLIB input stream`：文件被截断（下载中断/空间不足）
        throw AppTextException(R.string.err_archive_damaged)
    }

    private suspend fun importExternalFile(
        file: File,
        scratchDir: File,
        mode: ImportMode,
        onProgress: (Float) -> Unit,
    ): Pair<ImportReport, String> {
        val isZip = file.inputStream().use { it.read() == 0x50 && it.read() == 0x4b }
        if (!isZip && runCatching { OwnBackupStream.format { file.reader(Charsets.UTF_8) } }.getOrNull() == BackupFile.FORMAT) {
            return importOwnStream({ file.reader(Charsets.UTF_8) }, scratchDir, mode, onProgress)
        }
        if (isZip) ZipFile(file).use { zip ->
            val manifest = zip.getEntry(ChatArchive.MANIFEST)
            val metadata = if (manifest != null && manifest.size in 1..65_536) {
                runCatching { backupJson.parseToJsonElement(zip.getInputStream(manifest).reader(Charsets.UTF_8).use { it.readText() })
                    .jsonObject }.getOrNull()
            } else null
            if (metadata?.get("export_format_version")?.toString() == "3" &&
                metadata["simplechat_extension_version"]?.toString() == "1" &&
                metadata["simplechat_backup"]?.jsonPrimitive?.content == ChatArchive.LEGACY_DEEPSEEK_BACKUP) {
                val backup = zip.getEntry(ChatArchive.LEGACY_DEEPSEEK_BACKUP)
                    ?: throw AppTextException(R.string.err_archive_damaged)
                return importOwnStream({ zip.getInputStream(backup).reader(Charsets.UTF_8) }, scratchDir, mode, onProgress)
            }
            val own = metadata?.get("export_format_version") == null && manifest != null && manifest.size in 1..65_536 &&
                runCatching { OwnBackupStream.format { zip.getInputStream(manifest).reader(Charsets.UTF_8) } }.getOrNull() == ChatArchive.FORMAT
            if (own) {
                val backup = zip.getEntry(ChatArchive.BACKUP) ?: throw AppTextException(R.string.err_archive_damaged)
                return importOwnStream({ zip.getInputStream(backup).reader(Charsets.UTF_8) }, scratchDir, mode, onProgress)
            }
            // Keep the same ZIP open while consuming the two-pass DeepSeek importer.
            if (metadata == null || metadata["export_format_version"] != null) {
                val failures = mutableListOf<ImportFailure>()
                var droppedImages = 0
                val opened = DeepSeekHistory.open(
                    zip = zip,
                    parsedManifest = metadata,
                    naming = importNaming(),
                    onProgress = onProgress,
                    onFailure = { failure ->
                        failures += failure
                        android.util.Log.w(ImportLogTag, "跳过会话 ${failure.reference}：${failure.reason}")
                    },
                    onImageDropped = { droppedImages++ },
                )
                if (opened != null) {
                    val report = importSessions(opened, mode, failures).copy(droppedImages = droppedImages)
                    val reportSaved = saveImportReport(report)
                    if (report.expected == 0) throw AppTextException(R.string.err_no_sessions)
                    return report to importSummary(
                        sourceName = Res.get(R.string.backup_source_deepseek),
                        report = report,
                        warnings = buildList {
                            add(AppText.of(R.string.warn_deepseek_limited))
                            if (!reportSaved) add(AppText.of(R.string.import_report_not_saved))
                        },
                    )
                }
            }
        }

        return importExternal(
            ExternalBackup.parse(file, scratchDir, naming = importNaming(), onProgress = onProgress),
            mode,
        )
    }

    private suspend fun importOwnStream(
        reader: () -> java.io.Reader, scratch: File, mode: ImportMode, onProgress: (Float) -> Unit,
    ): Pair<ImportReport, String> {
        onProgress(0f)
        val failures = mutableListOf<ImportFailure>()
        val opened = OwnBackupStream.open(reader, scratch, importNaming(), onProgress) { failures += it }
        val report = importSessions(opened, mode, failures)
        val reportSaved = saveImportReport(report)
        onProgress(1f)
        return report to importSummary(Res.get(R.string.backup_source_simplechat), report,
            if (reportSaved) emptyList() else listOf(AppText.of(R.string.import_report_not_saved)))
    }

    /**
     * 导入时图片**落盘**：字节进 `filesDir/attachments/`，行里只留文件名。
     *
     * 内联会让"附件 + 正文"那一行超过 `CursorWindow` 的 2MB，整条会话读不出来；
     * 落盘之后导入侧的图片配额才能全部取消（见 `AttachmentFiles`）。
     */
    private fun importNaming() = ExternalBackup.ImportNaming(
        storeImage = { bytes, mime -> AttachmentFiles.save(context, bytes, mime) },
        storeImageStream = { source, mime -> AttachmentFiles.save(context, source, mime) },
        discardImage = { AttachmentFiles.delete(context, it) },
    )

    private fun saveImportReport(report: ImportReport): Boolean = try {
        val target = File(context.filesDir, "last-import-report.json")
        val temporary = File(context.filesDir, "last-import-report.tmp")
        temporary.writeText(backupJson.encodeToString(ImportReport.serializer(), report), Charsets.UTF_8)
        check(temporary.renameTo(target)) { "Could not save import report" }
        true
    } catch (error: Exception) {
        android.util.Log.w(ImportLogTag, "Import summary file unavailable: ${error.javaClass.simpleName}")
        false
    }

    fun lastImportReportFile(): File? = File(context.filesDir, "last-import-report.json").takeIf { it.isFile }

    /** 把落盘附件的字节读回内存，供构造请求 / 导出备份使用（都在 IO 上做）。 */
    internal suspend fun materialize(attachments: List<Attachment>): List<Attachment> =
        if (attachments.none { it.isStored }) attachments
        else withContext(Dispatchers.IO) { AttachmentFiles.materialize(context, attachments) }

    /**
     * 构造请求前整理附件：**字节读回内存 + 图片按真实内容校正**。
     *
     * 两件事合成一个入口，是因为它们在请求链路上本来就是同一步：
     * 读字节 → 判格式 → 必要时转码 → 回 base64。为什么要按内容判断、为什么要转码，
     * 见 [ApiImage]：历史里的图片可能是服务端不认的格式，而**每一轮都会重发**，
     * 一张坏图能让整个会话永远发不出去。
     *
     * @return 可内联的附件 + 被丢掉的图片张数（损坏或解不开）。宁可少一张图，
     *   也不能让整轮请求 400 —— 丢弃数量由调用方如实告诉用户。
     */
    internal suspend fun prepareRequestAttachments(
        attachments: List<Attachment>,
    ): Pair<List<Attachment>, Int> {
        if (attachments.isEmpty()) return attachments to 0
        return withContext(Dispatchers.IO) {
            var dropped = 0
            val prepared = attachments.mapNotNull { attachment ->
                if (!attachment.isImage) return@mapNotNull attachment
                val image = if (attachment.isStored) {
                    ApiImage.prepare { AttachmentFiles.open(context, attachment) }
                } else {
                    runCatching { Base64.decode(attachment.data, Base64.NO_WRAP) }.getOrNull()?.let { ApiImage.prepare(it) }
                }
                if (image == null) {
                    dropped++
                    null
                } else {
                    attachment.copy(mime = image.mediaType, data = image.base64, path = null)
                }
            }
            prepared to dropped
        }
    }

    private val fileApiTextCache by lazy { FileApiTextCache(context) }

    internal suspend fun prepareLongTextForRequest(text: String, attachments: List<Attachment>, settings: StoredSettings) =
        fileApiTextCache.prepare(text, attachments, settings)

    /**
     * 导出的前置处理：把落盘附件的字节读回 base64。
     *
     * 备份格式没变（图片仍是内联 base64），所以"导入 → 导出 → 再导入"这条链路
     * 与原来自洽；区别只在**运行期**的存储位置。
     */
    private suspend fun List<MessageEntity>.withInlineAttachments(): List<MessageEntity> =
        if (none { Attachments.decode(it.attachments).any { a -> a.isStored } }) {
            this
        } else withContext(Dispatchers.IO) {
            map { message ->
                val attachments = Attachments.decode(message.attachments)
                if (attachments.none { it.isStored }) message
                else message.copy(
                    attachments = Attachments.encode(AttachmentFiles.materialize(context, attachments)),
                )
            }
        }

    private suspend fun importExternal(
        parsed: ExternalBackup.Parsed,
        mode: ImportMode,
    ): Pair<ImportReport, String> {
        val report = importStream(
            conversationsSeq = parsed.file.conversations.asSequence(),
            presetsToWrite = parsed.file.presets,
            mode = mode,
            expected = parsed.expected,
            failures = parsed.failures,
            droppedImages = parsed.droppedImages,
        )
        return report to importSummary(
            sourceName = Res.get(parsed.source.nameRes),
            report = report,
            warnings = parsed.warnings,
        )
    }

    /**
     * 从解析好的备份模型落库。
     *
     * - [ImportMode.MERGE]：按 `id` 去重，已存在的一律跳过。多设备汇总时不会覆盖本地改动。
     * - [ImportMode.REPLACE_SAME]：同名先删再导。
     *
     * 预设**各模式都不删本地已有项**，只按 id 覆盖 —— 预设是用户长期积累的资产，
     * 不该因为恢复一份旧备份就被清掉。
     */
    suspend fun importParsed(file: BackupFile, mode: ImportMode): ImportReport =
        importStream(file.conversations.asSequence(), file.presets, mode, expected = file.conversations.size)

    internal suspend fun previewDeepSeekShare(file: File, shareId: String, fallbackTitle: String): DeepSeekShare.Preview =
        DeepSeekShare.preview(file, shareId, fallbackTitle)

    internal suspend fun importDeepSeekShare(
        stream: DeepSeekShare.ShareStream,
        onProgress: (Float) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        onProgress(0f)
        stream.onProgress = onProgress
        val report = importSessions(stream, ImportMode.MERGE, mutableListOf()).copy(droppedImages = stream.attachments)
        val reportSaved = saveImportReport(report)
        onProgress(1f)
        importSummary(
            sourceName = Res.get(R.string.deepseek_share_source),
            report = report,
            warnings = buildList {
                if (!reportSaved) add(AppText.of(R.string.import_report_not_saved))
            },
        )
    }

    /**
     * **流式**导入：逐批取会话、逐批写库。
     *
     * 为什么不能"先建好整个 `BackupFile` 再写"：那个模型的峰值内存 = 全部会话 +
     * 全部图片 base64 之和。实测 2106 个会话 / 24535 条消息的 DeepSeek 归档解压后
     * 是几百兆，堆里放不下 —— 报错形如
     * `Failed to allocate a 893424 byte allocation with 424432 free bytes`。
     * 改成流式 + 分批事务之后，峰值只与"单条最大的会话"有关，与归档总量无关。
     *
     * ### 写库的底线：一条坏数据不许毁掉整次导入
     *
     * 一批 50 条共用**一个事务**（快）。但只要有一条写不进去，整个事务回滚 ——
     * 前面的 49 条也跟着没了，而用户看到的只是"导入失败"。所以这里在整批失败时
     * **退化成逐条重试**：坏的那条被隔离、记进 [ImportReport.failures]，其余照常入库。
     * 正常路径没有额外开销（一次事务），只有真出问题时才多花一点时间。
     */
    private suspend fun importStream(
        conversationsSeq: Sequence<BackupConversation>,
        presetsToWrite: List<BackupPreset>,
        mode: ImportMode,
        expected: Int? = null,
        failures: List<ImportFailure> = emptyList(),
        droppedImages: Int = 0,
    ): ImportReport {
        // All formats share transactional reconciliation and collision protection.
        // The legacy adapter can still materialize one external conversation while parsing.
        val source = object : ImportSessionSource {
            override val expected = expected
            override val indexVerified: Boolean? = null
            override val presets = presetsToWrite
            override suspend fun forEachSession(block: suspend (ImportSession) -> Unit) {
                for (conversation in conversationsSeq) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    block(ImportSession(conversation.copy(messages = emptyList()),
                        expectedMessages = conversation.messages.size) { sink ->
                        conversation.messages.forEach { sink(it) }
                    })
                }
            }
        }
        return importSessions(source, mode, failures.toMutableList()).copy(droppedImages = droppedImages)
    }

    /**
     * 把 [ImportReport] 拼成一句**能对账**的话。
     *
     * 结果页是用户判断"迁移成没成"的唯一依据，所以这里按"声明 → 结果 → 差额 → 原因"
     * 的顺序说完整：会话 4 个（归档共 5 个）· 1 个未能导入：会话文件损坏 ×1。
     * 宁可句子长一点，也不要一句"导入完成"然后少一条。
     */
    private fun importSummary(
        sourceName: String,
        report: ImportReport,
        warnings: List<AppText>,
    ): String = buildString {
        append(Res.get(if (report.complete) R.string.import_done else R.string.import_partial,
            sourceName, report.conversations, report.messages))
        if (report.indexVerified == false) append(Res.get(R.string.import_index_unverified))
        if (report.repairedMessages > 0) append(Res.get(R.string.import_repaired_messages, report.repairedMessages))
        report.expected?.let { expected ->
            if (expected != report.conversations + report.alreadyPresent) {
                append(Res.get(R.string.import_done_expected, expected))
            }
        }
        if (report.alreadyPresent > 0) {
            append(Res.get(R.string.import_done_duplicates, report.alreadyPresent))
        }
        if (report.failures.isNotEmpty()) {
            append(
                Res.get(
                    R.string.import_done_failed,
                    report.failures.size,
                    failureReasons(report.failures),
                ),
            )
            /*
             * 能说出**是哪一条**就尽量说：DeepSeek 归档的索引里有标题，
             * 用户看到"《示例会话》没能导入"才知道该重建哪一个，
             * 一个光秃秃的"1 个未能导入"帮不上忙。最多列 3 个，再多就把提示撑成一段日志了。
             */
            val titles = report.failures
                .mapNotNull { failure -> failure.title?.trim()?.takeIf { it.isNotEmpty() } }
                .distinct()
                .take(MaxFailedTitlesInSummary)
            if (titles.isNotEmpty()) {
                append(
                    Res.get(
                        R.string.import_failed_titles,
                        titles.joinToString(Res.get(R.string.list_separator)),
                    ),
                )
            }
        }
        if (report.droppedImages > 0) {
            append(Res.get(R.string.import_note_separator))
            append(Res.get(R.string.warn_dropped_images, report.droppedImages))
        }
        if (warnings.isNotEmpty()) {
            append(Res.get(R.string.import_note_separator))
            append(warnings.joinToString(Res.get(R.string.list_separator)) { it.resolve() })
        }
    }

    /** 失败原因按"一句话 × 条数"归并：`会话文件损坏 ×3、会话文件过大 ×1`。 */
    private fun failureReasons(failures: List<ImportFailure>): String =
        failures.groupingBy { it.reason }.eachCount()
            .entries
            .sortedBy { it.key.ordinal }
            .joinToString(Res.get(R.string.list_separator)) { (reason, count) ->
                Res.get(R.string.import_fail_item, Res.get(reason.labelRes()), count)
            }

    // ── DeepSeek 导出：逐条流式写库 ────────────────────────
    //
    // 为什么单独一条链路：它的单条会话可以几十 MB（长篇创作 + 大量分支），
    // 整条建进内存再写会 OOM，所以走"读一条、写一条"（见 `ImportSession`）。
    // 本应用备份与已解析的外部格式都复用同一个会话事务写入器。

    /**
     * 逐条会话写库：**每个会话一个事务**。
     *
     * 为什么不沿用"一批 50 个会话一个事务"：流式会话的正文是现从 ZIP 里读的，
     * 攒 50 个会话就意味着攒 50 份正文 —— 又回到内存问题。每会话一个事务的代价
     * 只是多几次提交。峰值仍受最大单条消息影响，必须用低内存设备验证。
     */
    private suspend fun importSessions(
        stream: ImportSessionSource,
        mode: ImportMode,
        failures: MutableList<ImportFailure>,
    ): ImportReport {
        var alreadyPresent = 0
        var importedMessages = 0
        var importedConversations = 0
        val writeFailures = mutableListOf<ImportFailure>()
        val audits = mutableListOf<ImportedSessionAudit>()
        val importedIds = mutableSetOf<String>()
        var repairedMessages = 0

        /*
         * 同名判定要反复用（每个会话一次），先取一份标题快照。
         *
         * 它同时充当"本次导入已经占用的标题"：同一份备份里出现两个同名会话时，
         * 第二个也能拿到不同的副本名，不会两条都叫「副本」。
         */
        val titles = mutableSetOf<String>()
        db.withTransaction { titles += conversations.allTitles() }

        if (stream.presets.isNotEmpty()) db.withTransaction {
            stream.presets.forEach { preset -> presets.upsert(PresetEntity(
                id = preset.id, name = preset.name, content = preset.content, sortOrder = preset.sortOrder,
                createdAt = preset.createdAt, updatedAt = preset.updatedAt,
            )) }
        }

        stream.forEachSession { session ->
            val header = session.header
            val titlesBefore = titles.toSet()
            val createdPaths = linkedSetOf<String>()
            fun discardCreated() {
                session.discard()
                createdPaths.forEach { AttachmentFiles.delete(context, it) }
            }
            val outcome = try {
                db.withTransaction { writeStreamedSession(session, mode, titles, importedIds, createdPaths) }
            } catch (error: CancellationException) {
                discardCreated()
                throw error
            } catch (error: Throwable) {
                discardCreated()
                titles.clear()
                titles += titlesBefore
                /*
                 * 一条会话写不进去就隔离它：事务已经整体回滚，这条会话不会留下
                 * 半截数据，后面的会话照常导入。原因如实记进结果页 ——
                 * 用户至少知道"少了哪一个"。
                 */
                android.util.Log.w(ImportLogTag, "Session import failed: ${error.javaClass.simpleName}")
                writeFailures += ImportFailure(
                    title = header.title,
                    reference = header.id,
                    reason = when (error) {
                        is android.database.sqlite.SQLiteFullException -> ImportFailure.Reason.INSUFFICIENT_SPACE
                        is OutOfMemoryError -> ImportFailure.Reason.INSUFFICIENT_MEMORY
                        is java.io.IOException -> if (context.filesDir.usableSpace < 16L * 1024 * 1024) ImportFailure.Reason.INSUFFICIENT_SPACE else ImportFailure.Reason.WRITE_FAILED
                        else -> ImportFailure.Reason.WRITE_FAILED
                    },
                    detail = "${error.javaClass.simpleName}: ${error.message}",
                )
                null
            }
            audits += ImportedSessionAudit(header.id, outcome?.targetId, session.expectedMessages,
                outcome?.messages ?: 0, if (outcome == null) "FAILED" else if (outcome.skipped) "ALREADY_PRESENT" else "IMPORTED")
            when {
                outcome == null -> Unit
                outcome.skipped -> alreadyPresent++
                else -> {
                    importedConversations++
                    importedMessages += outcome.messages
                    repairedMessages += session.repairedMessages
                    outcome.targetId?.let { importedIds += it }
                }
            }
        }

        return ImportReport(
            conversations = importedConversations,
            messages = importedMessages,
            presets = stream.presets.size,
            alreadyPresent = alreadyPresent,
            expected = stream.expected,
            failures = failures.toList() + writeFailures,
            // 图片丢弃数在消费过程中累加，由调用方补进来（见 importExternalFile）
            droppedImages = 0,
            repairedMessages = repairedMessages,
            indexVerified = stream.indexVerified,
            sessions = audits + failures.map { ImportedSessionAudit(it.reference.orEmpty(), null, null, 0, "FAILED") },
        )
    }

    /** 写一条流式会话的结果。[skipped] 表示按导入策略跳过了（已存在）。 */
    private class StreamedOutcome(val skipped: Boolean, val messages: Int = 0, val targetId: String? = null)

    /**
     * 把一条流式会话写进库：会话行 → 逐条消息 → 预设挂载 → 附件引用。
     *
     * 整段跑在调用方给的事务里：要么整条会话落地，要么一条都不留。
     * 消息按条数和字符数双重限制分批插入；ID 冲突必须回滚，不能覆盖别的会话。
     */
    private suspend fun writeStreamedSession(
        session: ImportSession,
        mode: ImportMode,
        titles: MutableSet<String>,
        protectedIds: Set<String> = emptySet(),
        createdPaths: MutableSet<String>,
    ): StreamedOutcome {
        val prepared = prepareConversation(session.header, mode, titles, protectedIds)
            ?: return StreamedOutcome(skipped = true, targetId = session.header.id)
        val conversation = prepared.conversation
        val remapId = prepared.remapId

        // 会话内重复 ID 显式失败，全局 ID 冲突由 ABORT 插入保护。
        val existing = messages.idsFor(conversation.id).toHashSet()
        val batch = ArrayList<BackupMessage>(ImportMessageBatch)
        val imagePaths = LinkedHashSet<String>()
        var written = 0
        var batchChars = 0L
        var received = 0

        suspend fun flush() {
            if (batch.isEmpty()) return
            val entities = batch.map { it.toEntity(conversation.id).withOffloadedImages(createdPaths) }
            // 外置后的图片路径要一起登记，否则删会话时这些文件会变成没人管的垃圾
            entities.forEach { entity ->
                Attachments.decode(entity.attachments).mapNotNullTo(imagePaths) { it.path }
            }
            messages.insertImported(entities)
            written += entities.size
            batch.clear()
            batchChars = 0
        }

        session.forEachMessage { raw ->
            received++
            val message = if (remapId == null) {
                raw
            } else {
                raw.copy(
                    id = remapId(raw.id),
                    parentId = if (raw.parentId.isBlank()) "" else remapId(raw.parentId),
                    versionGroup = raw.versionGroup?.let { if (it.isBlank()) "" else remapId(it) },
                )
            }
            check(existing.add(message.id)) { "Duplicate imported message: ${message.id}" }
            val chars = message.content.length.toLong() + (message.reasoning?.length ?: 0) +
                message.attachments.sumOf { it.data.length.toLong() }
            if (batch.isNotEmpty() && batchChars + chars > 512_000) flush()
            batch += message
            batchChars += chars
            if (batch.size >= ImportMessageBatch || batchChars >= 512_000) flush()
        }
        flush()
        session.expectedMessages?.let { check(received == it && written == it) { "Message count mismatch: $received/$written/$it" } }
        check(messages.countFor(conversation.id) == written) { "Stored message count mismatch" }

        // 恢复挂载的预设（顺序有意义：拼接顺序会影响模型对主次的判断）
        writePresetLinks(conversation.id, conversation.enabledPresetIds)
        /*
         * 登记落盘图片的引用（只记 path，几十字节）：删除会话时据此删文件，
         * 避开 messages.attachments 那一列的大 JSON（读它会撞 CursorWindow）。
         */
        if (imagePaths.isNotEmpty()) {
            attachmentRefs.upsertAll(
                imagePaths.map { path -> AttachmentRefEntity(path = path, conversationId = conversation.id) },
            )
        }
        return StreamedOutcome(skipped = false, messages = written, targetId = conversation.id)
    }

    /**
     * 导入一条会话的"外壳"：按策略判重 / 改名 / 换 id，写会话行。返回 null 表示应跳过。
     *
     * 抽出来是因为两条链路（常规备份的分批事务、DeepSeek 的逐条流式）都要它 ——
     * 各写一遍的话，"同名怎么处理"迟早会在其中一条上分叉。
     */
    private suspend fun prepareConversation(
        raw: BackupConversation,
        mode: ImportMode,
        titles: MutableSet<String>,
        protectedIds: Set<String> = emptySet(),
    ): PreparedConversation? {
        var conversation = raw
        var remapId: ((String) -> String)? = null

        when (mode) {
            ImportMode.MERGE -> {
                // 增量：按 id 去重。已存在就跳过，本地改动一律不动
                if (conversations.findExistingId(conversation.id) != null) return null
            }

            ImportMode.REPLACE_SAME -> {
                // 同名 = 同一条会话：先删旧的（消息 / 预设挂载 / 摘要靠外键级联）
                val clash = conversations.findExistingId(conversation.id)
                    ?: conversations.findIdByTitle(conversation.title)
                check(conversation.id !in protectedIds) { "Duplicate source conversation id: ${conversation.id}" }
                if (clash != null && clash !in protectedIds) {
                    deleteConversationRecords(clash)
                    titles.remove(conversation.title)
                }
            }

            ImportMode.RENAME -> {
                // 标题或 id 撞了就改名 + 换 id：一条都不能覆盖，两条并存
                val clash = conversation.title in titles ||
                    conversations.findExistingId(conversation.id) != null
                if (clash) {
                    val title = uniqueCopyTitle(
                        title = conversation.title,
                        taken = titles,
                        suffix = Res.get(R.string.conversation_fork_suffix),
                        suffixN = { n -> Res.get(R.string.conversation_fork_suffix_n, n) },
                    )
                    val newId = "${conversation.id}~${UUID.randomUUID()}"
                    conversation = conversation.copy(title = title).withFreshIds(newId)
                    // 与 withFreshIds 的 id 规则保持一致（消息 id 与父子引用一起换）
                    remapId = { old -> if (old.isBlank()) "" else "$newId#$old" }
                }
            }
        }

        titles += conversation.title
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
                memoryEnabled = conversation.memoryEnabled,
            ),
        )
        return PreparedConversation(conversation, remapId)
    }

    private class PreparedConversation(
        val conversation: BackupConversation,
        /** 消息 id 重映射（同名新命名时会换 id）；为 null 表示不需要改。 */
        val remapId: ((String) -> String)?,
    )

    /**
     * 删掉一条会话及其附属记录 —— 「覆盖同名」用。
     *
     * 消息 / 预设挂载 / 压缩摘要有外键级联，会跟着走；**草稿不会**，
     * 所以要手动清：留着就是一条指向已不存在会话的死数据。
     */
    private suspend fun deleteConversationRecords(id: String) {
        conversations.delete(id)
        drafts.clearAll(listOf(id))
        deleteCompactionCheckpoint(id)
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
            Preset(
                id = "deepseek-client-prompt",
                name = Res.get(R.string.preset_deepseek_client_name),
                content = Res.get(R.string.preset_deepseek_client_body),
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

/**
 * 流式导入时每条会话里一次写多少条消息。
 *
 * 与 512,000 字符阈值共同控制批次；单条超大消息仍会单独处理。
 */
private const val ImportMessageBatch = 32

/**
 * 导入链路的日志 tag。
 *
 * 用户看不到日志，但"哪条会话被跳过 / 为什么"必须留痕 —— 否则用户说
 * "少了两个对话"时，我们只能猜。
 */
private const val ImportLogTag = "SimpleChat/Import"

/** 导入结果里最多列出几个失败会话的标题（再多就把提示撑成一段日志了）。 */
private const val MaxFailedTitlesInSummary = 3

/**
 * 按 id 批量取消息时，一次带多少个 id。
 *
 * SQLite 的绑定变量数有上限（老系统 999），400 留足余量，又不会因为批太小
 * 而把一条路径拆成上百次查询。
 */
private const val MessageQueryChunk = 400

/** Five byte slices plus metadata stay well below a CursorWindow's capacity. */
private const val ExportTextChunkBytes = 16_384

/** 回收站保留天数：超过就真删（与 dsh-session-delete 的约定一致）。 */
const val TRASH_RETENTION_DAYS = 15
