package com.simplechat.app.data.sync

import android.content.Context
import android.os.Build
import com.simplechat.app.R
import com.simplechat.app.data.AppText
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.ImportMode
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.net.WebDavClient
import com.simplechat.app.net.WebDavException
import com.simplechat.app.net.WebDavFailure
import com.simplechat.app.net.davShardFileName
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 云同步协调器（docs/40 §50.8.1）。
 *
 * 职责：读配置 → 导出/哈希本地分片 → 列远端 → 纯计划器 [planSync] → 执行动作 →
 * 写回基线与墓碑 → 产出一份 [SyncReport]。
 *
 * 不做的事：不在启动时偷偷同步（只在用户点「立即同步」时跑）；不在删除路径发网络
 * 请求（墓碑靠"基线里有、本地没了"在下次同步时推导）；不引 WorkManager。
 */
class SyncCoordinator(
    private val context: Context,
    private val repository: ChatRepository,
    private val settingsStore: SettingsStore,
) {
    sealed interface Status {
        data object Idle : Status
        data class Running(val progress: Float) : Status
        data class Done(val report: SyncReport) : Status
        data class Failed(val text: AppText) : Status
    }

    data class SyncReport(
        val pushed: Int = 0,
        val pulled: Int = 0,
        val deleted: Int = 0,
        val conflicts: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val conflictConversations: List<String> = emptyList(),
        /** 部分失败时保留一句人话；整体失败由 [Status.Failed] 承担。 */
        val failureText: AppText? = null,
    ) {
        val ok: Boolean get() = failed == 0 && failureText == null
    }

    private val store = SyncStore(File(context.filesDir, "sync"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var job: Job? = null

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    /** UI 触发：互斥、后台执行，结果写进 [status]。 */
    fun syncAsync() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                syncNow()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _status.value = Status.Failed(error.toAppText())
            }
        }
    }

    /** 直接执行一次同步（串行）。异常会转成 [Status.Failed] 并向上抛给调用方。 */
    suspend fun syncNow(): SyncReport = mutex.withLock {
        _status.value = Status.Running(0f)
        try {
            val report = runSync()
            _status.value = Status.Done(report)
            report
        } catch (error: CancellationException) {
            _status.value = Status.Idle
            throw error
        } catch (error: Throwable) {
            _status.value = Status.Failed(error.toAppText())
            throw error
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** 退出同步：清账号 + 本机同步状态；聊天数据一条不动。 */
    suspend fun clearConfig() {
        mutex.withLock {
            settingsStore.clearSyncConfig()
            store.clear()
            _status.value = Status.Idle
        }
    }

    // ── 同步主流程 ───────────────────────────────────────

    private suspend fun runSync(): SyncReport {
        val config = settingsStore.flow.first()
        val password = settingsStore.readSyncPassword()
        if (config.syncBaseUrl.isBlank() || config.syncUsername.isBlank() || password.isBlank()) {
            throw AppTextException(R.string.sync_not_configured)
        }
        val (deviceId, deviceName) = settingsStore.ensureSyncDevice(defaultDeviceName())
        if (!config.syncEnabled) settingsStore.setSyncEnabled(true)

        val client = WebDavClient(
            WebDavClient.WebDavConfig(
                baseUrl = config.syncBaseUrl,
                username = config.syncUsername,
                password = password,
                rootDir = config.syncRootDir,
            ),
        )
        val root = client.rootPath
        val conversationsDir = "$root/conversations"
        val tombstonesDir = "$root/tombstones"
        val devicesDir = "$root/devices"
        client.ensureCollections(root, conversationsDir, tombstonesDir, devicesDir)

        val state = store.read()
        val shardDir = store.cacheDir()
        shardDir.deleteRecursively()
        shardDir.mkdirs()

        // ① 本地：活动会话导出为分片并计算内容哈希（回收站不参与同步）。
        val localInfo = repository.syncConversationInfo()
        val active = localInfo.filterNot { it.trashed }
        val localShards = ArrayList<LocalShard>(active.size)
        active.forEachIndexed { index, info ->
            val file = File(shardDir, davShardFileName(info.id))
            repository.exportSyncShard(info.id, file) { progress ->
                _status.value = Status.Running(0.4f * (index + progress) / active.size.coerceAtLeast(1))
            }
            localShards += LocalShard(info.id, SyncStore.sha256(file), file)
        }
        _status.value = Status.Running(0.4f)

        // ② 远端：列出分片与墓碑；用基线与本地文件名把哈希文件名反查回会话 id。
        val remoteFiles = client.list(conversationsDir).filterNot { it.isDirectory }
        val remoteTombFiles = client.list(tombstonesDir).filterNot { it.isDirectory }
        val nameToId = buildMap {
            state.baselines.forEach { (id, baseline) -> put(baseline.remoteFile, id) }
            localInfo.forEach { put(davShardFileName(it.id), it.id) }
        }
        val remote = remoteFiles.map {
            RemoteShard(it.path, it.etag, nameToId[it.path.substringAfterLast('/')], it.isDirectory)
        }
        val remoteTombstones = remoteTombFiles.map {
            RemoteTombstone(it.path, it.etag, nameToId[it.path.substringAfterLast('/')])
        }
        val plan = planSync(
            active = localShards,
            localIds = localInfo.mapTo(mutableSetOf()) { it.id },
            trashedIds = localInfo.filter { it.trashed }.mapTo(mutableSetOf()) { it.id },
            remote = remote,
            remoteTombstones = remoteTombstones,
            state = state,
        )
        _status.value = Status.Running(0.45f)

        // ③ 执行计划。
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        var pushed = 0
        var pulled = 0
        var deleted = 0
        var conflicts = plan.conflicts.size
        var failed = 0
        var skipped = 0
        var firstFailure: AppText? = null
        val baselines = state.baselines.toMutableMap()
        val pending = state.pendingTombstones.toMutableList()
        val conflictIds = plan.conflicts.mapTo(mutableSetOf()) { it.conversationId }
        val totalActions = plan.actions.size.coerceAtLeast(1)
        val localById = localShards.associateBy { it.conversationId }

        plan.actions.forEachIndexed { actionIndex, action ->
            _status.value = Status.Running(0.45f + 0.5f * actionIndex / totalActions)
            val now = System.currentTimeMillis()
            try {
                when (action) {
                    is SyncAction.Push -> {
                        val shard = localById[action.conversationId] ?: return@forEachIndexed
                        val etag = client.put(
                            path = "$conversationsDir/${action.remoteFile}",
                            file = shard.file,
                            ifMatch = action.ifMatch,
                            ifNoneMatch = action.ifNoneMatch,
                        )
                        baselines[action.conversationId] = SyncBaseline(
                            localHash = shard.hash,
                            remoteEtag = etag,
                            remoteFile = action.remoteFile,
                            syncedAtMillis = now,
                        )
                        pushed++
                    }

                    is SyncAction.Pull -> {
                        val etag = pullAndApply(
                            client = client,
                            path = "$conversationsDir/${action.remoteFile}",
                            conversationId = action.conversationId,
                            mode = ImportMode.SYNC_APPLY,
                            shardDir = shardDir,
                            expectedEtag = action.etag,
                        )
                        val baselineEtag = etag ?: baselines[action.conversationId]?.remoteEtag
                        if (baselineEtag != null) {
                            baselines[action.conversationId] = SyncBaseline(
                                localHash = hashOfReexported(action.conversationId, shardDir),
                                remoteEtag = baselineEtag,
                                remoteFile = action.remoteFile,
                                syncedAtMillis = now,
                            )
                            pulled++
                        }
                    }

                    is SyncAction.PullCompare -> {
                        val local = localById[action.conversationId]
                        val remoteHash = downloadAndHash(
                            client, "$conversationsDir/${action.remoteFile}", shardDir, action.remoteFile,
                        )
                        val localHash = local?.hash
                        if (localHash != null && localHash == remoteHash) {
                            // 内容其实一样（两端独立导出了同一份数据）：只更新基线。
                            baselines[action.conversationId] = SyncBaseline(
                                localHash = localHash,
                                remoteEtag = action.etag,
                                remoteFile = action.remoteFile,
                                syncedAtMillis = now,
                            )
                            skipped++
                        } else {
                            // 双改：远端另存成本地副本（RENAME 保证不覆盖），本机内容回传。
                            pullAndApply(
                                client = client,
                                path = "$conversationsDir/${action.remoteFile}",
                                conversationId = action.conversationId,
                                mode = ImportMode.RENAME,
                                shardDir = shardDir,
                                expectedEtag = action.etag,
                            )
                            pulled++
                            if (local != null) {
                                val etag = client.put(
                                    path = "$conversationsDir/${action.remoteFile}",
                                    file = local.file,
                                    ifMatch = baselines[action.conversationId]?.remoteEtag,
                                    ifNoneMatch = baselines[action.conversationId] == null,
                                )
                                baselines[action.conversationId] = SyncBaseline(
                                    localHash = local.hash,
                                    remoteEtag = etag,
                                    remoteFile = action.remoteFile,
                                    syncedAtMillis = now,
                                )
                                pushed++
                            }
                            conflictIds += action.conversationId
                            conflicts = maxOf(conflicts, conflictIds.size)
                        }
                    }

                    is SyncAction.PullUnknown -> {
                        val temporary = File(shardDir, "unknown-${action.remoteFile}")
                        client.get("$conversationsDir/${action.remoteFile}", temporary)
                        val id = conversationIdOf(temporary, json)
                        if (id == null) {
                            failed++
                            skipped++
                        } else {
                            pullAndApply(
                                client = client,
                                path = "$conversationsDir/${action.remoteFile}",
                                conversationId = id,
                                mode = ImportMode.MERGE,
                                shardDir = shardDir,
                                expectedEtag = action.etag,
                            )
                            baselines[id] = SyncBaseline(
                                localHash = hashOfReexported(id, shardDir),
                                remoteEtag = action.etag,
                                remoteFile = action.remoteFile,
                                syncedAtMillis = now,
                            )
                            pulled++
                        }
                    }

                    is SyncAction.DeleteLocal -> {
                        repository.deleteConversations(listOf(action.conversationId))
                        baselines.remove(action.conversationId)
                        deleted++
                    }

                    is SyncAction.PushTombstone -> {
                        val file = File(shardDir, "tomb-${action.remoteFile}")
                        file.writeText(
                            """{"conversationId":"${action.conversationId}","deletedAt":$now,"deviceId":"$deviceId"}""",
                            Charsets.UTF_8,
                        )
                        client.put("$tombstonesDir/${action.remoteFile}", file, ifNoneMatch = true)
                        pending.removeAll { it.conversationId == action.conversationId }
                        baselines.remove(action.conversationId)
                    }

                    is SyncAction.DeleteRemote -> {
                        client.delete("$conversationsDir/${action.remoteFile}")
                        deleted++
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error is WebDavException && error.failure == WebDavFailure.CONFLICT) {
                    // 412：别的设备刚改过；保留本机内容，下一轮再比。
                    conflictIds += (action as? SyncAction.Push)?.conversationId
                        ?: (action as? SyncAction.Pull)?.conversationId
                        ?: ""
                    conflicts = conflictIds.size
                } else {
                    failed++
                    if (firstFailure == null) firstFailure = error.toAppText()
                }
            }
        }

        // ④ 设备文件与最后同步时间（失败不影响主结果）。
        runCatching {
            val deviceFile = File(shardDir, "device-$deviceId.json")
            deviceFile.writeText(
                """{"deviceId":"$deviceId","name":"${deviceName.replace("\"", "")}","lastSyncAt":${System.currentTimeMillis()}}""",
                Charsets.UTF_8,
            )
            client.put("$devicesDir/$deviceId.json", deviceFile)
        }
        val now = System.currentTimeMillis()
        store.write(
            SyncState(
                version = SyncState.CurrentVersion,
                baselines = baselines,
                pendingTombstones = pending,
                lastSyncAtMillis = now,
            ),
        )
        settingsStore.setSyncLastAt(now)
        _status.value = Status.Running(1f)
        shardDir.deleteRecursively()

        return SyncReport(
            pushed = pushed,
            pulled = pulled,
            deleted = deleted,
            conflicts = conflictIds.count { it.isNotBlank() },
            skipped = skipped,
            failed = failed,
            conflictConversations = conflictIds.filter { it.isNotBlank() }.toList(),
            failureText = firstFailure,
        )
    }

    // ── 工具 ─────────────────────────────────────────────

    private suspend fun downloadAndHash(
        client: WebDavClient,
        path: String,
        shardDir: File,
        name: String,
    ): String {
        val target = File(shardDir, "remote-$name")
        client.get(path, target)
        return SyncStore.sha256(target)
    }

    private suspend fun pullAndApply(
        client: WebDavClient,
        path: String,
        conversationId: String,
        mode: ImportMode,
        shardDir: File,
        expectedEtag: String?,
    ): String? {
        val target = File(shardDir, "pull-${davShardFileName(conversationId)}")
        val etag = client.get(path, target) ?: expectedEtag
        repository.importSyncShard(target, shardDir, mode)
        return etag
    }

    /** 导入后重新导出一份规范分片，作为下一次比较的基线哈希。 */
    private suspend fun hashOfReexported(conversationId: String, shardDir: File): String {
        val file = File(shardDir, "base-${davShardFileName(conversationId)}")
        repository.exportSyncShard(conversationId, file)
        return SyncStore.sha256(file)
    }

    private fun conversationIdOf(file: File, json: Json): String? = runCatching {
        json.parseToJsonElement(file.readText(Charsets.UTF_8))
            .jsonObject["conversations"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("id")?.jsonPrimitive?.content
    }.getOrNull()

    private fun defaultDeviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .trim()
        .ifEmpty { "Android" }
}

/** 同步异常 → 可翻译文案（网络层不碰资源，由这里统一映射）。 */
internal fun Throwable.toAppText(): AppText {
    if (this is AppTextException) return text
    if (this is WebDavException) {
        return AppText.of(
            when (failure) {
                WebDavFailure.AUTH -> R.string.sync_err_auth
                WebDavFailure.TLS -> R.string.sync_err_tls
                WebDavFailure.QUOTA -> R.string.sync_err_quota
                WebDavFailure.TOO_LARGE -> R.string.sync_err_too_large
                WebDavFailure.NOT_DAV -> R.string.sync_err_not_dav
                WebDavFailure.CONFLICT -> R.string.sync_err_conflict
                WebDavFailure.SERVER_BUSY -> R.string.sync_err_busy
                WebDavFailure.NETWORK -> R.string.sync_err_network
            },
        )
    }
    return AppText.of(R.string.sync_err_generic)
}
