package com.simplechat.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class RemoteModelMetadata(
    val name: String? = null,
    val supportsVision: Boolean? = null,
    val supportsThinking: Boolean? = null,
    val effortLevels: List<String>? = null,
    val reasoningWire: ReasoningWire? = null,
    val maxOutputTokens: Int? = null,
)

@Serializable
data class ModelCapabilityOverride(
    val vision: Boolean? = null,
    val thinking: Boolean? = null,
    val contextWindow: Int? = null,
    val reasoningWire: ReasoningWire? = null,
)

internal data class RemoteModelCatalog(
    val ids: List<String>,
    val contextWindows: Map<String, Int>,
    val metadata: Map<String, RemoteModelMetadata>,
)

internal fun parseRemoteModelCatalog(json: Json, raw: String): RemoteModelCatalog {
    val response = json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid model listing")
    val data = response["data"] as? JsonArray
    val listed = data?.map { null to it } ?: when (val models = response["models"]) {
        is JsonArray -> models.map { null to it }
        is JsonObject -> models.entries.map { (id, entry) -> id to entry }
        else -> error("Model listing has no data array or models collection")
    }
    val ids = linkedSetOf<String>()
    val windows = mutableMapOf<String, Int>()
    val metadata = mutableMapOf<String, RemoteModelMetadata>()
    for ((key, rawEntry) in listed) {
        val entry = rawEntry as? JsonObject ?: continue
        val id = key?.trim()?.takeIf(String::isNotEmpty) ?: entry["id"].label()
            ?: entry["name"].label()?.takeIf { response["data"] !is JsonArray } ?: continue
        if (id !in ids && ids.size >= 500) continue
        ids += id
        val limit = entry["limit"] as? JsonObject
        val route = entry["top_provider"] as? JsonObject
        val architecture = entry["architecture"] as? JsonObject
        val capabilities = entry["capabilities"] as? JsonObject
        // A route can impose a lower limit than the advertised upstream model.
        val context = capacity(
            entry["contextWindow"], entry["context_window"], entry["context_length"],
            entry["max_input_tokens"], entry["inputTokenLimit"], entry["input_token_limit"],
            limit?.get("context"), route?.get("context_length"),
        )
        if (context != null) windows.merge(id, context, ::minOf)
        val output = capacity(
            entry["maxOutputTokens"], entry["max_output_tokens"], entry["maxTokens"],
            entry["max_tokens"], entry["max_completion_tokens"], entry["outputTokenLimit"],
            limit?.get("output"), route?.get("max_completion_tokens"),
        )
        val modalities = (entry["input_modalities"] as? JsonArray)
            ?: (entry["inputModalities"] as? JsonArray)
            ?: (architecture?.get("input_modalities") as? JsonArray)
        val vision = entry["supports_vision"].flag() ?: entry["supportsVision"].flag()
            ?: capabilities?.get("vision").flag()
            ?: modalities?.any { it.label()?.lowercase() in setOf("image", "image_url") }
        val effort = entry["effort"] as? JsonObject
        val levels = ((effort?.get("supported_levels") ?: entry["reasoning_effort_levels"]) as? JsonArray)
            ?.mapNotNull { it.label()?.lowercase() }?.distinct()
        val parameters = (entry["supported_parameters"] as? JsonArray)?.mapNotNull { it.label() }.orEmpty()
        val thinking = entry["supports_thinking"].flag() ?: entry["supportsThinking"].flag()
            ?: capabilities?.get("reasoning").flag() ?: capabilities?.get("thinking").flag()
            ?: levels?.any { it !in ReasoningWire.offLevels }
            ?: true.takeIf { parameters.any { it in setOf("reasoning", "reasoning_effort", "thinking", "enable_thinking") } }
        val wire = when {
            "thinking" in parameters -> ReasoningWire.DEEPSEEK
            "enable_thinking" in parameters -> ReasoningWire.ENABLE_THINKING
            "reasoning" in parameters -> ReasoningWire.REASONING_OBJECT
            "reasoning_effort" in parameters -> ReasoningWire.EFFORT
            else -> null
        }
        val model = RemoteModelMetadata(
            name = entry["display_name"].label() ?: entry["displayName"].label()
                ?: entry["name"].label()?.takeIf { it != id },
            supportsVision = vision,
            supportsThinking = thinking,
            effortLevels = levels,
            reasoningWire = wire,
            maxOutputTokens = output,
        )
        if (model != RemoteModelMetadata()) metadata.putIfAbsent(id, model)
    }
    return RemoteModelCatalog(ids.toList(), windows, metadata)
}

private fun JsonElement?.label(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }
    ?.content?.trim()?.takeIf(String::isNotEmpty)

private fun JsonElement?.flag(): Boolean? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

private fun capacity(vararg values: JsonElement?): Int? = values.mapNotNull {
    (it as? JsonPrimitive)?.intOrNull?.takeIf { tokens -> tokens > 0 }
}.minOrNull()
