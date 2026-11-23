package com.simplechat.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class ImageToolOperation { CREATE, EDIT }

internal data class ImageToolInvocation(val prompt: String, val operation: ImageToolOperation)

/** A completed reply may invoke at most one image generation. */
internal class ImageToolCall {
    private var index: Int? = null
    private val arguments = StringBuilder()
    private var invalid = false
    private var stopReason: String? = null
    val endedWithoutCall: Boolean get() = index == null && stopReason in setOf("stop", "end_turn")

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
            is StreamEvent.Stopped -> stopReason = event.reason
            else -> Unit
        }
    }

    fun invocation(): ImageToolInvocation? {
        if (stopReason !in setOf("tool_calls", "tool_use") || index == null) return null
        return requireNotNull(if (invalid) null else parseImageToolInvocation(arguments.toString())) {
            "Invalid image generation tool arguments"
        }
    }
}

internal fun parseImageToolInvocation(arguments: String): ImageToolInvocation? {
    val fields = runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull() ?: return null
    if (fields.keys != setOf("prompt", "operation")) return null
    val prompt = (fields["prompt"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        ?.takeIf { it.isNotEmpty() } ?: return null
    val operation = when ((fields["operation"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
        "create" -> ImageToolOperation.CREATE
        "edit" -> ImageToolOperation.EDIT
        else -> return null
    }
    return ImageToolInvocation(prompt, operation)
}
