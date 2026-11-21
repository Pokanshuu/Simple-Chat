package com.simplechat.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A completed reply may invoke at most one image generation. */
internal class ImageToolCall {
    private var index: Int? = null
    private val arguments = StringBuilder()
    private var invalid = false
    private var finished = false

    fun consume(event: StreamEvent) {
        when (event) {
            is StreamEvent.ToolCallDelta -> {
                if (event.name == imageToolDefinition.name && index == null) index = event.index
                if (index != event.index) return
                if (event.arguments != null) {
                    if (event.arguments.length > 8192 - arguments.length) invalid = true
                    else arguments.append(event.arguments)
                }
            }
            is StreamEvent.Stopped -> finished = event.reason in setOf("tool_calls", "tool_use")
            else -> Unit
        }
    }

    fun prompt(): String? {
        if (!finished || index == null) return null
        val fields = if (invalid) null else runCatching { Json.parseToJsonElement(arguments.toString()) as? JsonObject }.getOrNull()
        val prompt = (fields?.get("prompt") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        require(!prompt.isNullOrEmpty() && fields?.keys == setOf("prompt")) { "Invalid image generation tool arguments" }
        return prompt
    }
}
