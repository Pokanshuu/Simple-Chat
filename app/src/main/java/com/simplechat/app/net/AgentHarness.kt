package com.simplechat.app.net

import kotlinx.coroutines.CancellationException

/** The deliberately small tool surface exposed by chat turns. */
internal enum class AgentToolId {
    WEB_SEARCH,
    WEB_FETCH,
    IMAGE_GENERATION,
}

internal data class AgentToolRegistration(
    val id: AgentToolId,
    val wireNames: Set<String>,
    val enabled: Boolean,
)

/** Registry boundary for future skills, memory, worldbook and external tools. */
internal class AgentToolRegistry private constructor(
    private val registrations: List<AgentToolRegistration>,
) {
    fun isEnabled(name: String): Boolean = registrations.any { it.enabled && name in it.wireNames }

    fun registration(name: String): AgentToolRegistration? = registrations.firstOrNull { name in it.wireNames }

    fun enabled(): List<AgentToolRegistration> = registrations.filter { it.enabled }

    companion object {
        fun forCapabilities(capabilities: AgentCapabilities): AgentToolRegistry = AgentToolRegistry(listOf(
            AgentToolRegistration(AgentToolId.WEB_SEARCH, setOf(IndependentSearchName), capabilities.webSearch),
            AgentToolRegistration(AgentToolId.WEB_FETCH, setOf(IndependentFetchName), capabilities.webFetch),
            AgentToolRegistration(AgentToolId.IMAGE_GENERATION, setOf(imageToolDefinition.name), capabilities.imageGeneration),
        ))
    }
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
    val webFetchEnabled: Boolean = false,
    val imageConfigured: Boolean = false,
    val imageExposed: Boolean = false,
) {
    val capabilities: AgentCapabilities
        get() = AgentCapabilities(
            webSearch = webEnabled,
            webFetch = webFetchEnabled,
            imageGeneration = imageExposed,
        )

    fun instruction(): String = agentCapabilityInstruction(
        webConfigured, webEnabled, webFetchEnabled, imageConfigured, imageExposed,
    )
}

internal data class AgentHarnessPolicy(
    val maxRounds: Int = 4,
    val maxWebRounds: Int = 2,
    val maxImageCalls: Int = 1,
    val maxToolCallsPerRound: Int = 4,
)

internal enum class AgentTranscriptKind {
    MODEL_TURN,
    TOOL_CALL,
    TOOL_RESULT,
    STATE_CHANGE,
}

internal data class AgentTranscriptEvent(
    val kind: AgentTranscriptKind,
    val round: Int,
    val toolId: String? = null,
    val callId: String? = null,
    val status: String? = null,
    val detail: String = "",
)

internal enum class AgentToolStatus {
    SUCCESS,
    FAILED,
    BLOCKED,
    DISABLED,
    INVALID_ARGUMENTS,
    BUDGET_EXCEEDED,
}

internal data class AgentToolExecutionResult(
    val toolId: AgentToolId?,
    val status: AgentToolStatus,
    val message: String,
    val consumedImageCall: Boolean = false,
)

internal fun AgentToolExecutionResult.isUsableEvidence(): Boolean =
    status == AgentToolStatus.SUCCESS && message.isNotBlank()

/** Executes registered tools without exposing provider protocol details to the loop. */
internal class AgentToolExecutor(
    private val harness: AgentHarness,
    private val webAllowed: Boolean,
    private val imageAllowed: Boolean,
    private val session: WebResearchSession?,
    private val nativeSearch: (suspend (String) -> String)?,
    private val nativeFetch: (suspend (String) -> String)?,
    private val generateImage: (suspend (ImageToolInvocation) -> String)?,
) {
    private var imageCallConsumed = false

    suspend fun execute(name: String, arguments: String): AgentToolExecutionResult {
        val toolId = harness.toolId(name)
        if (toolId == null) return AgentToolExecutionResult(null, AgentToolStatus.BLOCKED, "Unknown tool is not registered.")
        if (!harness.knownTool(name)) return AgentToolExecutionResult(toolId, AgentToolStatus.DISABLED, disabledMessage(toolId))
        return when (toolId) {
            AgentToolId.IMAGE_GENERATION -> {
                if (!imageAllowed || imageCallConsumed || generateImage == null) {
                    AgentToolExecutionResult(toolId, AgentToolStatus.DISABLED, "Image tool is disabled for this turn. No image was generated.")
                } else {
                    val invocation = parseImageToolInvocation(arguments)
                        ?: return AgentToolExecutionResult(toolId, AgentToolStatus.INVALID_ARGUMENTS, "Invalid image tool arguments. No image was generated.")
                    imageCallConsumed = true
                    try {
                        AgentToolExecutionResult(toolId, AgentToolStatus.SUCCESS, generateImage(invocation), consumedImageCall = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        AgentToolExecutionResult(toolId, AgentToolStatus.FAILED, "Image generation failed. No image was attached. Tell the user they can retry.", consumedImageCall = true)
                    }
                }
            }
            AgentToolId.WEB_SEARCH, AgentToolId.WEB_FETCH -> {
                if (!webAllowed) AgentToolExecutionResult(toolId, AgentToolStatus.DISABLED, "This web tool is disabled for this turn. No web request was made.")
                else runWeb(name, arguments, toolId)
            }
        }
    }

    private suspend fun runWeb(name: String, arguments: String, toolId: AgentToolId): AgentToolExecutionResult {
        val parameter = if (name == IndependentSearchName) "query" else "url"
        val value = webToolArgument(arguments, parameter)
            ?: return AgentToolExecutionResult(toolId, AgentToolStatus.INVALID_ARGUMENTS, "Invalid tool arguments: supply $parameter.")
        return try {
            val output = when {
                session != null -> session.execute(name, arguments)
                name == IndependentSearchName && nativeSearch != null -> nativeSearch(ServiceApi.query(value))
                name == IndependentFetchName && nativeFetch != null -> nativeFetch(value)
                else -> "No executor is available for this web tool."
            }
            val status = when {
                output.startsWith("Invalid tool arguments") -> AgentToolStatus.INVALID_ARGUMENTS
                output.startsWith("This web tool is disabled") -> AgentToolStatus.DISABLED
                output.startsWith("The tool request failed") || output.startsWith("Web tool failed") ||
                    output.startsWith("No executor") -> AgentToolStatus.FAILED
                else -> AgentToolStatus.SUCCESS
            }
            AgentToolExecutionResult(toolId, status, output)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AgentToolExecutionResult(toolId, AgentToolStatus.FAILED, "Web tool failed. No evidence was obtained.")
        }
    }

    private fun disabledMessage(toolId: AgentToolId): String = when (toolId) {
        AgentToolId.IMAGE_GENERATION -> "Image tool is disabled for this turn. No image was generated."
        AgentToolId.WEB_SEARCH, AgentToolId.WEB_FETCH -> "This web tool is disabled for this turn. No web request was made."
    }
}

/** Ephemeral per-turn audit trail; persistence can be added without changing the loop API. */
internal class AgentTranscript {
    private val entries = mutableListOf<AgentTranscriptEvent>()
    val events: List<AgentTranscriptEvent> get() = entries.toList()

    fun add(event: AgentTranscriptEvent) {
        entries += event
    }
}

internal fun agentCapabilityInstruction(
    webConfigured: Boolean,
    webEnabled: Boolean,
    webFetchEnabled: Boolean = webEnabled,
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
    append(", web_fetch=")
    append(when {
        webFetchEnabled -> "enabled"
        webConfigured -> "configured_but_disabled_or_unavailable"
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
    private val registry = AgentToolRegistry.forCapabilities(capabilities)

    fun canOfferWeb(round: Int): Boolean =
        (capabilities.webSearch || capabilities.webFetch) && round < policy.maxWebRounds

    fun canOfferImage(imageCalls: Int): Boolean =
        capabilities.imageGeneration && imageCalls < policy.maxImageCalls

    fun knownTool(name: String): Boolean = registry.isEnabled(name)

    fun toolId(name: String): AgentToolId? = when (name) {
        IndependentSearchName -> AgentToolId.WEB_SEARCH
        IndependentFetchName -> AgentToolId.WEB_FETCH
        imageToolDefinition.name -> AgentToolId.IMAGE_GENERATION
        else -> null
    }
}
