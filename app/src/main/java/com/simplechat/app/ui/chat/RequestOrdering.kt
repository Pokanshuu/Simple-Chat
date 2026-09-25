package com.simplechat.app.ui.chat

import com.simplechat.app.db.CompactionEntity

/**
 * 从**活跃路径**与压缩状态算出实际发出去的消息序列。
 *
 * 刻意抽成纯函数：这一层错了只会表现为"模型答非所问"，从现象上几乎不可能定位。
 *
 * 路径本身已经有序（`MessageTree.activePath` 走出来的），所以这里只做筛选：
 *
 * 1. 跳过流式未定稿的，以及**既没有正文、也没有附件**的
 * 2. 被压缩覆盖的跳过 —— 摘要里已经有它们了
 * 3. **首条必须是 user** —— 以 assistant 开头等于"一个没有问题的回答"
 */
internal fun orderedOutgoingMessages(
    path: List<UiMessage>,
    compaction: CompactionEntity?,
): List<UiMessage> {
    // 摘要覆盖到路径上的哪一条；找不到说明摘要是别的分支留下的，直接忽略
    val coveredIndex = compaction
        ?.let { c -> path.indexOfFirst { it.id == c.throughMessageId } }
        ?: -1

    val filtered = path
        .filter { it.status != MessageStatus.STREAMING }
        /*
         * ⚠️ 判据是"有没有东西可发"，不是"正文是否非空"。
         *
         * 只挂了附件、没打字的用户消息（"你看看这个"的最自然写法）正文是空的 ——
         * 只按 content 判会被整条丢掉，请求里只剩下一串 assistant，
         * 服务端直接回 `Empty input messages`。
         */
        .filter { it.content.isNotBlank() || it.attachments.isNotEmpty() }
        .filterIndexed { index, _ -> coveredIndex < 0 || index > coveredIndex }

    val firstUser = filtered.indexOfFirst { it.role == MessageRole.USER }
    return when {
        firstUser < 0 -> emptyList()
        firstUser == 0 -> filtered
        else -> filtered.drop(firstUser)
    }
}
