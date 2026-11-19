package com.simplechat.app.data

import com.simplechat.app.net.ModelCapabilityOverride
import com.simplechat.app.net.RemoteModelMetadata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Persisted profiles contain ciphertext only. Only the active key is decrypted for requests. */
@Serializable
data class ApiProfile(
    val id: String,
    val name: String,
    val providerId: String,
    val baseUrl: String,
    val modelId: String,
    val apiKeyEncrypted: String = "",
    val apiStyleId: String = "AUTO",
    val modelIds: List<String> = emptyList(),
    val modelContextWindows: Map<String, Int> = emptyMap(),
    val modelMetadata: Map<String, RemoteModelMetadata> = emptyMap(),
    val modelOverrides: Map<String, ModelCapabilityOverride> = emptyMap(),
    val reasoningLevelId: String = "OFF",
    val temperature: Float = 1f,
    val topP: Float = 1f,
    val maxTokens: Int = 0,
    val webSearch: Boolean = false,
    val lastUsedAt: Long = 0,
    val searchApi: ServiceApiConfig = ServiceApiConfig(),
    val filesApi: ServiceApiConfig = ServiceApiConfig(),
) {
    // Neither plaintext nor ciphertext should appear in logs or UI debug output.
    override fun toString(): String = "ApiProfile(id=$id, name=$name, providerId=$providerId)"
}

internal val apiProfileJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Reject damaged registries rather than silently overwriting saved credentials with an empty list. */
internal fun decodeApiProfiles(raw: String): List<ApiProfile> =
    apiProfileJson.decodeFromString<List<ApiProfile>>(raw).also { profiles ->
        require(profiles.isNotEmpty() && profiles.map { it.id }.distinct().size == profiles.size)
    }
