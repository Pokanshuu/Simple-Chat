package com.simplechat.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun buildResponsesRequest(
    model: ModelInfo,
    messages: List<OutMessageDto>,
    thinkingEnabled: Boolean,
    reasoningEffort: String?,
    maxTokens: Int?,
    temperature: Double?,
): ResponsesRequestDto {
    val reasoning = when {
        thinkingEnabled -> model.resolveReasoningEffort(reasoningEffort ?: "medium")?.let {
            ResponsesReasoningDto(it, summary = "auto")
        }
        model.supportsThinking -> model.effortLevels?.firstOrNull { it in ReasoningWire.offLevels }
            ?.let { ResponsesReasoningDto(it) }
        else -> null
    }
    val output = model.clampOutputTokens(maxTokens?.coerceAtLeast(16))
    return ResponsesRequestDto(
        model = model.id,
        input = messages.map { ResponsesInputMessage(it.role, responsesInputContent(it.content)) },
        stream = true,
        maxOutputTokens = output,
        temperature = temperature.takeUnless { model.supportsThinking || thinkingEnabled },
        reasoning = reasoning,
    )
}

private fun responsesInputContent(content: JsonElement): JsonElement {
    val parts = content as? JsonArray ?: return content
    return JsonArray(parts.map { part ->
        val block = part as? JsonObject ?: error("Invalid input content block")
        when (block.text("type")) {
            "text" -> buildJsonObject {
                put("type", "input_text")
                put("text", checkNotNull(block.text("text")))
            }
            "image_url" -> buildJsonObject {
                val image = checkNotNull(block["image_url"] as? JsonObject)
                put("type", "input_image")
                put("image_url", checkNotNull(image.text("url")))
                image.text("detail")?.let { put("detail", it) }
            }
            "file" -> buildJsonObject {
                put("type", "input_file")
                put("file_id", checkNotNull(block.text("file_id")))
            }
            else -> error("Unsupported input content block")
        }
    })
}

internal fun responsesUrl(baseUrl: String): String {
    val base = requireNotNull((officialChatRoot(baseUrl) ?: baseUrl.trim()).toHttpUrlOrNull()) { "Invalid base URL" }
    val root = base.encodedPath.trimEnd('/').removeSuffix("/responses").removeSuffix("/chat/completions")
    return base.newBuilder().encodedPath("$root/responses").build().toString()
}

/** Tracks delivered text per output part so final snapshots can fill in omitted deltas. */
internal class ResponsesSseLineParser(private val json: Json) {
    private enum class Kind { SUMMARY, REASONING, TEXT, REFUSAL }
    private data class Part(val kind: Kind, val output: Int, val index: Int)
    private val delivered = mutableMapOf<Part, StringBuilder>()

    fun parse(line: String): SseLineParser.Result {
        if (!line.startsWith("data:")) return SseLineParser.Result.Ignore
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") return SseLineParser.Result.Done
        val event = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()
            ?: return SseLineParser.Result.Ignore
        val output = event.int("output_index") ?: 0
        val events = mutableListOf<StreamEvent>()
        val type = event.text("type")
        val kind = when (type?.removeSuffix(".delta")?.removeSuffix(".done")) {
            "response.reasoning_summary_text" -> Kind.SUMMARY
            "response.reasoning_text" -> Kind.REASONING
            "response.output_text" -> Kind.TEXT
            "response.refusal" -> Kind.REFUSAL
            else -> null
        }
        if (kind != null) {
            val index = event.int(if (kind == Kind.SUMMARY) "summary_index" else "content_index") ?: 0
            val part = Part(kind, output, index)
            if (type?.endsWith(".delta") == true) {
                append(part, event.text("delta"), events)
            } else {
                snapshot(part, event.text(if (kind == Kind.REFUSAL) "refusal" else "text"), events)
            }
        } else when (type) {
            "response.reasoning_summary_part.done" -> snapshot(
                Part(Kind.SUMMARY, output, event.int("summary_index") ?: 0),
                (event["part"] as? JsonObject)?.text("text"), events,
            )
            "response.output_item.done" -> item(event["item"] as? JsonObject, output, events)
            "response.completed", "response.incomplete", "response.done" -> {
                val response = event["response"] as? JsonObject
                (response?.get("output") as? JsonArray)?.forEachIndexed { index, value ->
                    item(value as? JsonObject, index, events)
                }
                response?.text("title")?.let { events += StreamEvent.Title(it) }
                val usage = response?.get("usage") as? JsonObject
                if (usage != null) events += StreamEvent.Usage(
                    usage.int("input_tokens") ?: 0,
                    usage.int("output_tokens") ?: 0,
                    (usage["input_tokens_details"] as? JsonObject)?.int("cached_tokens"),
                )
                val reason = (response?.get("incomplete_details") as? JsonObject)?.text("reason")
                events += StreamEvent.Stopped(when (reason) {
                    "max_output_tokens" -> "length"
                    null -> if (type == "response.incomplete") "incomplete" else "stop"
                    else -> reason
                })
            }
            "response.failed", "error" -> {
                val response = event["response"] as? JsonObject
                val error = (response?.get("error") as? JsonObject) ?: (event["error"] as? JsonObject)
                val message = error?.text("message") ?: event.text("message") ?: error?.text("code") ?: "Responses request failed"
                events += StreamEvent.Error(message, rawMessage = message)
            }
        }
        return if (events.isEmpty()) SseLineParser.Result.Ignore else SseLineParser.Result.Events(events)
    }

    private fun item(item: JsonObject?, output: Int, events: MutableList<StreamEvent>) {
        when (item?.text("type")) {
            "reasoning" -> {
                val summary = item["summary"] as? JsonArray
                val hasSummary = summary.orEmpty().any { (it as? JsonObject)?.text("text")?.isNotBlank() == true } ||
                    delivered.keys.any { it.output == output && it.kind == Kind.SUMMARY }
                if (hasSummary) {
                    summary?.forEachIndexed { index, value ->
                        val part = value as? JsonObject
                        if (part?.text("type") == "summary_text") {
                            snapshot(Part(Kind.SUMMARY, output, index), part.text("text"), events)
                        }
                    }
                } else {
                    (item["content"] as? JsonArray)?.forEachIndexed { index, value ->
                        val part = value as? JsonObject
                        if (part?.text("type") == "reasoning_text") {
                            snapshot(Part(Kind.REASONING, output, index), part.text("text"), events)
                        }
                    }
                }
            }
            "message" -> (item["content"] as? JsonArray)?.forEachIndexed { index, value ->
                val part = value as? JsonObject
                when (part?.text("type")) {
                    "output_text" -> {
                        snapshot(Part(Kind.TEXT, output, index), part.text("text"), events)
                        events += citationEvents(part["annotations"])
                    }
                    "refusal" -> snapshot(Part(Kind.REFUSAL, output, index), part.text("refusal"), events)
                }
            }
        }
    }

    private fun snapshot(part: Part, full: String?, events: MutableList<StreamEvent>) {
        if (full == null) return
        val prefix = delivered[part]
        if (prefix == null || full.startsWith(prefix)) append(part, full.substring(prefix?.length ?: 0), events)
    }

    private fun append(part: Part, text: String?, events: MutableList<StreamEvent>) {
        if (text.isNullOrEmpty()) return
        val thinking = part.kind == Kind.SUMMARY || part.kind == Kind.REASONING
        val previousPart = part !in delivered && delivered.keys.any {
            it.kind == Kind.SUMMARY || it.kind == Kind.REASONING
        }
        delivered.getOrPut(part) { StringBuilder() }.append(text)
        events += if (thinking) StreamEvent.Reasoning(if (previousPart) "\n\n$text" else text) else StreamEvent.Content(text)
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
