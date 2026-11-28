package com.simplechat.app.net

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 下载更新包。
 *
 * 与 `VoiceTtsClient` 一样走 IO 线程 + OkHttp，但多两件事：
 *
 * 1. **进度**：几十 MB 的包要能让用户看到走到哪了，否则界面像卡死；
 * 2. **校验**：先下到 `.part`，sha256 对上才改名成正式文件 —— 断网/代理插一脚
 *    产生的半截 APK 也能被 `PackageManager` 装出个"应用未安装"，用户根本不知道
 *    是下载坏了。GitHub 的 `digest` 字段给的就是 sha256，老 release 没有就跳过校验
 *    （宁可装没校验的包，也不要因为这个功能不可用）。
 */
object UpdateDownloader {

    /** 下载目录名：放在 cache 下，安装完不会留一份 20 MB 的死文件。 */
    private const val DIR = "updates"

    data class Progress(val received: Long, val total: Long) {
        /** 0..1；总量未知时返回 0（界面用不确定进度条）。 */
        val fraction: Float get() = if (total > 0L) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    class DownloadException(message: String) : Exception(message)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 下载大文件：连接慢可以等，读超时给足
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 把 [asset] 下到 `cacheDir/updates/<name>` 并返回文件。
     *
     * 已经下过且校验通过时直接复用 —— 用户点了"取消"再点"下载"，不该从头再来一遍。
     *
     * @param onProgress 在主线程之外回调，调用方自己切线程更新 UI 状态。
     */
    suspend fun download(
        context: Context,
        asset: UpdateChecker.Asset,
        mirror: UpdateChecker.Mirror,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        // 资产名来自网络 JSON，不能直接当路径用（`../databases/...` 会穿出 updates/）。
        val target = File(dir, safeApkName(asset.name))

        if (target.exists() && verifies(target, asset)) return@withContext target

        val url = mirror.apply(asset.url)
        val partial = File(dir, "${target.name}.part")
        partial.delete()

        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw DownloadException("HTTP ${response.code}")

                val total = response.body.contentLength().takeIf { it > 0 }
                    ?: asset.size.takeIf { it > 0 }
                    ?: 0L
                val source = response.body.byteStream()

                partial.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        // 用户点了取消：协程被取消时立刻停下，不要一边取消一边写盘
                        ensureActive()
                        val read = source.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        received += read
                        onProgress(Progress(received, total))
                    }
                    // 总量已知时按字节数兜底：代理截断/返回错误页不能当成功。
                    if (total > 0L && received != total) {
                        throw DownloadException("下载不完整：$received/$total")
                    }
                }
            }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }

        if (!verifies(partial, asset)) {
            partial.delete()
            throw DownloadException("校验失败：文件可能没下完")
        }
        moveIntoPlace(partial, target)
        return@withContext target
    }

    /**
     * 把下载完成的 `.part` 变成正式文件。
     *
     * `renameTo()` 失败时**不抛异常、只返回 false**；某些容器（鸿蒙 / 卓易通之类
     * 把私有目录映射到不同挂载点的环境）里同目录 rename 也会失败。这里必须检查
     * 返回值，失败就退化成流式复制，并核对字节数 —— 任何情况下都不留 0 字节文件。
     */
    private fun moveIntoPlace(partial: File, target: File) {
        if (partial.renameTo(target)) return
        val expected = partial.length()
        target.delete()
        try {
            partial.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (!target.exists() || target.length() != expected) {
                throw IOException("无法保存安装包")
            }
        } catch (error: Throwable) {
            target.delete()
            partial.delete()
            throw error
        }
        partial.delete()
    }

    /**
     * 服务端文件名 → 安全的本地文件名。
     *
     * 只留字母/数字/`.`/`-`/`_`，且必须是 `.apk`；不符合的一律换成 `update.apk`。
     */
    internal fun safeApkName(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val valid = name.isNotEmpty() && name.length <= 120 &&
            name.endsWith(".apk", ignoreCase = true) && !name.startsWith(".") &&
            name.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        return if (valid) name else "update.apk"
    }

    /** 删除已下载的安装包（用户取消 / 装完后清理）。 */
    fun clear(context: Context): Long {
        val freed = downloadedBytes(context)
        File(context.cacheDir, DIR).listFiles()?.forEach { it.delete() }
        return freed
    }

    /** 已下载安装包占用的空间（字节）。界面据此决定「清理」是否可点。 */
    fun downloadedBytes(context: Context): Long =
        File(context.cacheDir, DIR).listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * 校验下载结果。
     *
     * 有 sha256 时比哈希；老 release 没给 digest 时退化为比字节数（[asset.size] 已知时），
     * 两者都没有才放弃校验 —— 但上面的下载循环仍会核对实际收到的字节数。
     */
    private fun verifies(file: File, asset: UpdateChecker.Asset): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val want = asset.sha256?.trim()?.lowercase()
        if (!want.isNullOrEmpty()) {
            val actual = runCatching { sha256Of(file) }.getOrNull() ?: return false
            return actual == want
        }
        return asset.size <= 0L || file.length() == asset.size
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
