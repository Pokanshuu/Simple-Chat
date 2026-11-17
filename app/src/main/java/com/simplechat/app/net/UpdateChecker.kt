package com.simplechat.app.net

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 「检查更新」：问一次 GitHub Releases 有没有比当前版本更新的发布。
 *
 * ### 为什么直接打 GitHub API 而不是内置版本清单
 *
 * 版本清单要有人维护，忘了更新就是"永远提示已是最新"；Releases 本来就是发布的
 * 唯一事实来源，`/releases/latest` 直接给出最新**非草稿、非预发布**的 tag。
 *
 * 代价是暴露了一次匿名请求（未登录限 60 次/小时，够用）。所以：
 * - 只在用户点「检查更新」时发，**不在启动时偷偷发**；
 * - 请求失败不重试、不放大：一次失败就是一次失败。
 */
object UpdateChecker {

    /** 仓库地址：与「关于」页里的项目链接同一处。 */
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Pokanshuu/Simple-Chat/releases/latest"

    /**
     * 独立的客户端：只用于版本查询。
     *
     * 不复用对话那个（它 `readTimeout = 0`，是给流式用的）—— 版本查询必须有超时，
     * 否则网络半死不活时"检查更新"会一直转圈。
     */
    private val client: OkHttpClient by lazy {
        ChatApi.defaultClient().newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 一次发布：tag（如 `v1.1.5`）与可打开的页面地址。 */
    data class ReleaseInfo(val tag: String, val url: String)

    /** 检查结果。 */
    sealed interface Result {
        /** 服务端没有更新的版本。 */
        data object UpToDate : Result

        /** 有更新的版本。 */
        data class Newer(val release: ReleaseInfo) : Result

        /** 查不出来（离线 / 限流 / 结构不认识）。 */
        data class Failed(val reason: String) : Result
    }

    /**
     * 检查一次。
     *
     * [currentVersion] 是 `BuildConfig.VERSION_NAME`（如 `1.1.5`）。
     */
    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        val release = runCatching { fetchLatest() }
            .getOrElse { error ->
                Log.w("SimpleChat/Update", "check failed: ${error.javaClass.simpleName}")
                return@withContext Result.Failed(error.message.orEmpty())
            }
        return@withContext if (isNewerVersion(release.tag, currentVersion)) {
            Result.Newer(release)
        } else {
            Result.UpToDate
        }
    }

    private fun fetchLatest(): ReleaseInfo {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
                ?: error("missing tag_name")
            val url = root["html_url"]?.jsonPrimitive?.contentOrNull
                ?: error("missing html_url")
            return ReleaseInfo(tag = tag, url = url)
        }
    }
}

/**
 * [remote] 是否比 [local] 新。
 *
 * 规则（纯函数，有单测 `UpdateCheckerTest`）：
 * - 去掉 `v` / `V` 前缀；
 * - 按 `.` 切成数字段，逐段比较，缺的段按 0 算（`1.1` 与 `1.1.0` 相等）；
 * - 遇到非数字（`-beta.1` 之类的预发布后缀）当场截断 —— 只比版本号本身，
 *   预发布比正式版旧，不该因为后缀把它当成"更新"。
 */
internal fun isNewerVersion(remote: String, local: String): Boolean {
    val a = versionParts(remote)
    val b = versionParts(local)
    for (index in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(index) { 0 }
        val right = b.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}

private fun versionParts(raw: String): List<Int> =
    raw.trim()
        .removePrefix("v")
        .removePrefix("V")
        .takeWhile { it.isDigit() || it == '.' }
        .split('.')
        .mapNotNull { it.toIntOrNull() }
