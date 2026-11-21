package com.simplechat.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * SSE 行解析器。
 *
 * 刻意做成**纯函数、无 IO**：SSE 是整条链路里最容易出边界问题的一环
 * （心跳、注释、分片、脏数据、`[DONE]`、流中错误对象），
 * 抽出来才能用单测穷举，而不是靠联调碰运气。见 `SseLineParserTest`。
 */
internal class SseLineParser(private val json: Json) {

    sealed interface Result {
        /** 解析出若干事件。 */
        data class Events(val events: List<StreamEvent>) : Result

        /** 收到 `[DONE]`，流正常结束。 */
        data object Done : Result

        /** 空行、心跳注释、未知字段 —— 忽略。 */
        data object Ignore : Result
    }

    fun parse(line: String): Result {
        // 空行
        if (line.isEmpty()) return Result.Ignore
        // SSE 注释/心跳，例如 ": keep-alive"
        if (line.startsWith(":")) return Result.Ignore
        // 只处理 data 字段；event: / id: / retry: 本项目不使用
        if (!line.startsWith(DATA_PREFIX)) return Result.Ignore

        val data = line.substring(DATA_PREFIX.length).trim()
        if (data.isEmpty()) return Result.Ignore
        if (data == DONE) return Result.Done

        return Result.Events(decode(data))
    }

    /** 解析一段 JSON 负载。脏数据静默丢弃，绝不因为一行坏数据中断整条流。 */
    private fun decode(data: String): List<StreamEvent> {
        val element = runCatching { json.parseToJsonElement(data) }.getOrNull()
        val obj = element as? JsonObject ?: return emptyList()

        // 必须先判错误对象：ChatChunkDto 开了 ignoreUnknownKeys，
        // 会把 {"error":{...}} 成功解析成一个空 choices 的 chunk，
        // 从而让错误被静默吞掉。
        extractErrorMessage(obj)?.let { return listOf(StreamEvent.Error(it)) }

        val chunk = runCatching {
            json.decodeFromJsonElement(ChatChunkDto.serializer(), obj)
        }.getOrNull() ?: return emptyList()

        val events = mutableListOf<StreamEvent>()

        chunk.choices.firstOrNull()?.delta?.let { delta ->
            delta.toolCalls.forEach { call ->
                events += StreamEvent.ToolCallDelta(call.index, call.id, call.function?.name, call.function?.arguments)
            }
            events += citationEvents(delta.annotations)
            delta.title?.takeIf(String::isNotBlank)?.let { events += StreamEvent.Title(it) }
            delta.displayReasoning()?.let { events += StreamEvent.Reasoning(it) }
            // 有些兼容端点通过独立字段返回拒绝文本。
            (delta.content?.takeIf(String::isNotEmpty) ?: delta.refusal.refusalDelta())
                ?.let { events += StreamEvent.Content(it) }
        }

        chunk.usage?.let {
            events += StreamEvent.Usage(
                promptTokens = it.promptTokens ?: 0,
                completionTokens = it.completionTokens ?: 0,
                cacheHitTokens = it.cacheHitTokens ?: it.promptTokensDetails?.cachedTokens,
            )
        }

        chunk.choices.firstOrNull()?.finishReason?.let { events += StreamEvent.Stopped(it) }

        return events
    }

    private fun DeltaDto.displayReasoning(): String? {
        // 兼容网关可能在同一帧重复发送别名。沿用传统字段优先级，只派发一份。
        val primary = reasoningContent.stringDelta()
        val alias = reasoning.stringDelta()
        val textAlias = reasoningText.stringDelta()
        if (primary != null && primary.isNotBlank()) return primary
        if (alias != null && alias.isNotBlank()) return alias
        if (textAlias != null && textAlias.isNotBlank()) return textAlias

        val summary = reasoningSummary.summaryDelta()
            ?: (reasoning as? JsonObject)?.get("summary").summaryDelta()
            ?: (reasoningContent as? JsonObject)?.get("summary").summaryDelta()
            ?: (reasoningDetails as? JsonArray)?.let { details ->
                joinDeltas(details) { detail ->
                    val item = detail as? JsonObject
                    item?.get("summary").summaryDelta()
                        ?.takeIf { item?.get("type").stringDelta() == "reasoning.summary" }
                }
            }
        // 独立的空白增量可能是词间空格或换行，留给累加器合并。
        return summary ?: primary ?: alias ?: textAlias
    }

    private fun JsonElement?.summaryDelta(): String? = when (this) {
        is JsonArray -> joinDeltas(this) { part ->
            val item = part as? JsonObject
            part.stringDelta() ?: item?.get("text").stringDelta()
                ?.takeIf { item?.get("type").stringDelta() == "summary_text" }
        }
        is JsonObject -> this["text"].stringDelta()
            ?.takeIf { this["type"].stringDelta() == "summary_text" }
        else -> stringDelta()
    }

    private fun joinDeltas(parts: JsonArray, extract: (JsonElement) -> String?): String? =
        buildString { parts.forEach { part -> extract(part)?.let { append(it) } } }.takeIf(String::isNotEmpty)

    private fun JsonElement?.refusalDelta(): String? {
        val item = this as? JsonObject ?: return stringDelta()
        val type = item["type"].stringDelta()
        return item["refusal"].stringDelta()
            ?: item["text"].stringDelta()?.takeIf { type == "refusal" }
    }

    private fun JsonElement?.stringDelta(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotEmpty)

    private fun extractErrorMessage(obj: JsonObject): String? =
        (obj["error"] as? JsonObject)
            ?.get("message")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    /** 从非 2xx 响应体里提取可读错误信息。 */
    fun parseErrorMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        return obj?.let { extractErrorMessage(it) } ?: raw.take(MAX_RAW_ERROR_LENGTH)
    }

    private companion object {
        const val DATA_PREFIX = "data:"
        const val DONE = "[DONE]"
        const val MAX_RAW_ERROR_LENGTH = 300
    }
}

/** Chat Completions nests the citation payload. Responses puts the same fields on the annotation. */
internal fun citationEvents(annotations: JsonElement?): List<StreamEvent.CitationDelta> =
    (annotations as? JsonArray).orEmpty().mapNotNull { value ->
        val annotation = value as? JsonObject ?: return@mapNotNull null
        if ((annotation["type"] as? JsonPrimitive)?.contentOrNull != "url_citation") return@mapNotNull null
        val fields = annotation["url_citation"] as? JsonObject ?: annotation
        val url = (fields["url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@mapNotNull null
        StreamEvent.CitationDelta(0, Citation(url, (fields["title"] as? JsonPrimitive)?.contentOrNull))
    }
