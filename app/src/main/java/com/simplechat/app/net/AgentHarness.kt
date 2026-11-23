package com.simplechat.app.net

/** The deliberately small tool surface exposed by chat turns. */
internal enum class AgentToolId {
    WEB_SEARCH,
    WEB_FETCH,
    IMAGE_GENERATION,
}

internal data class AgentCapabilities(
    val webSearch: Boolean = false,
    val webFetch: Boolean = false,
    val imageGeneration: Boolean = false,
) {
    fun exposes(tool: AgentToolId): Boolean = when (tool) {
        AgentToolId.WEB_SEARCH -> webSearch
        AgentToolId.WEB_FETCH -> webFetch
        AgentToolId.IMAGE_GENERATION -> imageGeneration
    }
}

internal data class AgentRuntimeState(
    val webConfigured: Boolean = false,
    val webEnabled: Boolean = false,
    val imageConfigured: Boolean = false,
    val imageExposed: Boolean = false,
) {
    val capabilities: AgentCapabilities
        get() = AgentCapabilities(
            webSearch = webEnabled,
            webFetch = webEnabled,
            imageGeneration = imageExposed,
        )

    fun instruction(): String = agentCapabilityInstruction(
        webConfigured, webEnabled, imageConfigured, imageExposed,
    )
}

internal data class AgentHarnessPolicy(
    val maxRounds: Int = 4,
    val maxWebRounds: Int = 2,
    val maxImageCalls: Int = 1,
    val maxToolCallsPerRound: Int = 4,
)

internal fun agentCapabilityInstruction(
    webConfigured: Boolean,
    webEnabled: Boolean,
    imageConfigured: Boolean,
    imageExposed: Boolean,
): String = buildString {
    append("Authoritative runtime tool state for this turn; it overrides earlier assistant claims. ")
    append("Only tools exposed in the current request may be called. ")
    append("web_search=")
    append(when {
        webEnabled -> "enabled"
        webConfigured -> "configured_but_disabled_by_user"
        else -> "unavailable"
    })
    append(", image_generation=")
    append(when {
        imageExposed -> "enabled"
        imageConfigured -> "configured_but_not_exposed_for_this_request"
        else -> "unavailable"
    })
    append(". Do not claim a disabled or unavailable tool can be used. ")
    if (webConfigured && !webEnabled) {
        append("The user disabled web search for this turn: do not search and do not say that you searched. ")
    }
    if (imageConfigured && !imageExposed) {
        append("Image generation is configured in the app, but it is not exposed for this non-image request; do not call it. ")
    }
    append("If the user asks about capabilities, report this state instead of inferring from previous messages.")
}

/** Runtime guard shared by tool exposure and execution. */
internal class AgentHarness(
    private val capabilities: AgentCapabilities,
    val policy: AgentHarnessPolicy = AgentHarnessPolicy(),
) {
    fun canOfferWeb(round: Int): Boolean =
        (capabilities.webSearch || capabilities.webFetch) && round < policy.maxWebRounds

    fun canOfferImage(imageCalls: Int): Boolean =
        capabilities.imageGeneration && imageCalls < policy.maxImageCalls

    fun knownTool(name: String): Boolean = when (name) {
        IndependentSearchName -> capabilities.webSearch
        IndependentFetchName -> capabilities.webFetch
        imageToolDefinition.name -> capabilities.imageGeneration
        else -> false
    }

    fun toolId(name: String): AgentToolId? = when (name) {
        IndependentSearchName -> AgentToolId.WEB_SEARCH
        IndependentFetchName -> AgentToolId.WEB_FETCH
        imageToolDefinition.name -> AgentToolId.IMAGE_GENERATION
        else -> null
    }
}
