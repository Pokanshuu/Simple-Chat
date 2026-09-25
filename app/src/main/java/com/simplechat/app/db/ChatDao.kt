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

    /**
     * 搜索：**标题 或 消息正文**命中。
     *
     * 命中正文时顺带带回**最早那条**命中的消息，供结果行显示片段。
     * `snippet` 是整条正文、不在 SQL 里截断 —— 中文按字节截会切出乱码，
     * 取窗口的事交给 UI 层（[snippetAround]）。
     *
     * 刻意不上 FTS：这个量级（几百个会话）`LIKE` 完全够用，
     * 而 FTS 要额外的虚表与分词器，中文还会被切坏。
     */
    @Query(
        """
        SELECT c.*,
            (SELECT m.content FROM messages m
             WHERE m.conversationId = c.id AND m.content LIKE '%' || :query || '%'
             ORDER BY m.createdAt ASC LIMIT 1) AS snippet
        FROM conversations c
        WHERE c.title LIKE '%' || :query || '%'
           OR EXISTS (SELECT 1 FROM messages m
                      WHERE m.conversationId = c.id AND m.content LIKE '%' || :query || '%')
        ORDER BY c.pinned DESC,
                 CASE WHEN c.pinned THEN c.pinnedAt ELSE c.updatedAt END DESC
        """,
    )
    fun search(query: String): Flow<List<ConversationSearchHit>>

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

    /** 导入（覆盖模式）用。会话删除会级联清掉消息 / 预设挂载 / 摘要。 */
    @Query("DELETE FROM conversations")
    suspend fun deleteEverything()
}

/**
 * 搜索命中：会话 + 命中的那条消息正文。
 *
 * [snippet] 为 null 表示只有**标题**命中，没有正文片段可显示。
 */
data class ConversationSearchHit(
    @Embedded val conversation: ConversationEntity,
    val snippet: String?,
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
     * 只要每条消息的 `parentId` —— 数兄弟版本用。
     *
     * 刻意**不**用 `getAllFor`：附件列里可能躺着几百 KB 的图片 base64，
     * 而数版本号只需要这一个字段。这个方法会在每次追加消息时被调用。
     */
    @Query("SELECT parentId FROM messages WHERE conversationId = :conversationId")
    suspend fun parentIds(conversationId: String): List<String>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findById(id: String): MessageEntity?

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

    /** 流式期间的 checkpoint 写入（§4.4）。 */
    @Query(
        """
        UPDATE messages
        SET content = :content, reasoning = :reasoning, status = :status
        WHERE id = :id
        """,
    )
    suspend fun checkpoint(id: String, content: String, reasoning: String?, status: String)

    @Query(
        """
        UPDATE messages
        SET status = :status, errorMessage = :error,
            ttftMs = :ttftMs, tps = :tps, thinkingMs = :thinkingMs
        WHERE id = :id
        """,
    )
    suspend fun finish(
        id: String,
        status: String,
        error: String?,
        ttftMs: Long?,
        tps: Float?,
        thinkingMs: Long?,
    )

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
            status = :status, errorMessage = NULL
        WHERE id = :id
        """,
    )
    suspend fun resetForRetry(id: String, model: String?, status: String)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countFor(conversationId: String): Int

    /** 导出用：全量（含所有分支与版本）。 */
    @Query("SELECT * FROM messages")
    suspend fun getAllRaw(): List<MessageEntity>

    @Query("SELECT id FROM messages WHERE id = :id")
    suspend fun findExistingId(id: String): String?
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
