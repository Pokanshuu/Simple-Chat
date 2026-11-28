package com.simplechat.app.net

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** 同步失败的类型（界面层负责翻成中英文案；net 层不碰 Android 资源）。 */
internal enum class WebDavFailure {
    /** 401 / 403：账号密码错、应用密码未开启、权限不足。 */
    AUTH,

    /** TLS 握手/证书问题。 */
    TLS,

    /** 507 / 412 之外的配额与空间不足。 */
    QUOTA,

    /** 413：单文件超限。 */
    TOO_LARGE,

    /** 405 / 501 / 返回 HTML：目标不是可写的 WebDAV 端点。 */
    NOT_DAV,

    /** 409 / 412：远端状态与预期不一致（并发修改、目录缺失）。 */
    CONFLICT,

    /** 429 / 503 / 504：服务器忙。 */
    SERVER_BUSY,

    /** 其它网络错误。 */
    NETWORK,
}

/** 一次 WebDAV 操作失败；[detail] 只用于诊断展示，不含密码。 */
internal class WebDavException(
    val failure: WebDavFailure,
    val code: Int = 0,
    detail: String? = null,
    cause: Throwable? = null,
) : IOException(detail ?: "webdav ${failure.name.lowercase()} ($code)", cause)

/**
 * 极简 WebDAV 客户端（docs/40 §50.8.1）。
 *
 * 只做 HTTP：PROPFIND / GET / PUT / DELETE / MKCOL + Basic 认证；不懂"会话"，
 * 也不做冲突判定 —— 那是 `data/sync/SyncEngine` 的事。这样网络层可以独立替换，
 * 编排层也能用假 gateway 做 JVM 单测。
 */
internal class WebDavClient(
    private val config: WebDavConfig,
    client: OkHttpClient? = null,
) {
    data class WebDavConfig(
        /** 形如 `https://dav.jianguoyun.com/dav/`；结尾斜杠可有可无。 */
        val baseUrl: String,
        val username: String,
        val password: String,
        /** 同步根目录（根目录下第一层），默认 `SimpleChat`。 */
        val rootDir: String = "SimpleChat",
    )

    private val http: OkHttpClient = (client ?: defaultClient()).newBuilder()
        // 同步都是小文件 + 明确的超时；不要沿用对话的无限读超时。
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val authHeader: String = basicAuth(config.username, config.password)

    /** 同步根目录（如 `SimpleChat`）。 */
    val rootPath: String = normalizeDavPath(config.rootDir)

    // ── 目录 ─────────────────────────────────────────────

    /** 列出 `dir` 下的一层条目；目录不存在时返回空列表（首次同步的常态）。 */
    suspend fun list(dir: String): List<DavEntry> = withContext(Dispatchers.IO) {
        val url = urlFor(dir)
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("Depth", "1")
            .method("PROPFIND", body)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> emptyList()
                    response.isSuccessful || response.code == 207 ->
                        parseDavMultistatus(response.body.string(), dir)
                    else -> throw failureFor(response.code)
                }
            }
        } catch (error: WebDavException) {
            throw error
        } catch (error: SSLException) {
            throw WebDavException(WebDavFailure.TLS, detail = error.message, cause = error)
        } catch (error: IOException) {
            throw WebDavException(WebDavFailure.NETWORK, detail = error.message, cause = error)
        }
    }

    /** 逐级创建目录（已存在算成功）。 */
    suspend fun ensureCollections(vararg dirs: String) {
        for (dir in dirs) {
            val path = normalizeDavPath(dir)
            if (path.isEmpty()) continue
            createCollection(path)
        }
    }

    private suspend fun createCollection(path: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(urlFor(path) + "/")
            .header("Authorization", authHeader)
            .method("MKCOL", null)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                // 201 新建；405/301 已存在；409 父目录不在（由调用方按顺序创建，不该出现）
                if (response.isSuccessful || response.code == 405 || response.code == 301) return@use
                throw failureFor(response.code)
            }
        } catch (error: WebDavException) {
            throw error
        } catch (error: SSLException) {
            throw WebDavException(WebDavFailure.TLS, detail = error.message, cause = error)
        } catch (error: IOException) {
            throw WebDavException(WebDavFailure.NETWORK, detail = error.message, cause = error)
        }
    }

    // ── 文件 ─────────────────────────────────────────────

    /** 下载 [path] 到 [target]，返回远端 ETag（可能为 null）。 */
    suspend fun get(path: String, target: File): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(urlFor(path))
            .header("Authorization", authHeader)
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw failureFor(response.code)
                target.parentFile?.mkdirs()
                response.body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                if (target.length() == 0L) throw WebDavException(WebDavFailure.NETWORK, detail = "empty shard")
                normalizeDavEtag(response.header("ETag"))
            }
        } catch (error: WebDavException) {
            throw error
        } catch (error: SSLException) {
            throw WebDavException(WebDavFailure.TLS, detail = error.message, cause = error)
        } catch (error: IOException) {
            throw WebDavException(WebDavFailure.NETWORK, detail = error.message, cause = error)
        }
    }

    /**
     * 上传 [file] 到 [path]，返回新的 ETag。
     *
     * @param ifMatch 非空时带 `If-Match`（覆盖已知基线；远端被别的设备改过会 412）
     * @param ifNoneMatch 新建时带 `If-None-Match: *`（已存在会 412，避免覆盖别人的新分片）
     */
    suspend fun put(
        path: String,
        file: File,
        ifMatch: String? = null,
        ifNoneMatch: Boolean = false,
    ): String? = withContext(Dispatchers.IO) {
        val body = file.asRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url(urlFor(path))
            .header("Authorization", authHeader)
            .put(body)
        if (ifMatch != null) builder.header("If-Match", "\"$ifMatch\"")
        else if (ifNoneMatch) builder.header("If-None-Match", "*")
        try {
            http.newCall(builder.build()).execute().use { response ->
                if (response.code == 412) throw WebDavException(WebDavFailure.CONFLICT, response.code)
                if (!response.isSuccessful) throw failureFor(response.code)
                normalizeDavEtag(response.header("ETag"))
            }
        } catch (error: WebDavException) {
            throw error
        } catch (error: SSLException) {
            throw WebDavException(WebDavFailure.TLS, detail = error.message, cause = error)
        } catch (error: IOException) {
            throw WebDavException(WebDavFailure.NETWORK, detail = error.message, cause = error)
        }
    }

    /** 删除文件；404 视为已删除。 */
    suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(urlFor(path))
            .header("Authorization", authHeader)
            .delete()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) throw failureFor(response.code)
            }
        } catch (error: WebDavException) {
            throw error
        } catch (error: SSLException) {
            throw WebDavException(WebDavFailure.TLS, detail = error.message, cause = error)
        } catch (error: IOException) {
            throw WebDavException(WebDavFailure.NETWORK, detail = error.message, cause = error)
        }
    }

    // ── 路径与错误映射 ───────────────────────────────────

    private fun urlFor(path: String): String {
        val base = config.baseUrl.trim().trimEnd('/')
        val normalized = normalizeDavPath(path)
        return if (normalized.isEmpty()) base + "/" else "$base/$normalized"
    }

    private fun failureFor(code: Int): WebDavException {
        val failure = when (code) {
            401, 403 -> WebDavFailure.AUTH
            404, 409, 412 -> WebDavFailure.CONFLICT
            413 -> WebDavFailure.TOO_LARGE
            405, 501 -> WebDavFailure.NOT_DAV
            429, 503, 504 -> WebDavFailure.SERVER_BUSY
            507 -> WebDavFailure.QUOTA
            else -> WebDavFailure.NETWORK
        }
        return WebDavException(failure, code)
    }

    companion object {
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
    <d:getetag/>
    <d:getcontentlength/>
    <d:getlastmodified/>
  </d:prop>
</d:propfind>"""

        /** 与对话共用的 OkHttp；连接池与 DNS 缓存复用。 */
        fun defaultClient(): OkHttpClient = ChatApi.defaultClient()

        /** Basic 认证头。密码可能含非 ASCII —— 按 UTF-8 编，服务端不支持时自然 401。 */
        fun basicAuth(username: String, password: String): String {
            val raw = "$username:$password".toByteArray(Charsets.UTF_8)
            return "Basic " + java.util.Base64.getEncoder().encodeToString(raw)
        }
    }
}
