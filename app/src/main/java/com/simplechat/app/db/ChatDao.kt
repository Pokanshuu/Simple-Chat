package com.simplechat.app.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    /** 列表页主查询：置顶优先，其次按最近更新。 */
    @Query(
        """
        SELECT * FROM conversations
        ORDER BY pinned DESC,
                 CASE WHEN pinned THEN pinnedAt ELSE updatedAt END DESC
        """,
    )
    fun observeAll(): Flow<List<ConversationEntity>>

    /** 跨对话记忆检索用：一次性取回会话标题与更新时间（不观察）。 */
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun allConversations(): List<ConversationEntity>

    /** One bounded preview per matching message; title-only hits remain visible. */
    @Query(
        """
        SELECT c.*,
            CASE WHEN m.id IS NULL THEN NULL ELSE
                substr(m.content, max(1, instr(lower(m.content), lower(:query)) - 60), max(180, length(:query) + 120))
            END AS snippet,
            m.id AS messageId, m.role AS messageRole, m.createdAt AS messageCreatedAt,
            CASE WHEN m.id IS NOT NULL THEN
                (SELECT count(*) FROM messages sibling WHERE sibling.conversationId = m.conversationId
                 AND sibling.parentId = m.parentId AND sibling.variantIndex < m.variantIndex)
            END AS variantIndex,
            CASE WHEN m.id IS NULL THEN 0 ELSE
                (SELECT count(*) FROM messages sibling WHERE sibling.conversationId = m.conversationId
                 AND sibling.parentId = m.parentId)
            END AS variantCount
        FROM conversations c
        LEFT JOIN messages m ON m.conversationId = c.id
            AND instr(lower(m.content), lower(:query)) > 0
            AND (:includeInactiveBranches OR m.active = 1)
        WHERE length(:query) > 0 AND (m.id IS NOT NULL OR instr(lower(c.title), lower(:query)) > 0)
        ORDER BY c.pinned DESC,
                 CASE WHEN c.pinned THEN c.pinnedAt ELSE c.updatedAt END DESC,
                 c.id ASC, m.createdAt DESC, m.id ASC
        LIMIT :limit
        """,
    )
    fun search(
        query: String,
        includeInactiveBranches: Boolean = true,
        limit: Int = Int.MAX_VALUE,
    ): Flow<List<ConversationSearchHit>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun findById(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE conversations SET pinned = :pinned, pinnedAt = :pinnedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, pinnedAt: Long?)

    /** 会话补充提示词。挂载的预设另存于 `conversation_presets`。 */
    @Query("UPDATE conversations SET systemPrompt = :systemPrompt WHERE id = :id")
    suspend fun setSystemPrompt(id: String, systemPrompt: String?)

    @Query("UPDATE conversations SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    /** 导出用：全量（含未激活的历史版本）。 */
    @Query("SELECT * FROM conversations ORDER BY createdAt ASC")
    suspend fun getAllRaw(): List<ConversationEntity>

    /** 全部标题。「从这里新建对话」生成副本名时用来去重。 */
    @Query("SELECT title FROM conversations")
    suspend fun allTitles(): List<String>

    @Query("SELECT id FROM conversations WHERE id = :id")
    suspend fun findExistingId(id: String): String?

    /**
     * 按**标题**找 id —— 导入时判定"同名"用。
     *
     * 为什么同名要靠标题而不是 id：同一条会话换个设备、或者隔一次导出，id 就变了，
     * 而标题是用户认得出的那个"同一个对话"。
     */
    @Query("SELECT id FROM conversations WHERE title = :title ORDER BY createdAt ASC LIMIT 1")
    suspend fun findIdByTitle(title: String): String?

    /** 导入（覆盖模式）用。会话删除会级联清掉消息 / 预设挂载 / 摘要。 */
    @Query("DELETE FROM conversations")
    suspend fun deleteEverything()
}

/**
 * 一条消息的**骨架**（见 `MessageDao.getLightFor`）。
 *
 * 刻意只有这几列：判断"当前分支是哪条"不需要正文与附件。
 */
data class MessageLight(
    val id: String,
    val parentId: String,
    val variantIndex: Int,
    val active: Boolean,
    val role: String,
    val status: String,
    val createdAt: Long,
)

/**
 * 搜索命中：会话 + 命中的那条消息正文。
 *
 * [snippet] 为 null 表示只有**标题**命中，没有正文片段可显示。
 */
data class ConversationSearchHit(
    @Embedded val conversation: ConversationEntity,
    val snippet: String?,
    val messageId: String? = null,
    val messageRole: String? = null,
    val messageCreatedAt: Long? = null,
    val variantIndex: Int? = null,
    val variantCount: Int = 0,
)

/** Bounded fields for lossless export of messages larger than CursorWindow. */
data class MessageTextChunk(
    val content: ByteArray?,
    val attachments: ByteArray?,
    val reasoning: ByteArray?,
    val timeline: ByteArray?,
    val errorMessage: ByteArray?,
    val attachmentsPresent: Boolean = false,
    val reasoningPresent: Boolean = false,
    val timelinePresent: Boolean = false,
    val errorPresent: Boolean = false,
)

@Dao
interface MessageDao {

    /**
     * 一个会话的**全部**消息（含所有分支、所有版本）。
     *
     * 树结构在内存里走：会话规模就几百条，一次读回来比递归 CTE 好得多 ——
     * 可测、可读、不受 SQLite 方言差异影响。
     */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId")
    suspend fun getAllFor(conversationId: String): List<MessageEntity>

    /**
     * 同 [getAllFor]，但把 `attachments` 截断到 [cap] 字符。
     *
     * **兜底用**：图片 base64 能到几兆，而 Android 的 `CursorWindow` 只有 2MB ——
     * 整行塞不进去就抛 `SQLiteBlobTooBigException`，那个会话从此打不开（实测闪退）。
     * 截断之后那一行的附件解析会失败，按约定当"没有附件"，至少正文还能看。
     */
    @Query(
        """
        SELECT id, conversationId, parentId, variantIndex, active, role, content,
               substr(attachments, 1, :cap) AS attachments,
               reasoning, status, errorMessage, model, excluded,
               thinkingMs, ttftMs, tps, tokens_in, tokens_out, tokens_cache_hit,
               timeline, stopReason, createdAt
        FROM messages WHERE conversationId = :conversationId
        """,
    )
    suspend fun getAllForCapped(conversationId: String, cap: Int): List<MessageEntity>

    /**
     * 只要每条消息的 `parentId` —— 数兄弟版本用。
     *
     * 刻意**不**用 `getAllFor`：附件列里可能躺着几百 KB 的图片 base64，
     * 而数版本号只需要这一个字段。这个方法会在每次追加消息时被调用。
     */
    @Query("SELECT id FROM messages WHERE conversationId = :conversationId AND parentId = :parentId ORDER BY variantIndex")
    suspend fun siblingIds(conversationId: String, parentId: String): List<String>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findById(id: String): MessageEntity?

    @Query(
        """
        SELECT id, conversationId, parentId, variantIndex, active, role,
               '' AS content, NULL AS attachments, NULL AS reasoning,
               status, NULL AS errorMessage,
               model, excluded, thinkingMs, ttftMs, tps, tokens_in, tokens_out, tokens_cache_hit,
               NULL AS timeline, stopReason, createdAt
        FROM messages WHERE id = :id
        """,
    )
    suspend fun exportHeader(id: String): MessageEntity?

    @Query(
        """
        SELECT substr(CAST(content AS BLOB), :start, :chunkBytes) AS content,
               substr(CAST(attachments AS BLOB), :start, :chunkBytes) AS attachments,
               substr(CAST(reasoning AS BLOB), :start, :chunkBytes) AS reasoning,
               substr(CAST(timeline AS BLOB), :start, :chunkBytes) AS timeline,
               substr(CAST(errorMessage AS BLOB), :start, :chunkBytes) AS errorMessage,
               attachments IS NOT NULL AS attachmentsPresent,
               reasoning IS NOT NULL AS reasoningPresent,
               timeline IS NOT NULL AS timelinePresent,
               errorMessage IS NOT NULL AS errorPresent
        FROM messages WHERE id = :id
        """,
    )
    suspend fun exportTextChunk(id: String, start: Int, chunkBytes: Int): MessageTextChunk?

    /**
     * 一个会话里所有消息的**骨架**：只读判活路径需要的几列。
     *
     * ### 为什么打开会话要先读一遍它
     *
     * 打开长会话最贵的不是"走树"，而是**把不需要的消息正文读出来**：
     * 一条分支被重新生成过 5 次，树上就有 5 条回复，而界面上只显示其中一条；
     * 每次 `SELECT *` 都把这 5 条的正文（还带 `attachments` / `timeline` 两段 JSON）
     * 全读进内存。几十条消息看不出差别，几千条的会话就是几十兆 —— 真机上
     * 表现是"点进去转半天，甚至闪退"。
     *
     * 先读骨架（每行只有几十字节）→ 在内存里走出活跃路径 → 只按 id 取那几条的正文。
     * 被放弃的分支因此**一次都不会被读出来**。
     */
    @Query(
        """
        SELECT id, parentId, variantIndex, active, role, status, createdAt
        FROM messages WHERE conversationId = :conversationId
        """,
    )
    suspend fun getLightFor(conversationId: String): List<MessageLight>

    /** 按 id 批量取整条消息（活跃路径上的那些）。 */
    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<MessageEntity>

    /**
     * 同 [getByIds]，但把 `attachments` 截断到 [cap] 字符。
     *
     * **兜底用**：老数据里的图片是内联 base64，单行可能超过 `CursorWindow` 的 2MB，
     * 那样整条会话都读不出来。截断之后那一行的附件解析会失败，按约定当"没有附件"，
     * 至少正文、思考、时间线都还在，会话能打开。
     */
    @Query(
        """
        SELECT id, conversationId, parentId, variantIndex, active, role, content,
               substr(attachments, 1, :cap) AS attachments,
               reasoning, status, errorMessage, model, excluded,
               thinkingMs, ttftMs, tps, tokens_in, tokens_out, tokens_cache_hit,
               timeline, stopReason, createdAt
        FROM messages WHERE id IN (:ids)
        """,
    )
    suspend fun getByIdsCapped(ids: List<String>, cap: Int): List<MessageEntity>

    /** 同父兄弟里的下一个版本号。 */
    @Query(
        """
        SELECT COALESCE(MAX(variantIndex), -1) + 1 FROM messages
        WHERE conversationId = :conversationId AND parentId = :parentId
        """,
    )
    suspend fun nextVariantIndex(conversationId: String, parentId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    /** An import must never replace a message owned by another conversation. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertImported(messages: List<MessageEntity>)

    /** 流式期间的 checkpoint 写入（§4.4）。 */
    @Query(
        """
        UPDATE messages
        SET content = :content, reasoning = :reasoning, status = :status, timeline = :timeline
        WHERE id = :id
        """,
    )
    suspend fun checkpoint(
        id: String,
        content: String,
        reasoning: String?,
        status: String,
        timeline: String?,
    )

    @Query(
        """
        UPDATE messages
        SET content = :content, reasoning = :reasoning, status = :status, errorMessage = :error,
            ttftMs = :ttftMs, tps = :tps, thinkingMs = :thinkingMs,
            timeline = :timeline, stopReason = :stopReason,
            tokens_in = :tokensIn, tokens_out = :tokensOut, tokens_cache_hit = :cacheHitTokens
        WHERE id = :id
        """,
    )
    suspend fun finish(
        id: String,
        content: String,
        reasoning: String?,
        status: String,
        error: String?,
        ttftMs: Long?,
        tps: Float?,
        thinkingMs: Long?,
        timeline: String?,
        stopReason: String?,
        tokensIn: Int?,
        tokensOut: Int?,
        cacheHitTokens: Int?,
    )

    /**
     * 全库累计用量（设置 → 用量与余额）。
     *
     * 只统计服务端回报过用量的回复：老数据 tokens_in 为 null，算进来会让"总消耗"
     * 被大量 0 稀释。每轮请求的 prompt 都含前面全部上下文，所以直接求和就是
     * 真实计费口径，不需要去重。
     */
    @Query(
        """
        SELECT COUNT(*) AS replies,
               COALESCE(SUM(tokens_in), 0) AS promptTokens,
               COALESCE(SUM(tokens_out), 0) AS completionTokens,
               COALESCE(SUM(tokens_cache_hit), 0) AS cacheHitTokens
        FROM messages
        WHERE role = 'assistant' AND tokens_in IS NOT NULL
        """,
    )
    suspend fun usageTotals(): UsageTotals

    @Query(
        """
        SELECT date(createdAt / 1000, 'unixepoch', 'localtime') AS day,
               SUM(tokens_in + COALESCE(tokens_out, 0)) AS tokens
        FROM messages
        WHERE role = 'assistant' AND tokens_in IS NOT NULL AND createdAt >= :since
        GROUP BY day
        """,
    )
    suspend fun dailyUsage(since: Long): List<DailyUsage>

    /**
     * 「保存」编辑后的消息：**原地**换掉正文（可连同附件与内容块）。
     *
     * 用户对「保存」的理解就是"改这条"，而不是"再长出一条" —— 早先走的是
     * "新增版本"（`addActiveVariant`），于是只改个错别字也会冒出一组 `‹ 1/2 ›`，
     * 并且新版本没有子树：会话下半段当场消失，看起来就像"保存把我后面的聊天删了"。
     *
     * `timeline` 必须一起写：扩展通道的界面与请求都读它（见 `rewriteTimelineText`），
     * 只改 `content` 会得到"保存了却没变"。
     *
     * 状态 / 用时统计 / 思考内容都不动：保存改的是文字，不是这次请求的结果。
     */
    @Query(
        """
        UPDATE messages
        SET content = :content, attachments = :attachments, timeline = :timeline
        WHERE id = :id
        """,
    )
    suspend fun editContent(id: String, content: String, attachments: String?, timeline: String?)

    /**
     * 把卡在 `STREAMING` 的消息判为中断。
     *
     * 进程被杀 / 崩溃时，最后那条 assistant 会永远停在 STREAMING ——
     * 界面上它既没有操作栏也没有长按菜单（`actionable = !streaming`），
     * 用户**删不掉也切不走**，会永久卡在会话里。
     */
    @Query(
        """
        UPDATE messages SET status = :status
        WHERE conversationId = :conversationId AND status = 'STREAMING'
        """,
    )
    suspend fun resolveDanglingStreams(conversationId: String, status: String)

    /** 切换版本：先清掉同父兄弟，再点亮目标。 */
    @Transaction
    suspend fun activate(id: String, conversationId: String, parentId: String) {
        clearActiveSiblings(conversationId, parentId)
        markActive(id)
    }

    @Query("UPDATE messages SET active = 0 WHERE conversationId = :conversationId AND parentId = :parentId")
    suspend fun clearActiveSiblings(conversationId: String, parentId: String)

    @Query("UPDATE messages SET active = 1 WHERE id = :id")
    suspend fun markActive(id: String)

    /**
     * 重试：把这条消息**原地**清空并转回流式。
     *
     * 原地而不是"再生成一个新版本" —— 重试的是同一次请求，用户看到的
     * 还应该是那一格。冒出一个 `‹ 1/2 ›` 只会让人以为换了答案。
     */
    @Query(
        """
        UPDATE messages
        SET content = '', reasoning = NULL, model = :model,
            status = :status, errorMessage = NULL,
            timeline = NULL, stopReason = NULL
        WHERE id = :id
        """,
    )
    suspend fun resetForRetry(id: String, model: String?, status: String)

    /**
     * 续写：把这条回复重新标成流式，**内容一个字都不动**。
     *
     * 与 [resetForRetry] 的区别就是"清不清空"。续写是接着写，
     * 清空等于把用户想续的那半篇文章删了。
     */
    @Query(
        """
        UPDATE messages
        SET status = :status, errorMessage = NULL, stopReason = NULL
        WHERE id = :id
        """,
    )
    suspend fun resumeStreaming(id: String, status: String)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countFor(conversationId: String): Int

    /** 导出用：全量（含所有分支与版本）。 */
    @Query("SELECT * FROM messages")
    suspend fun getAllRaw(): List<MessageEntity>

    @Query("SELECT id FROM messages WHERE id = :id")
    suspend fun findExistingId(id: String): String?

    /**
     * 一条会话的全部消息 id（只读一列，**不碰正文与附件**）。
     *
     * 导入时用来判重：逐条 `findExistingId` 是 N 次查询，几万条消息就是几万次
     * Binder/游标开销；先取一份 id 集合再在内存里比对，代价与消息条数线性相关，
     * 单次查询就能完成。
     */
    @Query("SELECT id FROM messages WHERE conversationId = :conversationId")
    suspend fun idsFor(conversationId: String): List<String>

    /** Also protects older forks whose shared-file references were never registered. */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE instr(attachments, '\"' || :path || '\"') > 0)")
    suspend fun referencesAttachment(path: String): Boolean
}

@Dao
interface PresetDao {

    @Query("SELECT * FROM presets ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<PresetEntity>

    @Query("SELECT * FROM presets WHERE id = :id")
    suspend fun findById(id: String): PresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: PresetEntity)

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM presets")
    suspend fun count(): Int
}

@Dao
interface ConversationPresetDao {

    @Query("SELECT * FROM conversation_presets WHERE conversationId = :conversationId ORDER BY sortOrder ASC")
    suspend fun getFor(conversationId: String): List<ConversationPresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ConversationPresetEntity>)

    @Query("DELETE FROM conversation_presets WHERE conversationId = :conversationId")
    suspend fun clear(conversationId: String)

    /** 导出用：全量。 */
    @Query("SELECT * FROM conversation_presets")
    suspend fun getAllRaw(): List<ConversationPresetEntity>
}

@Dao
interface CompactionDao {

    @Query("SELECT * FROM compactions WHERE conversationId = :conversationId")
    suspend fun getFor(conversationId: String): List<CompactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(compaction: CompactionEntity)

    @Update
    suspend fun update(compaction: CompactionEntity)

    @Query("DELETE FROM compactions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DraftDao {

    @Query("SELECT * FROM drafts WHERE conversationId = :key")
    suspend fun find(key: String): DraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity)

    /** 空草稿不占行 —— 删掉，别留一堆空壳（§4.2）。 */
    @Query("DELETE FROM drafts WHERE conversationId = :key")
    suspend fun clear(key: String)

    /** 会话被删时同步清掉它的草稿。 */
    @Query("DELETE FROM drafts WHERE conversationId IN (:keys)")
    suspend fun clearAll(keys: List<String>)

    /** 「删除所有对话」连草稿一起清 —— 留着就是没人读的死数据。 */
    @Query("DELETE FROM drafts")
    suspend fun clearEverything()

    /** 验收 / 诊断用。 */
    @Query("SELECT COUNT(*) FROM drafts")
    suspend fun count(): Int
}
/**
 * 落盘图片的引用表（见 [AttachmentRefEntity]）。
 *
 * 只有三件事：登记、按会话查、按会话删。**刻意不做"全表 JOIN 消息"** ——
 * 那是为了避开 messages.attachments 那列大 JSON。
 */
@Dao
interface AttachmentRefDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(refs: List<AttachmentRefEntity>)

    @Query("SELECT path FROM attachment_refs WHERE conversationId = :conversationId")
    suspend fun pathsOf(conversationId: String): List<String>

    @Query("SELECT DISTINCT path FROM attachment_refs")
    suspend fun allPaths(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM attachment_refs WHERE path = :path)")
    suspend fun containsPath(path: String): Boolean

    @Query("DELETE FROM attachment_refs WHERE conversationId = :conversationId")
    suspend fun clear(conversationId: String)

    @Query("DELETE FROM attachment_refs WHERE conversationId IN (:ids)")
    suspend fun clearAll(ids: List<String>)

    @Query("DELETE FROM attachment_refs")
    suspend fun clearEverything()
}

/** 全库累计用量投影（见 [MessageDao.usageTotals]）。 */
data class UsageTotals(
    /** 有服务端用量回报的回复条数。 */
    val replies: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheHitTokens: Int,
)

/** Local date and reported input plus output tokens. */
data class DailyUsage(val day: String, val tokens: Long)
