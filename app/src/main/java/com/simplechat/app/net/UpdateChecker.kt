package com.simplechat.app.net

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 「检查更新」：问一次 GitHub Releases 有没有比当前版本更新的发布，并挑出要装的 APK。
 *
 * ### 为什么直接打 GitHub API 而不是内置版本清单
 *
 * 版本清单要有人维护，忘了更新就是"永远提示已是最新"；Releases 本来就是发布的
 * 唯一事实来源，`/releases/latest` 直接给出最新**非草稿、非预发布**的 tag。
 *
 * ### 为什么要镜像
 *
 * 国内访问 `api.github.com` 时好时坏，真正的大头是 release 资产
 * （`release-assets.githubusercontent.com`），经常能拿到 JSON 却下不动 APK。
 * 所以：
 * - 检查：直连失败后依次试公共加速站（它们的 URL 形态是 `前缀 + 原始链接`）；
 * - 下载：把镜像选择交给用户（见 [Mirror]），失败再换一个。
 *
 * 代价是暴露了一次匿名请求（未登录限 60 次/小时，够用）。所以只在用户点
 * 「检查更新」时发，**不在启动时偷偷发**。
 */
object UpdateChecker {

    /** 仓库地址：与「关于」页里的项目链接同一处。 */
    private const val REPO = "Pokanshuu/Simple-Chat"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** 拿不到资产时的兜底：让用户自己去发布页手动下载。 */
    const val RELEASES_PAGE_URL = "https://github.com/$REPO/releases/latest"

    /**
     * 下载渠道。
     *
     * 公共加速站的约定是把 GitHub 链接整段拼在域名后面（`https://ghfast.top/https://github.com/...`），
     * 所以这里只存前缀。没有自建镜像后端 —— 那需要额外维护一台服务器，
     * 而这类加速站的可用性本来就够用，坏了换一个即可。
     */
    enum class Mirror(val prefix: String) {
        GITHUB(""),
        GH_PROXY("https://gh-proxy.org/"),
        GHFAST("https://ghfast.top/");

        /** 把 GitHub 原始链接套上镜像前缀。 */
        fun apply(url: String): String = if (prefix.isEmpty()) url else prefix + url
    }

    /** 一个可下载的发布资产。 */
    data class Asset(
        val name: String,
        val url: String,
        val size: Long,
        /** GitHub 给的 `sha256:<hex>`，老 release 可能没有。 */
        val sha256: String?,
    )

    /** 一次发布。 */
    data class ReleaseInfo(
        val tag: String,
        val pageUrl: String,
        /** 更新说明（Markdown 原文，界面上按纯文本展示）。 */
        val notes: String,
        val publishedAt: String,
        /** 要装的 APK；没有时用户只能去网页下载。 */
        val asset: Asset?,
    )

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

    /**
     * 检查一次。依次尝试直连与镜像，第一个成功的为准。
     *
     * [currentVersion] 是 `BuildConfig.VERSION_NAME`（如 `1.2.6-beta.5`）。
     */
    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        var lastError = ""
        for (mirror in listOf(Mirror.GITHUB, Mirror.GH_PROXY, Mirror.GHFAST)) {
            val release = runCatching { fetchLatest(mirror) }.getOrElse { error ->
                lastError = error.message.orEmpty()
                Log.w(
                    "SimpleChat/Update",
                    "check via ${mirror.name} failed: ${error.javaClass.simpleName}: ${error.message}",
                )
                null
            } ?: continue
            return@withContext if (isNewerVersion(release.tag, currentVersion)) {
                Result.Newer(release)
            } else {
                Result.UpToDate
            }
        }
        Result.Failed(lastError.ifBlank { "网络不可用" })
    }

    private fun fetchLatest(mirror: Mirror): ReleaseInfo {
        val request = Request.Builder()
            .url(mirror.apply(LATEST_RELEASE_URL))
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull
                ?: error("missing tag_name")
            return ReleaseInfo(
                tag = tag,
                pageUrl = root["html_url"]?.jsonPrimitive?.contentOrNull
                    ?: RELEASES_PAGE_URL,
                notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                asset = pickApk(root["assets"] as? JsonArray),
            )
        }
    }

    /**
     * 从资产列表里挑 APK。
     *
     * 规则：只认 `.apk` 结尾（排除附带出来的 `.apk.sha256`）；优先非 debug 的包 ——
     * 发版时可能同时挂调试包，普通用户要的是 release。
     */
    private fun pickApk(assets: JsonArray?): Asset? {
        if (assets == null) return null
        val apks = assets.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!name.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
            val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            Asset(
                name = name,
                url = url,
                size = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                sha256 = parseSha256(obj["digest"]?.jsonPrimitive?.contentOrNull),
            )
        }
        return apks.firstOrNull { !it.name.contains("debug", ignoreCase = true) } ?: apks.firstOrNull()
    }

    /** `sha256:<hex>` → `<hex>`；格式不对就当没有校验值。 */
    private fun parseSha256(digest: String?): String? {
        val value = digest?.trim().orEmpty()
        if (!value.startsWith("sha256:", ignoreCase = true)) return null
        val hex = value.substringAfter(':').trim().lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
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
