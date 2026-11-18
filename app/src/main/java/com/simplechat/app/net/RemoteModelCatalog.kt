package com.simplechat.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal data class RemoteModelCatalog(val ids: List<String>, val contextWindows: Map<String, Int>)

internal fun parseRemoteModelCatalog(json: Json, raw: String): RemoteModelCatalog {
    val response = json.decodeFromString<ModelListDto>(raw)
    val ids = linkedSetOf<String>()
    val windows = buildMap {
        for ((entry, key) in response.data.map { it to it.id } + response.models.map { it to (it.id ?: it.name) }) {
            val id = key?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            ids += id
            val context = sequenceOf(
                entry.contextLength, entry.contextWindow, entry.inputTokenLimit, entry.snakeInputTokenLimit,
                (entry.topProvider as? JsonObject)?.get("context_length"),
            ).mapNotNull { (it as? JsonPrimitive)?.intOrNull?.takeIf { tokens -> tokens > 0 } }.minOrNull()
            if (context != null) put(id, minOf(get(id) ?: context, context))
        }
    }
    return RemoteModelCatalog(ids.toList(), windows)
}
