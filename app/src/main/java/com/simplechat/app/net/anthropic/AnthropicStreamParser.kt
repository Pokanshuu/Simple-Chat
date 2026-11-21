package com.simplechat.app.net.anthropic

import com.simplechat.app.net.Citation
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.StreamEvent
import com.simplechat.app.net.WebSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Anthropic 兼容通道的 SSE 行解析器 —— 与 [com.simplechat.app.net.SseLineParser] 同构：
 * **纯函数、无 IO**，能穷举单测（见 `AnthropicStreamParserTest`）。
 *
 * ### 与 OpenAI 通道的四处结构性差异
 *
 * | | OpenAI `/chat/completions` | Anthropic `/messages` |
 * |---|---|---|
 * | 结束 | `data: [DONE]` | `message_stop` 事件，**不发** `[DONE]` |
 * | 增量 | `choices[0].delta` | 按 `index` 分块的 `content_block_delta` |
 * | 结构 | 只有正文与思考 | 正文 / 思考 / 工具调用 / 工具结果**交错** |
 * | 结束原因 | `finish_reason` | `message_delta.delta.stop_reason` |
 *
 * ### 容错原则
 * 未知块类型、缺字段、坏 JSON 一律**静默跳过**，绝不因一行坏数据中断整条流
 * —— 与 OpenAI 通道同一条原则。唯一的例外是 `error` 帧，它必须报出来，
 * 否则用户看到的是"流自然结束了、内容却是空的"。
 */
internal class AnthropicStreamParser(private val json: Json, private val acceptImageTool: Boolean = false) {

    sealed interface Result {
        data class Events(val events: List<StreamEvent>) : Result

        /** `message_stop`：整条回复正常结束。 */
        data object Done : Result

        /** 空行、`event:` 行、注释、ping、未知类型 —— 忽略。 */
        data object Ignore : Result
    }

    fun parse(line: String): Result {
        if (line.isEmpty()) return Result.Ignore
        // SSE 注释 / 保活，例如 ": keep-alive"
        if (line.startsWith(":")) return Result.Ignore
        /*
         * `event: content_block_delta` 这行**不消费**：负载里的 `type` 与事件名恒等，
         * 只认它一处就够，少一个"两处不一致时信谁"的判断。
         */
        if (!line.startsWith(DATA_PREFIX)) return Result.Ignore

        val data = line.substring(DATA_PREFIX.length).trim()
        if (data.isEmpty()) return Result.Ignore
        // 官方不发 [DONE]，但网关可能补一个；收到就按结束处理，不吃亏
        if (data == DONE) return Result.Done

        val obj = runCatching { json.parseToJsonElement(data) }.getOrNull() as? JsonObject
            ?: return Result.Ignore

        // 错误对象优先：它可能出现在任何 type 名下，甚至没有 type
        errorMessage(obj)?.let { return Result.Events(listOf(StreamEvent.Error(it))) }

        return when (obj.string("type")) {
            "message_stop" -> Result.Done
            "message_start" -> Result.Events(messageStart(obj))
            "content_block_start" -> Result.Events(blockStart(obj))
            "content_block_delta" -> Result.Events(blockDelta(obj))
            "content_block_stop" -> Result.Events(
                listOfNotNull(obj.int("index")?.let { StreamEvent.BlockStop(it) }),
            )
            "message_delta" -> Result.Events(messageDelta(obj))
            // ping 以及协议新增的未知事件：跳过，不影响后续内容与正常结束
            else -> Result.Ignore
        }
    }

    /** 从非 2xx 响应体里提取可读错误信息（与 OpenAI 通道同形）。 */
    fun parseErrorMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        return obj?.let { errorMessage(it) } ?: raw.take(MAX_RAW_ERROR_LENGTH)
    }

    // ── 事件 ──────────────────────────────────────────────

    private fun messageStart(obj: JsonObject): List<StreamEvent> {
        val usage = usage(obj.obj("message")?.obj("usage") ?: obj.obj("usage"))
        return usage?.let { listOf(it) }.orEmpty()
    }

    private fun messageDelta(obj: JsonObject): List<StreamEvent> {
        val events = mutableListOf<StreamEvent>()
        val delta = obj.obj("delta")
        delta?.string("title")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            events += StreamEvent.Title(it)
        }
        delta?.string("stop_reason")?.let { events += StreamEvent.Stopped(it) }
        usage(obj.obj("usage"))?.let { events += it }
        return events
    }

    private fun blockDelta(obj: JsonObject): List<StreamEvent> {
        val index = obj.int("index") ?: return emptyList()
        val delta = obj.obj("delta") ?: return emptyList()
        val event = when (delta.string("type")) {
            "text_delta" -> delta.string("text")?.let { StreamEvent.TextDelta(index, it) }
            "thinking_delta" -> delta.string("thinking")?.let { StreamEvent.ThinkingDelta(index, it) }
            "signature_delta" -> delta.string("signature")?.let {
                StreamEvent.SignatureDelta(index, it)
            }
            "citations_delta" -> delta.obj("citation")?.let { readCitation(it) }?.let {
                StreamEvent.CitationDelta(index, it)
            }
            "input_json_delta" -> if (acceptImageTool) delta.string("partial_json")?.let { StreamEvent.ToolCallDelta(index, null, null, it) } else null
            else -> null
        }
        return listOfNotNull(event)
    }

    private fun blockStart(obj: JsonObject): List<StreamEvent> {
        val index = obj.int("index") ?: return emptyList()
        val block = obj.obj("content_block") ?: return emptyList()
        if (acceptImageTool && block.string("type") == "tool_use") {
            val input = block.obj("input")?.takeIf { it.isNotEmpty() }?.toString()
            return listOf(StreamEvent.ToolCallDelta(index, block.string("id"), block.string("name"), input))
        }
        val parsed = readBlock(block) ?: return emptyList()
        return listOf(StreamEvent.BlockStart(index, parsed))
    }

    // ── 内容块 ────────────────────────────────────────────

    private fun readBlock(block: JsonObject): ReplyBlock? = when (block.string("type")) {
        "text" -> {
            val text = block.string("text")
            if (text == null) null
            else ReplyBlock.Text(
                text = text,
                citations = (block["citations"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.let(::readCitation) }
                    .orEmpty(),
            )
        }

        "thinking" -> block.string("thinking")?.let {
            ReplyBlock.Thinking(thinking = it, signature = block.string("signature"))
        }

        "server_tool_use" -> readToolUse(block)

        "web_search_tool_result" -> readSearchResult(block)

        "web_fetch_tool_result" -> readFetchResult(block)

        // 客户端工具（tool_use）本项目不执行，也不存 —— 与官方 demo 一致
        else -> null
    }

    private fun readToolUse(block: JsonObject): ReplyBlock? {
        val id = block.string("id") ?: return null
        val name = block.string("name") ?: return null
        val kind = block.string("kind") ?: return null
        val input = block.obj("input") ?: return null
        val action = input.string("action") ?: return null
        return when (action) {
            "search" -> ReplyBlock.ToolUse(
                id = id,
                name = name,
                kind = kind,
                action = action,
                queries = (input["queries"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty(),
            )
            "open_page", "read_link" -> ReplyBlock.ToolUse(
                id = id,
                name = name,
                kind = kind,
                action = action,
                url = input.string("url").orEmpty(),
            )
            "find_in_page" -> ReplyBlock.ToolUse(
                id = id,
                name = name,
                kind = kind,
                action = action,
                url = input.string("url").orEmpty(),
                pattern = input.string("pattern").orEmpty(),
            )
            else -> null
        }
    }

    private fun readSearchResult(block: JsonObject): ReplyBlock? {
        val toolUseId = block.string("tool_use_id") ?: return null
        val kind = block.string("kind") ?: return null
        val content = block["content"] as? JsonArray ?: return null
        var failed = false
        val results = content.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            when (obj.string("type")) {
                "web_search_tool_result_error" -> {
                    failed = true
                    null
                }
                "web_search_result" -> {
                    val url = obj.string("url") ?: return@mapNotNull null
                    WebSource(
                        url = url,
                        title = obj.string("title"),
                        siteName = obj.string("site_name"),
                        siteIcon = obj.string("site_icon"),
                        snippet = obj.string("snippet"),
                        pageAge = obj.string("page_age"),
                        imageUrl = obj.string("image_url"),
                    )
                }
                else -> null
            }
        }
        // 搜索结果为空且不是失败（服务端"成功但没搜到"）是合法状态，照原样保留
        return ReplyBlock.SearchResult(
            toolUseId = toolUseId,
            kind = kind,
            results = results,
            failed = failed,
        )
    }

    private fun readFetchResult(block: JsonObject): ReplyBlock? {
        val toolUseId = block.string("tool_use_id") ?: return null
        val kind = block.string("kind") ?: return null
        val content = block.obj("content") ?: return null
        val signature = block.string("signature")?.takeIf { it.isNotBlank() }
        return when (content.string("type")) {
            "web_fetch_result" -> ReplyBlock.FetchResult(
                toolUseId = toolUseId,
                kind = kind,
                url = content.string("url").orEmpty(),
                title = content.obj("content")?.string("title"),
                publishedAt = content.string("published_at"),
                signature = signature,
            )
            "web_fetch_tool_result_error" -> ReplyBlock.FetchResult(
                toolUseId = toolUseId,
                kind = kind,
                failed = true,
                signature = signature,
            )
            // 未知形态的结果块：没有可展示的链接，也没有可回传的签名，丢弃
            else -> null
        }
    }

    private fun readCitation(obj: JsonObject): Citation? {
        if (obj.string("type") != "web_search_result_location") return null
        val url = obj.string("url") ?: return null
        return Citation(
            url = url,
            title = obj.string("title"),
            citedText = obj.string("cited_text").orEmpty(),
        )
    }

    // ── 小工具 ────────────────────────────────────────────

    /**
     * 用量统计。
     *
     * `message_start` 里只有输入侧、`message_delta` 里只有输出侧（且 `output_tokens`
     * 是**全轮累计值**），所以两个事件各报一半 —— 不能要求四个字段齐全才算数，
     * 否则本项目的用量展示永远是空的。
     */
    private fun usage(obj: JsonObject?): StreamEvent.Usage? {
        obj ?: return null
        val input = obj.int("input_tokens")
        val output = obj.int("output_tokens")
        if (input == null && output == null) return null
        val cacheRead = obj.int("cache_read_input_tokens")
        return StreamEvent.Usage(
            promptTokens = (input ?: 0) +
                (obj.int("cache_creation_input_tokens") ?: 0) +
                (cacheRead ?: 0),
            completionTokens = output ?: 0,
            cacheHitTokens = cacheRead,
        )
    }

    private fun errorMessage(obj: JsonObject): String? =
        obj.obj("error")?.string("message")?.takeIf { it.isNotBlank() }

    /** 只认**字符串**原语：`null` / 数字 / 布尔都不算 —— 免得把 `"title": 0` 读成 `"0"`。 */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private companion object {
        const val DATA_PREFIX = "data:"
        const val DONE = "[DONE]"
        const val MAX_RAW_ERROR_LENGTH = 300
    }
}
