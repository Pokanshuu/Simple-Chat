package com.simplechat.app.data.import

import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.Citation
import com.simplechat.app.net.WebSource
import kotlin.math.roundToLong

/** Decode fields directly: no raw-message copy, JsonObject tree or second copy of one fragment. */
internal fun readDeepSeekMessage(stream: JsonStream): DeepSeekHistory.ParsedMessage {
    var id: Long? = null
    var parent: Long? = null
    var role: String? = null
    var status: String? = null
    var insertedAt: String? = null
    var topContent: String? = null
    val request = TextParts()
    val response = TextParts()
    val template = TextParts()
    val thinks = mutableListOf<Pair<String, Long>>()
    val images = mutableListOf<DeepSeekHistory.ImageRef>()
    var timeline: MutableList<ReplyBlock>? = null
    var toolIndex = 0
    val shareReferences = mutableListOf<List<ArchiveSource>>()
    stream.beginObject()
    while (stream.hasNextField()) {
        when (stream.fieldName()) {
            "id", "message_id" -> id = stream.textValue()?.toLongOrNull()
            "parent_id" -> parent = stream.textValue()?.toLongOrNull()
            "role" -> role = stream.textValue()
            "status" -> status = stream.textValue()
            "inserted_at" -> insertedAt = stream.textValue()
            "content" -> topContent = stream.textValue()
            "fragments" -> {
                if (stream.valueKind() == 'n') { stream.skipValue(); continue }
                stream.beginArray()
                while (stream.hasNextArrayElement()) {
                    var type: String? = null
                    var content = ""
                    var elapsed = 0L
                    var files: MutableList<DeepSeekHistory.ImageRef>? = null
                    var queries: List<String> = emptyList()
                    var results: List<ArchiveSource> = emptyList()
                    var url: String? = null
                    var title: String? = null
                    var publishedAt: String? = null
                    var snippet: String? = null
                    var siteName: String? = null
                    var pattern = ""
                    var toolStatus: String? = null
                    stream.beginObject()
                    while (stream.hasNextField()) {
                        when (stream.fieldName()) {
                            "type" -> type = stream.textValue()
                            "content" -> content = stream.textValue().orEmpty()
                            "elapsed_secs" -> elapsed = stream.textValue()?.toDoubleOrNull()
                                ?.takeIf { it.isFinite() && it in 0.0..(Long.MAX_VALUE / 1000.0) }
                                ?.times(1000)?.roundToLong() ?: 0L
                            "queries" -> queries = stream.readQueryStrings()
                            "results" -> results = stream.readArchiveSources()
                            "references" -> {
                                shareReferences.clear()
                                shareReferences += stream.readShareReferences()
                            }
                            "result" -> {
                                if (stream.valueKind() != '{') stream.skipValue() else {
                                    stream.beginObject()
                                    while (stream.hasNextField()) when (stream.fieldName()) {
                                        "url" -> url = stream.textValue().archiveWebUrl()
                                        "title" -> title = stream.textValue()
                                        "snippet" -> snippet = stream.textValue()
                                        "site_name" -> siteName = stream.textValue()
                                        "published_at" -> publishedAt = stream.textValue()
                                        else -> stream.skipValue()
                                    }
                                }
                            }
                            "status" -> toolStatus = stream.textValue()
                            "url" -> url = stream.textValue().archiveWebUrl()
                            "title" -> title = stream.textValue()
                            "published_at" -> publishedAt = stream.textValue()
                            "snippet" -> snippet = stream.textValue()
                            "site_name" -> siteName = stream.textValue()
                            "pattern" -> pattern = stream.textValue().orEmpty()
                            "files" -> {
                                if (stream.valueKind() == 'n') { stream.skipValue(); continue }
                                stream.beginArray()
                                while (stream.hasNextArrayElement()) {
                                    var image = false
                                    var path: String? = null
                                    var name: String? = null
                                    stream.beginObject()
                                    while (stream.hasNextField()) {
                                        when (stream.fieldName()) {
                                            "is_image" -> image = stream.textValue() == "true"
                                            "export_path" -> path = stream.textValue()
                                            "file_name" -> name = stream.textValue()
                                            else -> stream.skipValue()
                                        }
                                    }
                                    if (image && !path.isNullOrBlank()) {
                                        (files ?: ArrayList<DeepSeekHistory.ImageRef>().also { files = it })
                                            .add(DeepSeekHistory.ImageRef(path, name?.takeIf { it.isNotBlank() }))
                                    }
                                }
                            }
                            else -> stream.skipValue()
                        }
                    }
                    when (type) {
                        "REQUEST" -> request.add(content)
                        "RESPONSE" -> {
                            response.add(content)
                            if (content.isNotEmpty()) timeline?.add(ReplyBlock.Text(content))
                        }
                        "TEMPLATE_RESPONSE" -> {
                            template.add(content)
                            if (content.isNotEmpty()) timeline?.add(ReplyBlock.Text(content))
                        }
                        "THINK" -> {
                            thinks += content to elapsed
                            timeline?.add(archiveThinking(content, elapsed))
                        }
                        "FILE" -> files?.let { images.addAll(it) }
                        "TOOL_SEARCH", "TOOL_IMAGE_SEARCH", "TOOL_OPEN", "TOOL_FIND", "READ_LINK" -> {
                            val blocks = timeline ?: ArrayList<ReplyBlock>().also { list ->
                                thinks.forEach { (text, duration) -> list.add(archiveThinking(text, duration)) }
                                response.value().takeIf { it.isNotEmpty() }?.let { list.add(ReplyBlock.Text(it)) }
                                template.value().takeIf { it.isNotEmpty() }?.let { list.add(ReplyBlock.Text(it)) }
                                timeline = list
                            }
                            val toolId = "archive-" + toolIndex++
                            val failed = toolStatus == "FAILED"
                            when (type) {
                                "TOOL_SEARCH", "TOOL_IMAGE_SEARCH" -> {
                                    val kind = if (type == "TOOL_SEARCH") "web_search" else "image_search"
                                    blocks += ReplyBlock.ToolUse(toolId, "archive", kind, "search", queries)
                                    blocks += ReplyBlock.SearchResult(toolId, kind, results.mapNotNull { it.toWebSource() }, failed)
                                }
                                "TOOL_OPEN", "TOOL_FIND" -> {
                                    val action = if (type == "TOOL_OPEN") "open_page" else "find_in_page"
                                    blocks += ReplyBlock.ToolUse(toolId, "archive", "deep_search", action, url = url.orEmpty(), pattern = pattern)
                                    blocks += ReplyBlock.FetchResult(toolId, "deep_search", url.orEmpty(), title, publishedAt,
                                        failed = failed || toolStatus == "NO_RESULT", snippet = snippet, siteName = siteName)
                                }
                                "READ_LINK" -> results.forEachIndexed { index, result ->
                                    val signature = result.signature ?: return@forEachIndexed
                                    val resultId = "$toolId-$index"
                                    // Web-archive signatures do not belong to the configured live API.
                                    val kind = "archive_read_link"
                                    if (!result.url.isNullOrBlank()) {
                                        blocks += ReplyBlock.ToolUse(resultId, "archive", kind, "open_page", url = result.url)
                                    }
                                    blocks += ReplyBlock.FetchResult(resultId, kind, result.url.orEmpty(), result.title, signature = signature)
                                }
                            }
                        }
                    }
                }
            }
            else -> stream.skipValue()
        }
    }
    return DeepSeekHistory.ParsedMessage(
        number = requireNotNull(id) { "Message has no supported numeric id" },
        parentNumber = parent, role = role, status = status, insertedAt = insertedAt,
        request = request.value().ifEmpty { topContent.orEmpty() }, response = response.value(), template = template.value(),
        thinks = thinks, images = images, timeline = if (role == "USER") emptyList() else
            timeline.orEmpty().map { block ->
                if (block !is ReplyBlock.Text) block else {
                    val citations = Regex("\\[reference:(\\d+)]").findAll(block.text)
                        .mapNotNull { match ->
                            val sources = shareReferences.getOrNull(match.groupValues[1].toIntOrNull() ?: -1).orEmpty()
                            sources.firstOrNull()?.let { source ->
                                source.url?.let { Citation(it, source.title, block.text.substring(0, match.range.first)) }
                            }
                        }.toList()
                    ReplyBlock.Text(Regex("\\[reference:\\d+]").replace(block.text, ""), citations)
                }
            },
    )
}

private class TextParts {
    private var first: String? = null
    private var joined: StringBuilder? = null
    fun add(text: String) {
        if (text.isEmpty()) return
        if (first == null) first = text
        else {
            val builder = joined ?: StringBuilder(first!!).also { joined = it }
            builder.append(text)
        }
    }
    fun value(): String = joined?.toString() ?: first.orEmpty()
}

private fun archiveThinking(text: String, millis: Long) =
    ReplyBlock.Thinking(text, startedAt = if (millis > 0) 0L else null, endedAt = millis.takeIf { it > 0 })

private data class ArchiveSource(
    val url: String?, val title: String?, val snippet: String?, val siteName: String?,
    val siteIcon: String?, val publishedAt: String?, val signature: String?,
) {
    fun toWebSource(): WebSource? = url?.let { WebSource(it, title, siteName, siteIcon, snippet, publishedAt) }
}

private fun JsonStream.readQueryStrings(): List<String> {
    if (valueKind() != '[') { skipValue(); return emptyList() }
    beginArray()
    val queries = ArrayList<String>()
    while (hasNextArrayElement()) {
        if (valueKind() == '"') queries += textValue().orEmpty() else skipValue()
    }
    return queries
}

private fun JsonStream.readArchiveSources(): List<ArchiveSource> {
    if (valueKind() != '[') { skipValue(); return emptyList() }
    beginArray()
    val results = ArrayList<ArchiveSource>()
    while (hasNextArrayElement()) {
        if (valueKind() != '{') { skipValue(); continue }
        var url: String? = null
        var title: String? = null
        var snippet: String? = null
        var siteName: String? = null
        var siteIcon: String? = null
        var publishedAt: String? = null
        var signature: String? = null
        beginObject()
        while (hasNextField()) {
            when (fieldName()) {
                "url", "source_page_url" -> url = textValue().archiveWebUrl()
                "title" -> title = textValue()
                "snippet" -> snippet = textValue()
                "site_name" -> siteName = textValue()
                "site_icon" -> siteIcon = textValue()
                "published_at" -> publishedAt = textValue()
                "signature" -> signature = textValue()
                else -> skipValue()
            }
        }
        results += ArchiveSource(url, title, snippet, siteName, siteIcon, publishedAt, signature)
    }
    return results
}

private fun JsonStream.readShareReferences(): List<List<ArchiveSource>> {
    if (valueKind() != '[') { skipValue(); return emptyList() }
    beginArray()
    val references = ArrayList<List<ArchiveSource>>()
    while (hasNextArrayElement()) {
        var urls: List<String> = emptyList()
        beginObject()
        while (hasNextField()) when (fieldName()) {
            "urls" -> {
                if (valueKind() != '[') { skipValue(); continue }
                beginArray()
                val values = ArrayList<String>()
                while (hasNextArrayElement()) streamUrl(textValue())?.let(values::add)
                urls = values
            }
            else -> skipValue()
        }
        references += urls.map { ArchiveSource(it, null, null, null, null, null, null) }
    }
    return references
}

private fun streamUrl(url: String?): String? = url?.takeIf {
    it.startsWith("https://", true) || it.startsWith("http://", true)
}

private fun String?.archiveWebUrl(): String? = this?.takeIf {
    it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
}
