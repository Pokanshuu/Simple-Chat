package com.simplechat.app.net

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.StoredSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val IndependentSearchName = "simplechat_independent_search"
internal const val IndependentFetchName = "simplechat_independent_fetch"
internal const val IndependentSearchKind = "independent_web_search"

internal data class SearchAugmentation(
    val blocks: List<ReplyBlock> = emptyList(),
) {
    fun replyBlocks(reasoning: String, answer: String, startedAt: Long?, endedAt: Long?): List<ReplyBlock> = blocks + buildList {
        if (reasoning.isNotEmpty()) add(ReplyBlock.Thinking(reasoning, startedAt = startedAt, endedAt = endedAt))
        if (answer.isNotEmpty()) add(ReplyBlock.Text(answer))
    }
}


private val explicitWebLink = Regex("https?://[^\\s<>\"）)]+", RegexOption.IGNORE_CASE)

internal fun publicPage(raw: String): String? {
    val url = raw.takeIf { it.length <= 2048 }?.toHttpUrlOrNull() ?: return null
    val host = url.host.trimEnd('.')
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || '.' !in host || ':' in host ||
        host.substringAfterLast('.').none { it in 'a'..'z' } ||
        host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".onion")) return null
    return url.newBuilder().fragment(null).build().toString()
}


internal class WebResearchSession(
    private val settings: StoredSettings,
    question: String,
    private val api: ServiceApi = ServiceApi(),
    recentTurns: List<Pair<String, String>> = emptyList(),
    private val onProgress: (List<ReplyBlock>) -> Unit = {},
) {
    private val config = settings.searchApi
    private val key by lazy { config.key(settings.apiKey) }
    private val blocks = mutableListOf<ReplyBlock>()
    private val sources = linkedMapOf<String, WebSource>()
    private val material = linkedMapOf<String, String>()
    private val candidates = linkedMapOf<String, String>()
    private val searched = linkedSetOf<String>()
    private val fetched = linkedSetOf<String>()
    private fun registerLinks(text: String) {
        explicitWebLink.findAll(text).take(6).mapNotNull { publicPage(it.value.trimEnd('.', ',', '。', '，')) }
            .forEach { candidates.putIfAbsent(it, "Link supplied by the user") }
    }
    init {
        registerLinks(question)
        recentTurns.asReversed().filter { it.first == "user" }.take(3).forEach { registerLinks(it.second) }
    }
    private suspend fun runTool(use: ReplyBlock.ToolUse, failed: ReplyBlock, request: suspend () -> ReplyBlock) {
        blocks += use
        onProgress(blocks.toList())
        val result = try {
            withTimeoutOrNull(18_000) { request() } ?: failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failed
        }
        blocks += result
        onProgress(blocks.toList())
    }
    private fun addSource(source: WebSource, text: String, fullPage: Boolean = false) {
        if (fullPage) {
            val previous = sources[source.url]
            sources[source.url] = previous?.copy(title = source.title?.takeIf { it.isNotBlank() } ?: previous.title) ?: source
            material[source.url] = text.take(8_000)
        } else if (sources.putIfAbsent(source.url, source) == null) {
            material[source.url] = text.take(1_500)
        }
        if (source.url !in candidates && candidates.size < 40) candidates[source.url] = source.title.orEmpty()
    }

    private suspend fun search(query: String) {
        if (searched.size >= 3 || !searched.add(query.lowercase())) return
        val search = ReplyBlock.ToolUse("$IndependentSearchName:${java.util.UUID.randomUUID()}", IndependentSearchName,
            IndependentSearchKind, "search", queries = listOf(query))
        runTool(search, ReplyBlock.SearchResult(search.id, search.kind, failed = true)) {
            val results = api.search(config.endpoint(settings.baseUrl, config.searchPath), key, query, config.count)
            val found = results.take(config.count).mapNotNull { item ->
                val result = item as? JsonObject ?: return@mapNotNull null
                fun field(name: String) = (result[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val url = publicPage(field("url")) ?: return@mapNotNull null
                WebSource(url, field("title").take(300), field("site_name").take(200), field("site_icon").take(2048),
                    field("snippet").take(500), field("date").take(80)).also {
                    if (sources.size < 30 || url in sources) addSource(it,
                        field("summary").ifBlank { field("snippet").ifBlank { field("content") } })
                }
            }.distinctBy { it.url }
            ReplyBlock.SearchResult(search.id, search.kind, found)
        }
    }
    private suspend fun fetch(url: String) {
        if (fetched.size >= 2 || !fetched.add(url)) return
            val fetch = ReplyBlock.ToolUse("$IndependentFetchName:${java.util.UUID.randomUUID()}", IndependentFetchName,
                "web_fetch", "open_page", url = url)
            runTool(fetch, ReplyBlock.FetchResult(fetch.id, fetch.kind, url = url, failed = true)) {
                val page = api.fetch(config.endpoint(settings.baseUrl, config.fetchPath), key, url)
                val content = (page["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: throw AppTextException(R.string.service_bad_response)
                val title = (page["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(300)
                addSource(WebSource(url = url, title = title), content, fullPage = true)
                ReplyBlock.FetchResult(fetch.id, fetch.kind, url = url, title = title)
            }
    }
    suspend fun execute(name: String, arguments: String): AgentToolOutcome {
        val param = if (name == IndependentSearchName) "query" else "url"
        val value = webToolArgument(arguments, param) ?: return AgentToolOutcome(AgentToolStatus.INVALID_ARGUMENTS, "Invalid tool arguments: supply $param.")
        val start = blocks.size
        val urls = when (name) {
            IndependentSearchName -> {
                search(ServiceApi.query(value))
                (blocks.lastOrNull() as? ReplyBlock.SearchResult)?.results.orEmpty().map { it.url }
            }
            IndependentFetchName -> {
                val url = publicPage(value)?.takeIf { it in candidates }
                    ?: return AgentToolOutcome(AgentToolStatus.BLOCKED, "Only public URLs provided by the user or returned by search can be read.")
                fetch(url)
                listOf(url)
            }
            else -> return AgentToolOutcome(AgentToolStatus.BLOCKED, "Unknown web tool.")
        }
        if (blocks.size == start) return AgentToolOutcome(AgentToolStatus.BUDGET_EXCEEDED, "Already attempted or tool budget exhausted; use previous results.")
        val result = blocks.last()
        if (result is ReplyBlock.SearchResult && result.failed || result is ReplyBlock.FetchResult && result.failed)
            return AgentToolOutcome(AgentToolStatus.FAILED, "The tool request failed; no evidence was obtained from this call.")
        val evidence = buildJsonArray {
            var remaining = 12000
            for (url in urls.distinct()) {
                val source = sources[url] ?: continue
                val item = buildJsonObject {
                    put("url", url); put("title", source.title.orEmpty()); put("date", source.pageAge.orEmpty())
                    put("text", material[url].orEmpty().take(remaining))
                }
                val size = item.toString().length
                if (size > remaining) continue
                add(item); remaining -= size
            }
        }
        return if (evidence.isEmpty()) {
            AgentToolOutcome(AgentToolStatus.EMPTY, "The web tool returned no usable evidence.")
        } else AgentToolOutcome.success(evidence.toString())
    }
}
