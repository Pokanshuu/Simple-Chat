package com.simplechat.app.data.import

import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.BackupMessage
import com.simplechat.app.data.BackupPreset

internal interface ImportSessionSource {
    val expected: Int?
    val indexVerified: Boolean?
    val presets: List<BackupPreset> get() = emptyList()
    suspend fun forEachSession(block: suspend (ImportSession) -> Unit)
}

/**
 * 一条**边读边写**的会话：头部（会话本身）+ 一个"逐条产出消息"的入口。
 *
 * ### 为什么不是 `BackupConversation`
 *
 * 常规备份（本应用的 JSON、Chatbox 的 ZIP）一个会话就是几百万字节里的一小段，
 * 整条建进内存没问题。而 DeepSeek 导出的单条会话可以几十 MB（长篇创作 + 大量分支），
 * 把它整条建成 `List<BackupMessage>` 再写库，峰值内存就回到"导入不了"的老路上。
 *
 * 所以这里把"消息"做成**逐条产出**：调用方在事务里 `forEachMessage { 写一条 }`，
 * 内存里只留当前这一条。产出过程需要重新读 ZIP 里的会话条目，
 * 因此必须在 `ZipFile` 关闭之前消费完。
 */
internal class ImportSession(
    /** 会话本身（`messages` 恒为空 —— 消息不在这里）。 */
    val header: BackupConversation,
    val expectedMessages: Int? = null,
    val repairedMessages: Int = 0,
    val discard: () -> Unit = {},
    private val emitMessages: suspend (sink: suspend (BackupMessage) -> Unit) -> Unit,
) {
    /**
     * 逐条产出消息。`sink` 里可以执行 `suspend` 的写库操作。
     *
     * ⚠️ 只能调用一次，且必须在 `ZipFile` 关闭之前完成。
     */
    suspend fun forEachMessage(sink: suspend (BackupMessage) -> Unit) = emitMessages(sink)
}
