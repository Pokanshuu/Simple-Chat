package com.simplechat.app.data.sync

import com.simplechat.app.data.writeFileAtomically
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json

/**
 * 同步状态的本地存储（docs/40 §50.7.2）。
 *
 * 位置：`filesDir/sync/sync-state.json`；写盘走 [writeFileAtomically]（容器里
 * `renameTo` 可能失败，失败会留下 0 字节状态文件，下次同步会当成"从没同步过"）。
 *
 * 状态是**派生数据**：坏了/丢了最多下次全量比对，不影响聊天内容，因此不做备份。
 */
internal class SyncStore(private val directory: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = false }

    val stateFile: File get() = File(directory, "sync-state.json")

    /** 分片临时文件目录（每次同步结束清理）。 */
    fun cacheDir(): File = File(directory, "shards").apply { mkdirs() }

    fun read(): SyncState {
        val file = stateFile
        if (!file.isFile) return SyncState()
        return runCatching {
            val decoded = json.decodeFromString(SyncState.serializer(), file.readText(Charsets.UTF_8))
            if (decoded.version != SyncState.CurrentVersion) SyncState() else decoded
        }.getOrDefault(SyncState())
    }

    fun write(state: SyncState) {
        directory.mkdirs()
        writeFileAtomically(stateFile) { file ->
            file.writeText(json.encodeToString(SyncState.serializer(), state), Charsets.UTF_8)
        }
    }

    /** 清空本地同步状态（退出同步账号时调用）。 */
    fun clear() {
        runCatching { stateFile.delete() }
        runCatching { cacheDir().deleteRecursively() }
    }

    companion object {
        /** 分片内容哈希：对**文件字节**做 SHA-256（`exportedAt` 固定后字节稳定）。 */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
