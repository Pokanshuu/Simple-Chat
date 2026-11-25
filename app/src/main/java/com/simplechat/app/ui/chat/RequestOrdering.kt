package com.simplechat.app.ui.chat

import com.simplechat.app.db.CompactionEntity

/**
 * 当前路径上真正生效的摘要。
 *
 * **空摘要不算压缩**：否则用户手工清空摘要后，覆盖范围内的原文会被丢掉，
 * 而请求里又因为没有摘要可注入而不带任何前情 —— 等于静默丢了整段上下文。
 */
internal fun validCompaction(path: List<UiMessage>, compaction: CompactionEntity?): CompactionEntity? =
    compaction?.takeIf { summary ->
        summary.summary.isNotBlank() && path.any { it.id == summary.throughMessageId }
    }

/**
 * 从同一会话可能留下的多份摘要中挑出**当前路径生效**的那一份。
 *
 * 切换分支、重新生成都会留下覆盖不同路径的摘要行。只认 boundary 在当前路径上的，
 * 取覆盖最远的一份；覆盖一样远时取最近更新的（防止旧摘要盖住新摘要）。
 */
internal fun selectCompaction(
    path: List<UiMessage>,
    candidates: List<CompactionEntity>,
): CompactionEntity? = candidates
    .mapNotNull { candidate ->
        val index = path.indexOfFirst { it.id == candidate.throughMessageId }
        if (index >= 0 && candidate.summary.isNotBlank()) candidate to index else null
    }
    .maxWithOrNull(compareBy<Pair<CompactionEntity, Int>> { it.second }.thenBy { it.first.updatedAt })
    ?.first

internal fun compactionInvalidatedByEdit(
    path: List<UiMessage>,
    compaction: CompactionEntity?,
    messageId: String,
): Boolean {
    val boundary = compaction?.let { summary -> path.indexOfFirst { it.id == summary.throughMessageId } } ?: -1
    val edited = path.indexOfFirst { it.id == messageId }
    return boundary >= 0 && edited in 0..boundary
}

internal fun modelSwitchedFrom(path: List<UiMessage>, selectedModel: String): String? =
    path.lastOrNull { it.role == MessageRole.ASSISTANT && !it.streaming && it.content.isNotBlank() && !it.model.isNullOrBlank() }
        ?.model?.takeIf { it != selectedModel }

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
    // 摘要覆盖到路径上的哪一条；找不到说明摘要是别的分支留下的，直接忽略。
    // 空摘要也不算压缩：否则原文被丢掉、又没有前情可发。
    val coveredIndex = compaction
        ?.takeIf { it.summary.isNotBlank() }
        ?.let { c -> path.indexOfFirst { it.id == c.throughMessageId } }
        ?: -1

    val filtered = path
        .filterIndexed { index, _ -> coveredIndex < 0 || index > coveredIndex }
        .filter { !it.deletedNavigation && it.status != MessageStatus.STREAMING }
        /*
         * ⚠️ 判据是"有没有东西可发"，不是"正文是否非空"。
         *
         * 只挂了附件、没打字的用户消息（"你看看这个"的最自然写法）正文是空的 ——
         * 只按 content 判会被整条丢掉，请求里只剩下一串 assistant，
         * 服务端直接回 `Empty input messages`。
         */
        .filter { it.content.isNotBlank() || it.attachments.isNotEmpty() }

    val firstUser = filtered.indexOfFirst { it.role == MessageRole.USER }
    return when {
        firstUser < 0 -> emptyList()
        firstUser == 0 -> filtered
        else -> filtered.drop(firstUser)
    }
}
