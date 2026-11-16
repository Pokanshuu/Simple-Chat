package com.simplechat.app.data

import androidx.annotation.StringRes
import com.simplechat.app.R
import kotlinx.serialization.Serializable

/**
 * 一次导入的**对账结果**。
 *
 * ### 为什么要有"对账"，而不是只回一个"导入了多少条"
 *
 * 导入是唯一一种"用户拿一份外部文件、期望本地变成它的样子"的操作。
 * 只要有任何一条没进来，用户看到的就是"我的数据少了" —— 而旧实现里，
 * 解析失败/超限/坏条目全是**静默跳过**：结果页照样说"导入完成"，
 * 用户只能凭记忆发现少了东西（v1.1.3 之前多位用户的真实反馈）。
 *
 * 所以这里同时记三件事：
 *
 * 1. **归档声明了多少**（[expected]）—— 与结果页上"导入了几条"一对照，
 *    "有没有丢"立刻可见，不需要用户去数；
 * 2. **哪些没进来、为什么**（[failures]）—— 让用户能判断是"重下文件"还是"换个导出"；
 * 3. **顺手丢了多少张图**（[droppedImages]）—— 图片缺失最容易被忽略。
 *
 * 这是导入链路的**底线**：宁可如实说"5 个里进来了 4 个，那 1 个文件损坏"，
 * 也不能说"导入完成"然后少一条。
 */
@Serializable
data class ImportReport(
    /** 真正写进库的会话数。 */
    val conversations: Int,
    /** 写进库的消息数。 */
    val messages: Int,
    val presets: Int,
    /** 归档里有、但本地已经存在（按导入策略跳过）的会话数。 */
    val alreadyPresent: Int,
    /**
     * 归档**声明**的会话数；数不出来时为 null（例如单文件 Chatbox JSON 没写总数）。
     *
     * 用户看到的 "4/5 个" 就是它 —— 这个数字比任何一句"已完成"都诚实。
     */
    val expected: Int? = null,
    /** 没能导入的条目，带原因。 */
    val failures: List<ImportFailure> = emptyList(),
    /** 取不回来的图片张数（归档里没有原件 / 读取失败）。 */
    val droppedImages: Int = 0,
    val repairedMessages: Int = 0,
    val indexVerified: Boolean? = null,
    val sessions: List<ImportedSessionAudit> = emptyList(),
) {
    /** 因为"读不下来 / 写不进去"而丢掉的会话数。 */
    val failed: Int get() = failures.size
    val complete: Boolean get() = failed == 0 && droppedImages == 0 && indexVerified != false &&
        (expected == null || expected == conversations + alreadyPresent)
}

@Serializable
data class ImportedSessionAudit(
    val sourceId: String,
    val targetId: String?,
    val expectedMessages: Int?,
    val writtenMessages: Int,
    val status: String,
)

/** 一条没能导入的会话（或图片所属的会话）。[reference] 是归档里的会话 id，用于排查。 */
@Serializable
data class ImportFailure(
    /** 归档索引里写着的标题，读不到会话文件时也能告诉用户"少了哪个"。 */
    val title: String?,
    val reference: String?,
    val reason: Reason,
    val detail: String? = null,
) {
    @Serializable
    enum class Reason {
        /** 会话 JSON 超过上限。 */
        TOO_LARGE,

        /** 读不出来 / JSON 损坏 / 解析时内存不够。 */
        UNREADABLE,

        /** 会话里没有消息。 */
        EMPTY,

        /** 消息树全是断链，一条能显示的都没有。 */
        BROKEN_TREE,

        /** 归档索引里有、但归档里根本没有这条会话的文件。 */
        MISSING_ENTRY,

        /** 解析成功、写库失败（单条坏数据被隔离出来，不连累其它会话）。 */
        WRITE_FAILED,
        INSUFFICIENT_SPACE,
        INSUFFICIENT_MEMORY,
    }
}

/** 失败原因给用户看的说法。数据层只带资源 ID，落成哪个语言由界面层决定（同 `AppText`）。 */
@StringRes
internal fun ImportFailure.Reason.labelRes(): Int = when (this) {
    ImportFailure.Reason.TOO_LARGE -> R.string.import_fail_too_large
    ImportFailure.Reason.UNREADABLE -> R.string.import_fail_unreadable
    ImportFailure.Reason.EMPTY -> R.string.import_fail_empty
    ImportFailure.Reason.BROKEN_TREE -> R.string.import_fail_broken_tree
    ImportFailure.Reason.MISSING_ENTRY -> R.string.import_fail_missing_entry
    ImportFailure.Reason.WRITE_FAILED -> R.string.import_fail_write_failed
    ImportFailure.Reason.INSUFFICIENT_SPACE -> R.string.import_fail_space
    ImportFailure.Reason.INSUFFICIENT_MEMORY -> R.string.import_fail_memory
}
