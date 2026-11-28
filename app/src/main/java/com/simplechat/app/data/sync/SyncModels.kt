package com.simplechat.app.data.sync

import java.io.File
import kotlinx.serialization.Serializable

/**
 * 多端同步的本地基线与墓碑（docs/40 §50.7）。
 *
 * 全部是**派生数据**：丢了最多下次同步全量比对，绝不丢聊天内容 —— 因此放在
 * 本机 JSON 文件里，不升 Room 版本、不进备份。
 */
@Serializable
data class SyncBaseline(
    /** 上次成功同步时本地分片的内容哈希（SHA-256）。 */
    val localHash: String,
    /** 上次成功同步时远端的 ETag（服务器没给则为 null）。 */
    val remoteEtag: String?,
    /** 该会话在远端的文件名（`sha256(conversationId).json`）。 */
    val remoteFile: String,
    val syncedAtMillis: Long,
)

/** 一条"本机永久删除、尚未上传"的墓碑。 */
@Serializable
data class SyncTombstoneRecord(
    val conversationId: String,
    val remoteFile: String,
    val deletedAtMillis: Long,
)

@Serializable
data class SyncState(
    val version: Int = 1,
    val baselines: Map<String, SyncBaseline> = emptyMap(),
    val pendingTombstones: List<SyncTombstoneRecord> = emptyList(),
    val lastSyncAtMillis: Long = 0L,
) {
    companion object {
        const val CurrentVersion = 1
    }
}

/** 本地分片：活动会话导出后的文件与内容哈希。 */
data class LocalShard(
    val conversationId: String,
    val hash: String,
    val file: File,
)

/** 远端分片（PROPFIND 一行）；[conversationId] 由基线反查得出，新设备可能为 null。 */
data class RemoteShard(
    val fileName: String,
    val etag: String?,
    val conversationId: String?,
    val isDirectory: Boolean = false,
)

/** 远端墓碑文件。 */
data class RemoteTombstone(
    val fileName: String,
    val etag: String?,
    val conversationId: String?,
)

internal enum class PushReason {
    /** 远端没有（新会话或远端被删）。 */
    NEW,

    /** 本地在上次同步之后改过，远端没动 → 用 If-Match 覆盖基线。 */
    LOCAL_CHANGED,

    /** 远端墓碑先到，但本机有改动：删除让位于数据，把本机内容传上去。 */
    DELETE_WON,
}

internal sealed interface SyncAction {
    data class Push(
        val conversationId: String,
        val remoteFile: String,
        /** 覆盖已知基线时带；新建/覆盖未知远端时为 null。 */
        val ifMatch: String?,
        /** 首次创建时带 `If-None-Match: *`，防止覆盖别的设备刚上传的分片。 */
        val ifNoneMatch: Boolean,
        val reason: PushReason,
    ) : SyncAction

    /** 纯下载：本机没改，远端变了 → 直接应用。 */
    data class Pull(
        val conversationId: String,
        val remoteFile: String,
        val etag: String?,
    ) : SyncAction

    /** 下载后按 id 更新本地（远端内容未知哈希时，用作"先比再决定"）。 */
    data class PullCompare(
        val conversationId: String,
        val remoteFile: String,
        val etag: String?,
    ) : SyncAction

    /** 新设备：远端有本机从未见过的分片，下载后按 MERGE 导入。 */
    data class PullUnknown(
        val remoteFile: String,
        val etag: String?,
    ) : SyncAction

    /** 远端墓碑而本机未改：删除本机会话（进回收站还是永久删由仓库决定，MVP 永久删）。 */
    data class DeleteLocal(val conversationId: String) : SyncAction

    /** 上传墓碑并删除远端分片。 */
    data class PushTombstone(val conversationId: String, val remoteFile: String) : SyncAction

    data class DeleteRemote(val remoteFile: String) : SyncAction
}

internal enum class SyncConflictKind {
    /** 两台设备都改过同一条会话。 */
    BOTH_CHANGED,

    /** 别的设备删了、本机改过：保留本机并回传。 */
    DELETED_REMOTE_KEPT_LOCAL,
}

internal data class SyncConflict(val conversationId: String, val kind: SyncConflictKind)

internal data class SyncPlan(
    val actions: List<SyncAction>,
    val conflicts: List<SyncConflict>,
)

/**
 * 三向比较生成同步计划（纯函数，JVM 可测）。
 *
 * 输入与远端无关：网络、文件、仓库都在协调器里；这里只回答"该做什么"。
 *
 * @param active 活动会话的本地分片（回收站里的会话不参与同步）
 * @param localIds 本机**全部**会话 id（含回收站）—— 用来判断基线里的会话
 *   到底是"永久删除"还是"只是进了回收站"
 * @param trashedIds 回收站中的会话 id
 * @param remote 远端 `conversations/` 目录里的分片
 * @param remoteTombstones 远端 `tombstones/` 目录里的墓碑
 */
internal fun planSync(
    active: List<LocalShard>,
    localIds: Set<String>,
    trashedIds: Set<String>,
    remote: List<RemoteShard>,
    remoteTombstones: List<RemoteTombstone>,
    state: SyncState,
): SyncPlan {
    val actions = mutableListOf<SyncAction>()
    val conflicts = mutableListOf<SyncConflict>()
    val activeById = active.associateBy { it.conversationId }

    // ① 本机待上传的墓碑：上传墓碑 + 删除远端分片。
    val pendingIds = state.pendingTombstones.mapTo(HashSet()) { it.conversationId }
    state.pendingTombstones.forEach { tombstone ->
        actions += SyncAction.PushTombstone(tombstone.conversationId, tombstone.remoteFile)
        if (remote.any { it.fileName == tombstone.remoteFile }) {
            actions += SyncAction.DeleteRemote(tombstone.remoteFile)
        }
    }

    // ② 基线里在本机彻底消失（且不在回收站）的会话 → 传播删除。
    state.baselines.forEach { (id, baseline) ->
        if (id !in localIds && id !in pendingIds && id !in trashedIds) {
            actions += SyncAction.PushTombstone(id, baseline.remoteFile)
            if (remote.any { it.fileName == baseline.remoteFile }) {
                actions += SyncAction.DeleteRemote(baseline.remoteFile)
            }
        }
    }

    // ③ 远端墓碑：删除让位于数据。
    val tombstonedIds = remoteTombstones.mapNotNullTo(HashSet()) { it.conversationId }
    for (id in tombstonedIds) {
        val local = activeById[id] ?: continue
        if (id in trashedIds) continue
        val baseline = state.baselines[id]
        val localChanged = baseline == null || local.hash != baseline.localHash
        if (localChanged) {
            // 本机有改动：保留本机内容并回传，冲突记一笔。
            actions += SyncAction.Push(
                conversationId = id,
                remoteFile = davFileName(id),
                ifMatch = baseline?.remoteEtag,
                ifNoneMatch = false,
                reason = PushReason.DELETE_WON,
            )
            conflicts += SyncConflict(id, SyncConflictKind.DELETED_REMOTE_KEPT_LOCAL)
        } else {
            actions += SyncAction.DeleteLocal(id)
        }
    }

    // ④ 本地活动会话 vs 远端分片。
    val remoteByFile = remote.associateBy { it.fileName }
    val remoteByConversation = remote.mapNotNull { entry ->
        entry.conversationId?.let { it to entry }
    }.toMap()
    active.filter { it.conversationId !in tombstonedIds }.forEach { local ->
        val id = local.conversationId
        if (id in pendingIds) return@forEach
        val fileName = davFileName(id)
        val baseline = state.baselines[id]
        val remoteEntry = remoteByFile[fileName] ?: remoteByConversation[id]
        if (remoteEntry == null) {
            // 远端没有：新会话直接建；基线在但远端没了 → 视为"远端被删"仍回传本机。
            actions += SyncAction.Push(
                conversationId = id,
                remoteFile = fileName,
                ifMatch = baseline?.remoteEtag,
                ifNoneMatch = baseline == null,
                reason = if (baseline == null) PushReason.NEW else PushReason.LOCAL_CHANGED,
            )
            return@forEach
        }
        if (baseline == null) {
            // 两边都有、从没同步过：先下载比内容；不覆盖任何一边，记冲突。
            actions += SyncAction.PullCompare(id, remoteEntry.fileName, remoteEntry.etag)
            conflicts += SyncConflict(id, SyncConflictKind.BOTH_CHANGED)
            return@forEach
        }
        val localChanged = local.hash != baseline.localHash
        val remoteChanged = remoteEntry.etag == null || remoteEntry.etag != baseline.remoteEtag
        when {
            !localChanged && !remoteChanged -> Unit
            localChanged && !remoteChanged -> actions += SyncAction.Push(
                conversationId = id,
                remoteFile = remoteEntry.fileName,
                ifMatch = baseline.remoteEtag,
                ifNoneMatch = false,
                reason = PushReason.LOCAL_CHANGED,
            )
            !localChanged && remoteChanged -> actions += SyncAction.Pull(
                conversationId = id,
                remoteFile = remoteEntry.fileName,
                etag = remoteEntry.etag,
            )
            else -> {
                actions += SyncAction.PullCompare(id, remoteEntry.fileName, remoteEntry.etag)
                conflicts += SyncConflict(id, SyncConflictKind.BOTH_CHANGED)
            }
        }
    }

    // ⑤ 本机从未见过的远端分片 → 新设备首次同步。
    val baselineFiles = state.baselines.values.mapTo(HashSet()) { it.remoteFile }
    val localFiles = active.mapTo(HashSet()) { davFileName(it.conversationId) }
    remote.forEach { entry ->
        if (entry.isDirectory) return@forEach
        if (entry.fileName in baselineFiles || entry.fileName in localFiles) return@forEach
        if (entry.conversationId != null && entry.conversationId in localIds) return@forEach
        actions += SyncAction.PullUnknown(entry.fileName, entry.etag)
    }

    return SyncPlan(actions, conflicts)
}

/** 会话 id → 远端分片文件名（与 [com.simplechat.app.net.davShardFileName] 同一规则）。 */
internal fun davFileName(conversationId: String): String = com.simplechat.app.net.davShardFileName(conversationId)
