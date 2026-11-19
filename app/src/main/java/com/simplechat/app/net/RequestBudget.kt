package com.simplechat.app.net

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.TokenEstimate
import com.simplechat.app.net.anthropic.AnthropicRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Estimates the actual outgoing payload, including system prompts, attachments and continuation. */
internal fun estimatedInputTokens(request: ChatRequestDto): Long = estimatedInputTokens(request.messages)

internal fun estimatedInputTokens(messages: List<OutMessageDto>): Long =
    messages.sumOf { 8L + contentTokens(it.content) }

internal fun estimatedInputTokens(request: AnthropicRequest): Long =
    TokenEstimate.of(request.system).toLong() +
        request.messages.sumOf { 8L + contentTokens(it.content) }

private fun contentTokens(content: JsonElement): Long = when (content) {
    is JsonPrimitive -> TokenEstimate.of(content.content).toLong()
    is JsonArray -> content.sumOf { contentTokens(it) }
    is JsonObject -> when ((content["type"] as? JsonPrimitive)?.content) {
        // Base64 is transport data, not text tokens.
        "image", "image_url" -> 1024L
        else -> content.entries.sumOf { (key, value) ->
            if (key in setOf("type", "signature")) 0L else contentTokens(value)
        }
    }
}

internal fun validateRequestBudget(inputTokens: Long, maxTokens: Int?, contextWindow: Int?, maxOutputTokens: Int? = null) {
    if (contextWindow == null || contextWindow <= 0) return
    val outputTokens = maxTokens?.takeIf { it > 0 } ?: minOf(4096, contextWindow / 8, maxOutputTokens ?: Int.MAX_VALUE)
    if (inputTokens + outputTokens > contextWindow.toLong()) {
        throw AppTextException(
            R.string.error_context_budget,
            inputTokens.toString(), outputTokens.toString(), contextWindow.toString(),
        )
    }
}
