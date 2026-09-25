package com.simplechat.app.db

/**
 * 消息树的纯函数。
 *
 * 刻意不做成递归 SQL：会话规模就几百条，一次读回来在内存里走更简单 ——
 * 可读、可测、不受 SQLite 方言影响。抽成纯函数是为了能直接单测
 * （见 `MessageTreeTest`），树的行走逻辑一旦错就是"顺序全乱"这种极难查的问题。
 */
object MessageTree {

    /**
     * 当前对话 = 从根沿 `active` 一路走到底的那条路径。
     *
     * 遇到环就停（数据损坏时的防御，正常不会出现）——
     * 这是个会用在 UI 线程上的函数，宁可少显示也不能死循环。
     */
    fun activePath(all: List<MessageEntity>): List<MessageEntity> {
        val byParent = all.groupBy { it.parentId }
        val path = ArrayList<MessageEntity>()
        val visited = HashSet<String>()

        var parentId: String = MessageEntity.ROOT_PARENT
        while (true) {
            val next = byParent[parentId]?.firstOrNull { it.active } ?: break
            if (!visited.add(next.id)) break
            path += next
            parentId = next.id
        }
        return path
    }

    /** `parentId` → 兄弟数量。用于渲染 `‹ n/m ›`。 */
    fun siblingCounts(all: List<MessageEntity>): Map<String, Int> =
        siblingCountsOf(all.map { it.parentId })

    /**
     * 同上，但只吃 `parentId` 列表。
     *
     * 给数据库用：数版本号不需要把整条消息（可能带着图片 base64）读出来。
     */
    fun siblingCountsOf(parentIds: List<String>): Map<String, Int> =
        parentIds.groupingBy { it }.eachCount()

    /**
     * 某节点**整棵子树**（含自己）的 id 列表。
     *
     * 删除一条消息 = 删它的子树：下游都是基于它生成的，
     * 留着一个"没有前文的下文"没有任何意义。
     */
    fun subtreeIds(all: List<MessageEntity>, rootId: String): List<String> {
        val childrenByParent = all.groupBy { it.parentId }
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>().apply { addLast(rootId) }

        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!seen.add(id)) continue
            out += id
            childrenByParent[id]?.forEach { stack.addLast(it.id) }
        }
        return out
    }

    /** 某个父节点下，`variantIndex` 最大的那个兄弟（删除后用来接管 active）。 */
    fun lastSibling(all: List<MessageEntity>, parentId: String): MessageEntity? =
        all.filter { it.parentId == parentId }.maxByOrNull { it.variantIndex }

    /**
     * 活跃路径上**到 [messageId] 为止**（含）的那一段。
     *
     * 供「从这里新建对话」用：把那一段复制成另一个会话（§21）。
     * 目标不在当前路径上（理论上不可能，菜单只挂在当前列表上）时返回空列表。
     */
    fun pathTo(all: List<MessageEntity>, messageId: String): List<MessageEntity> {
        val path = activePath(all)
        val end = path.indexOfFirst { it.id == messageId }
        return if (end < 0) emptyList() else path.subList(0, end + 1).toList()
    }

    /**
     * 把一段路径复制成**另一个会话**的消息：id 与 `parentId` 全部重挂。
     *
     * 抽成纯函数是为了能单测 —— 这条链错一处，表现就是"打开新会话一片空白"，
     * 从界面上几乎看不出是哪一环断的。
     *
     * - 每条都取一个新 id（沿用旧 id 会撞主键）
     * - `parentId` 指向**新链上的前一条**，首条指向 [MessageEntity.ROOT_PARENT]
     * - `variantIndex` 归零、`active` 置真 —— 新会话里全是单版本
     *   （只复制当前选中的那一版，不搬兄弟，见 §21.4）
     */
    fun forkMessages(
        path: List<MessageEntity>,
        conversationId: String,
        newId: () -> String,
    ): List<MessageEntity> {
        val out = ArrayList<MessageEntity>(path.size)
        var parentId: String = MessageEntity.ROOT_PARENT

        for (source in path) {
            val id = newId()
            out += source.copy(
                id = id,
                conversationId = conversationId,
                parentId = parentId,
                variantIndex = 0,
                active = true,
            )
            parentId = id
        }
        return out
    }
}
