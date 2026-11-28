package com.simplechat.app.net

import java.io.StringReader
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * WebDAV 协议里的纯函数（docs/40 §50.8.1）。
 *
 * 只做三件事：解析 `PROPFIND` 的 multistatus、归一化 ETag、拼/拆远端路径。
 * 刻意不碰 OkHttp / Android —— 全部是 JVM 可测的字符串处理，协议细节错在这里
 * 只会表现为"同步莫名其妙不生效"，很难从界面上看出来。
 */
internal data class DavEntry(
    /** 相对同步根目录的路径（不含前导 `/`）。 */
    val path: String,
    /** 归一化后的 ETag；服务器没给或格式不认识时为 null。 */
    val etag: String?,
    val isDirectory: Boolean,
    val size: Long,
    val lastModifiedMillis: Long?,
)

/** XML 解析失败（服务器返回错误页 / 非 WebDAV 服务）。 */
internal class DavParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 解析 WebDAV 的 `multistatus` 响应。
 *
 * 不依赖具体命名空间前缀：用 `*` 命名空间按 local name 找元素，兼容
 * Nextcloud / 坚果云 / AList / 群晖这些实现（它们的前缀各不相同，甚至同一台服务器
 * 不同接口也不一样）。
 *
 * @param basePath 被查询目录的路径（如 `SimpleChat/conversations`），用于把
 *   `href` 里的绝对路径换算成相对路径。
 */
internal fun parseDavMultistatus(xml: String, basePath: String): List<DavEntry> {
    if (xml.isBlank()) throw DavParseException("empty response")
    val document = parseDocument(xml)
    val responses = document.getElementsByTagNameNS("*", "response")
    val base = normalizeDavPath(basePath)
    val out = mutableListOf<DavEntry>()
    for (index in 0 until responses.length) {
        val response = responses.item(index) as? Element ?: continue
        val href = childText(response, "href") ?: continue
        val path = relativeDavPath(href, base) ?: continue
        // 查询目录自身也会出现在结果里（Depth: 1），跳过。
        if (path.isEmpty()) continue
        val prop = findPropStatOk(response)
        val resourceType = prop?.let { findChild(it, "resourcetype") }
        val isDirectory = resourceType?.let { findChild(it, "collection") != null } ?: href.trimEnd('/').endsWith("/")
        val etag = normalizeDavEtag(prop?.let { childText(it, "getetag") })
        val size = prop?.let { childText(it, "getcontentlength") }?.trim()?.toLongOrNull() ?: 0L
        val modified = prop?.let { childText(it, "getlastmodified") }?.let(::parseHttpDateMillis)
        out += DavEntry(
            path = path,
            etag = etag,
            isDirectory = isDirectory,
            size = size.coerceAtLeast(0L),
            lastModifiedMillis = modified,
        )
    }
    return out
}

private fun parseDocument(xml: String): Document = try {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // 远端返回的 WebDAV XML 不需要 DTD/外部实体；禁掉顺带挡 XXE。
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
} catch (error: Exception) {
    throw DavParseException("invalid multistatus xml", error)
}

private fun findPropStatOk(response: Element): Element? {
    val propStats = response.getElementsByTagNameNS("*", "propstat")
    for (index in 0 until propStats.length) {
        val propStat = propStats.item(index) as? Element ?: continue
        val status = childText(propStat, "status").orEmpty()
        // 只看 2xx 那条；207 内部每条 propstat 各自带状态。
        if (status.isNotBlank() && !status.contains(" 200 ")) continue
        return findChild(propStat, "prop")
    }
    return null
}

private fun findChild(element: Element, localName: String): Element? {
    val children = element.getElementsByTagNameNS("*", localName)
    return children.item(0) as? Element
}

private fun childText(element: Element, localName: String): String? =
    findChild(element, localName)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 把 `href`（可能是绝对 URL、`/SimpleChat/x` 或已编码的路径）换算成相对 [basePath] 的路径。
 *
 * 不在 base 下面（服务器爱把父目录也列出来）就返回 null。
 */
internal fun relativeDavPath(href: String, basePath: String): String? {
    var path = href.trim()
    // 绝对 URL：剥出 path 部分。
    if (path.startsWith("http://") || path.startsWith("https://")) {
        path = path.substringAfter("://").substringAfter('/', "")
        path = "/$path"
    }
    path = path.substringBefore('?').substringBefore('#')
    val decoded = runCatching { java.net.URLDecoder.decode(path, Charsets.UTF_8.name()) }.getOrDefault(path)
    var normalized = normalizeDavPath(decoded)
    val base = normalizeDavPath(basePath)
    if (base.isNotEmpty()) {
        /*
         * href 里通常带服务端的 DAV 前缀（如 `/dav/SimpleChat/...`），我们的 basePath
         * 只有同步根内的相对目录 —— 因此按**路径段后缀**匹配，而不是要求整个 href
         * 以 basePath 开头。
         */
        val marker = "/$base/"
        val index = normalized.indexOf(marker)
        when {
            index >= 0 -> normalized = normalized.substring(index + marker.length)
            // href 可能正好是 base 本身或以其开头（没有服务端前缀的手写路径）。
            normalized == base -> return ""
            normalized.startsWith("$base/") -> normalized = normalized.removePrefix("$base/")
            normalized.endsWith("/$base") -> return ""
            else -> return null
        }
    }
    return normalized.trim('/')
}

/** 归一化远端路径：剥协议/主机、合并连续 `/`、去掉首尾 `/`。 */
internal fun normalizeDavPath(raw: String): String {
    var path = raw.trim()
    if (path.startsWith("http://") || path.startsWith("https://")) {
        path = path.substringAfter("://").substringAfter('/', "")
    }
    return path.split('/').filter { it.isNotEmpty() }.joinToString("/")
}

/** 拼远端路径：每段去掉首尾 `/` 后拼接（不做协议归一化，首段可能是绝对 URL）。 */
internal fun joinDavPath(vararg segments: String): String =
    segments.map { it.trim().trim('/') }
        .filter { it.isNotEmpty() }
        .joinToString("/")

/**
 * 归一化 ETag：去掉 `W/` 弱校验前缀与双引号，统一小写。
 *
 * 我们不要求强 ETag（同步只把它当"远端变过没有"的比较键），但**必须**统一格式，
 * 否则 `"abc"` 与 `W/"abc"` 会被当成两个值，导致每次都判"远端变了"。
 */
internal fun normalizeDavEtag(raw: String?): String? {
    var value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    // 弱校验前缀大小写都见过（`W/`、`w/`），都要剥。
    if (value.startsWith("W/", ignoreCase = true)) value = value.substring(2)
    value = value.trim('"').trim()
    return value.takeIf { it.isNotEmpty() }?.lowercase()
}

/** 会话 id → 远端分片文件名：SHA-256 十六进制，避免中文/特殊字符路径。 */
internal fun davShardFileName(conversationId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(conversationId.toByteArray(Charsets.UTF_8))
    // Byte 是带符号的：先 and 0xff 再格式化成两位十六进制。
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".json"
}

/** 远端分片文件名 → 会话 id 不可能（哈希不可逆）——同步状态里反向记录文件名。 */

/** HTTP 日期（`Sun, 06 Nov 1994 08:49:37 GMT`）→ epoch 毫秒；解析失败返回 null。 */
internal fun parseHttpDateMillis(raw: String): Long? = runCatching {
    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
        .parse(raw)
        .let { java.time.ZonedDateTime.from(it).toInstant().toEpochMilli() }
}.getOrNull()
