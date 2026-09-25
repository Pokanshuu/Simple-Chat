package com.simplechat.app.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 会话。
 *
 * `mode` / `systemPrompt` 承载创作向设定（设计文档 §9.1.1）：
 * 最终系统提示词 = 会话补充提示词 + 各启用预设按序拼接。
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** CREATIVE | ROLEPLAY */
    val mode: String,
    /**
     * 会话建立时的服务商 / 模型。
     *
     * ⚠️ **只写不读的死列**：建会话时写进去、备份也会带上，
     * 但 `openConversation` 从不读它们 —— 运行时用的是 DataStore 里的
     * 全局模型。也就是说同一件事有两处真相，而这里这份永远是陈旧的。
     *
     * 想删掉过，**放弃了**：`messages.conversationId` 有
     * `ON DELETE CASCADE`，而 SQLite 删表的实现是"先隐式 DELETE 再从库文件里
     * 摘掉" —— 外键开着的时候那一下会把**所有消息级联删光**。
     * 想安全重建表就得关外键，而 `PRAGMA foreign_keys` 在事务里是空操作，
     * Room 的迁移恰好跑在事务里。SQLite 的 `DROP COLUMN` 要 3.35+，
     * Android 12 上只有 3.32。
     *
     * 结论：**为两列死数据冒清库的风险，不值。** 详情见设计文档 §4.2。
     */
    val provider: String,
    val model: String,
    val systemPrompt: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val pinnedAt: Long? = null,
)

/**
 * 未发送的输入草稿 —— **按会话一条**。
 *
 * 同一行还捎带「新对话」的**待用标题**（[title]）：用户在发出第一条消息之前
 * 就给会话起了名，而那一刻会话行还不存在 —— 与草稿是同一个处境，一起记在这里。
 *
 * 为什么不做成 `conversations` 上的两列：**"新对话"还没有会话行**。用户打开应用
 * 就落在"新对话"上，打一半被系统杀掉正是最常丢草稿的场景，而那一刻连会话 id
 * 都没有、更没有行可挂。单独一张表就能用 [NewConversation] 这个 key 兜住它。
 *
 * 刻意**不进备份**：草稿是未发出的临时内容，随会话一起删掉就该消失（§4.2 的
 * "没人读的写就是死数据"同一条 —— 空草稿直接删行，不留空壳）。
 */
@Entity(tableName = "drafts")
data class DraftEntity(
    /** 会话 id；还没建会话时是 [NewConversation]。 */
    @PrimaryKey val conversationId: String,
    val text: String,
    val updatedAt: Long,
    /** 「新对话」的待用标题；会话建出来后由 `conversations.title` 接管，此处清空。 */
    val title: String? = null,
) {
    companion object {
        /** 「新对话」的 key —— 会话尚未创建，没有 id 可用。 */
        const val NewConversation = ""

        /**
         * 单条草稿的长度上限。
         *
         * 不是为了省空间（Room 的 TEXT 不在乎），是为了**别让一次异常粘贴把落库拖慢**：
         * 落库是 400ms 防抖的，一份几 MB 的文本会被反复写。到不了的长度，真到就截尾。
         */
        const val MaxChars = 100_000
    }
}

/**
 * 消息 —— **树**，不是位置栈。
 *
 * 身份是 `parentId`：它挂在**哪一条**消息下面，而不是"排在第几位"。
 *
 * ```
 *        u1 ── a1 ── u2 ── a2
 *         │            │
 *         │            └── a2'   ← 在 u2 上「重新生成」
 *         └── a1'                  ← 在 u1 上「重新生成」
 * ```
 *
 * 兄弟（同一个 [parentId]）= 多版本，由 [variantIndex] 编号、[active] 标记当前选中。
 * **当前对话 = 从根沿 active 一路走到底的那条路径。**
 *
 * 这样「编辑重发」只是挂一个新兄弟上去，旧兄弟**连同它的整棵子树**原样留着，
 * 随时可以切回去 —— 位置模型做不到这件事，只能把下游删掉。
 *
 * 根消息的 [parentId] 是 [ROOT_PARENT]（空串）。用非空哨兵而不是 NULL，
 * 才能让下面的唯一索引真正生效（SQLite 的唯一索引把多个 NULL 视作互不相同）。
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversationId", "parentId", "variantIndex"], unique = true),
        Index(value = ["conversationId", "parentId"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    /** 父亲消息 id；[ROOT_PARENT] 表示这是根。 */
    val parentId: String,
    /** 同父兄弟间的版本号，0 起。 */
    val variantIndex: Int,
    /** 在**同父兄弟**里是否被选中（每个 parentId 下至多一条为 true）。 */
    val active: Boolean,
    /** user | assistant */
    val role: String,
    val content: String,
    /**
     * 附件（JSON 数组，见 [com.simplechat.app.data.Attachment]）。
     *
     * null / 空串 = 没有附件。图片以 base64 存在这里面，
     * 所以这一列可能很长 —— 读取时**不要**在列表查询里 select *，
     * 需要正文的场景才带上（见 `MessageDao`）。
     */
    val attachments: String? = null,
    /** reasoning_content，仅本地展示，构造请求时不回传（§11.1）。 */
    val reasoning: String? = null,
    /** DONE | STREAMING | STOPPED | ERROR */
    val status: String = "DONE",
    val errorMessage: String? = null,
    /**
     * 这条是哪个模型写的（`ModelInfo.id`）。
     *
     * 只有 **AI 回复**有值，用户消息恒为 null。
     * v3 及以前的老数据也是 null —— 那时根本没记，
     * 界面上就**不显示**，而不是拿当前模型猜一个填上去（那会是假信息）。
     */
    val model: String? = null,
    /**
     * **休眠字段**：早先的「不参与上下文」功能已撤下（§17.6），
     * 保留列只为不动 schema、不废掉老备份。
     */
    val excluded: Boolean = false,
    val thinkingMs: Long? = null,
    val ttftMs: Long? = null,
    val tps: Float? = null,
    @ColumnInfo(name = "tokens_in") val tokensIn: Int? = null,
    @ColumnInfo(name = "tokens_out") val tokensOut: Int? = null,
    val createdAt: Long,
) {
    companion object {
        /** 根消息的 parentId 哨兵。 */
        const val ROOT_PARENT = ""
    }
}

/**
 * 预设：**一段可命名的系统提示词**（§9.1.1①）。
 *
 * 刻意不做结构化字段、不兼容外部角色卡格式 —— 保持泛用性。
 */
@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val content: String,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 会话挂载的预设。多对多 + 顺序 + 启停。
 * [sortOrder] 有意义 —— 拼接顺序会影响模型对主次的判断。
 */
@Entity(
    tableName = "conversation_presets",
    primaryKeys = ["conversationId", "presetId"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PresetEntity::class,
            parentColumns = ["id"],
            childColumns = ["presetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ConversationPresetEntity(
    val conversationId: String,
    val presetId: String,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
)

/**
 * 上下文压缩摘要（§9.1.1③）。
 *
 * **只改变发给 API 的内容，绝不删除本地消息。**
 * 请求装配时以「前情提要」形式紧跟系统提示词之后。
 */
@Entity(
    tableName = "compactions",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["conversationId"])],
)
data class CompactionEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    /**
     * 摘要**覆盖到活跃路径上的哪一条**为止。
     *
     * 用消息 id 而不是位置：分支一切换，"第几条"就变了，
     * 而 id 是稳定的。若这个 id 已不在当前路径上，说明摘要是别的分支留下的，
     * 应当忽略（见 `orderedOutgoingMessages`）。
     */
    val throughMessageId: String,
    val summary: String,
    val createdAt: Long,
    val updatedAt: Long,
)
