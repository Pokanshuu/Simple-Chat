package com.simplechat.app.db

/**
 * 消息树的纯函数。
 *
 * 刻意不做成递归 SQL：会话规模就几百条，一次读回来在内存里走更简单 ——
 * 可读、可测、不受 SQLite 方言影响。抽成纯函数是为了能直接单测
 * （见 `MessageTreeTest`），树的行走逻辑一旦错就是"顺序全乱"这种极难查的问题。
 */
object MessageTree {

    data class DeletionPlan(
        val deletedIds: Set<String>,
        val promoted: List<MessageLight>,
        val activateExisting: Set<String>,
        val clearActiveChildren: Set<String>,
        val replacementId: String?,
    )

    /** Root-to-target ancestry, including inactive branches. Invalid chains cannot be activated. */
    fun ancestry(all: List<MessageLight>, targetId: String): List<MessageLight> {
        val byId = all.associateBy { it.id }
        val path = ArrayList<MessageLight>()
        val seen = HashSet<String>()
        var id = targetId
        while (id != MessageEntity.ROOT_PARENT) {
            val node = byId[id] ?: return emptyList()
            if (!seen.add(id)) return emptyList()
            path += node
            id = node.parentId
        }
        path.reverse()
        return path
    }

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
     * 同 [activePath]，但只吃**骨架**（[MessageLight]）并只返回 id。
     *
     * 打开长会话时先读骨架（每行几十字节）走出路径，再按 id 只取这条路径的正文 ——
     * 被放弃的分支因此一次都不会被读出来（见 `ChatRepository.loadActivePath`）。
     */
    fun activePathIds(all: List<MessageLight>): List<String> {
        val byParent = all.groupBy { it.parentId }
        val path = ArrayList<String>()
        val visited = HashSet<String>()

        var parentId: String = MessageEntity.ROOT_PARENT
        while (true) {
            val next = byParent[parentId]?.firstOrNull { it.active } ?: break
            if (!visited.add(next.id)) break
            path += next.id
            parentId = next.id
        }
        return path
    }

    /**
     * 同上，但只吃 `parentId` 列表。
     *
     * 给数据库用：数版本号不需要把整条消息（可能带着图片 base64）读出来。
     */
    fun siblingCountsOf(parentIds: List<String>): Map<String, Int> =
        parentIds.groupingBy { it }.eachCount()

    /** Include hidden ancestors only while they still lead to a visible message. */
    fun reachable(all: List<MessageLight>, includeTrash: Boolean = false): Set<String> {
        val byId = all.associateBy { it.id }
        val reachable = HashSet<String>()
        for (row in all) {
            if (!row.visible && !(includeTrash && row.trashedAt != null)) continue
            var node: MessageLight? = row
            while (node != null && reachable.add(node.id)) node = byId[node.parentId]
        }
        return reachable
    }

    fun navigable(all: List<MessageLight>): List<MessageLight> {
        val reachable = reachable(all)
        return all.filter { it.id in reachable }
    }

    /** Remove selected message versions and splice their direct surviving continuations into place. */
    fun deletionPlan(
        all: List<MessageLight>,
        selectedIds: Set<String>,
        allVersions: Boolean,
    ): DeletionPlan {
        val selectedNodes = all.filter { it.id in selectedIds }
        val deletedIds = if (allVersions) {
            val selectedGroups = selectedNodes.mapTo(HashSet()) { it.versionGroup ?: it.parentId }
            all.filter { (it.versionGroup ?: it.parentId) in selectedGroups }.mapTo(LinkedHashSet()) { it.id }
        } else selectedNodes.mapTo(LinkedHashSet()) { it.id }
        val childrenByParent = all.sortedBy { it.variantIndex }.groupBy { it.parentId }

        // Replacing the visible version must keep both its siblings and the current continuation visible.
        val deletedVersion = selectedNodes.singleOrNull()?.takeIf { !allVersions && it.active }
        val replacement = deletedVersion?.let { target ->
            val versions = childrenByParent[target.parentId].orEmpty().filter {
                it.id !in deletedIds && (it.versionGroup ?: it.parentId) == (target.versionGroup ?: target.parentId)
            }
            versions.lastOrNull { it.variantIndex < target.variantIndex } ?: versions.firstOrNull()
        }

        val boundaryByParent = LinkedHashMap<String, MutableList<Pair<MessageLight, Boolean>>>()
        val activeParents = LinkedHashSet<String>()
        val visited = HashSet<String>()
        val roots = all.filter { it.id in deletedIds && it.parentId !in deletedIds }.sortedBy { it.variantIndex }
        roots.forEach { root ->
            if (root.active) activeParents += root.parentId
            val destination = replacement?.id ?: root.parentId
            val pending = ArrayDeque<Pair<MessageLight, Boolean>>()
            pending.addLast(root to root.active)
            while (pending.isNotEmpty()) {
                val (node, onPath) = pending.removeLast()
                if (!visited.add(node.id)) continue
                if (node.id !in deletedIds) {
                    boundaryByParent.getOrPut(destination) { mutableListOf() } += node to onPath
                    continue
                }
                childrenByParent[node.id].orEmpty().asReversed().forEach { child ->
                    pending.addLast(child to (onPath && child.active))
                }
            }
        }

        val promoted = mutableListOf<MessageLight>()
        val activateExisting = mutableSetOf<String>()
        val clearActiveChildren = mutableSetOf<String>()
        replacement?.let { activateExisting += it.id }
        (roots.map { it.parentId } + boundaryByParent.keys).distinct().forEach { parentId ->
            val candidates = boundaryByParent[parentId].orEmpty()
            val survivingSibling = childrenByParent[parentId].orEmpty().lastOrNull { it.id !in deletedIds }
            val firstIndex = (survivingSibling?.variantIndex ?: -1) + 1
            var activeCandidate = candidates.firstOrNull { it.second }?.first
            if (parentId in activeParents && activeCandidate == null && replacement == null) {
                if (survivingSibling != null) activateExisting += survivingSibling.id
                else activeCandidate = (candidates.firstOrNull { it.first.active } ?: candidates.firstOrNull())?.first
            }
            if (parentId == replacement?.id && activeCandidate != null) clearActiveChildren += parentId
            candidates.forEachIndexed { index, (message, _) ->
                promoted += message.copy(
                    parentId = parentId,
                    variantIndex = firstIndex + index,
                    active = message.id == activeCandidate?.id,
                    versionGroup = message.versionGroup ?: message.parentId,
                )
            }
        }
        return DeletionPlan(deletedIds, promoted, activateExisting, clearActiveChildren, replacement?.id)
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
                versionGroup = null,
                variantIndex = 0,
                active = true,
            )
            parentId = id
        }
        return out
    }
}
