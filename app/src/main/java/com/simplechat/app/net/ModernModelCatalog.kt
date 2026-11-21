package com.simplechat.app.net

/** Model IDs and limits from the OpenAI and Claude API model references. */
private val standardEffort = listOf("low", "medium", "high")
private val extendedGptEffort = standardEffort + "xhigh"
private val maxGptEffort = extendedGptEffort + "max"
private val claudeExtendedEffort = standardEffort + "max"
private val claudeLatestEffort = standardEffort + listOf("xhigh", "max")
private val datedSnapshot = Regex("-\\d{4}-?\\d{2}-?\\d{2}$")

internal data class ClaudeAdaptiveModel(
    val contextWindow: Int,
    val outputTokens: Int,
    val efforts: List<String>,
    val acceptsTemperature: Boolean = false,
    val explicitDisplay: Boolean = true,
    val canDisableThinking: Boolean = true,
)

internal fun claudeAdaptiveModel(id: String): ClaudeAdaptiveModel? {
    val canonical = id.lowercase().removePrefix("anthropic/").replace(datedSnapshot, "").replace('.', '-')
    return when (canonical) {
        "claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-2", "claude-mythos-5-2",
        "claude-fable-5-1", "claude-mythos-5-1", "claude-fable-5", "claude-mythos-5" ->
            ClaudeAdaptiveModel(1_000_000, 128_000, claudeLatestEffort, canDisableThinking = false)
        "claude-opus-5", "claude-sonnet-5", "claude-haiku-5-5", "claude-opus-4-7", "claude-opus-4-8" ->
            ClaudeAdaptiveModel(1_000_000, 128_000, claudeLatestEffort)
        "claude-mythos-preview" ->
            ClaudeAdaptiveModel(1_000_000, 128_000, claudeExtendedEffort, canDisableThinking = false)
        "claude-sonnet-4-6" -> ClaudeAdaptiveModel(1_000_000, 64_000, claudeExtendedEffort,
            acceptsTemperature = true, explicitDisplay = false)
        "claude-opus-4-6" -> ClaudeAdaptiveModel(1_000_000, 128_000, claudeExtendedEffort,
            acceptsTemperature = true, explicitDisplay = false)
        else -> null
    }
}

internal fun modernClaudeBaseline(id: String): ModelInfo? {
    claudeAdaptiveModel(id)?.let { spec ->
        return ModelInfo(id, supportsVision = true, supportsThinking = true, contextWindow = spec.contextWindow,
            maxOutputTokens = spec.outputTokens, effortLevels = spec.efforts, reasoningWire = ReasoningWire.EFFORT)
    }
    val canonical = id.removePrefix("anthropic/").replace(datedSnapshot, "").replace('.', '-')
    return when (canonical) {
        "claude-opus-4-5", "claude-sonnet-4-5", "claude-haiku-4-5", "claude-opus-4-1", "claude-opus-4", "claude-sonnet-4", "claude-3-7-sonnet" ->
            ModelInfo(id, supportsVision = true, supportsThinking = true, contextWindow = 200_000,
                maxOutputTokens = when (canonical) {
                    "claude-haiku-4-5", "claude-opus-4-1", "claude-opus-4" -> 32_000
                    else -> 64_000
                },
                reasoningWire = ReasoningWire.EFFORT)
        else -> null
    }
}

internal fun normalizedOpenAiModel(id: String): String = id.lowercase().removePrefix("openai/").replace(datedSnapshot, "")

internal fun modernGptBaseline(id: String): ModelInfo? {
    val canonical = normalizedOpenAiModel(id)
    fun gpt(window: Int, output: Int, efforts: List<String>) = ModelInfo(id, supportsVision = true,
        supportsThinking = true, contextWindow = window, maxOutputTokens = output, effortLevels = efforts,
        reasoningWire = ReasoningWire.EFFORT, completionTokenField = true)
    return when (canonical) {
        "gpt-6.2-sol", "gpt-6.2-astra", "gpt-6.1-sol", "gpt-6-astra" ->
            gpt(1_050_000, 128_000, maxGptEffort)
        "gpt-6.1-terra", "gpt-6-sol", "gpt-6-luna", "gpt-5.6", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna" ->
            gpt(1_050_000, 128_000, listOf("none") + maxGptEffort)
        "chat-latest" -> ModelInfo(id, supportsVision = true, contextWindow = 400_000,
            maxOutputTokens = 128_000, reasoningWire = ReasoningWire.EFFORT, completionTokenField = true)
        "gpt-5.5" -> gpt(1_050_000, 128_000, listOf("none") + extendedGptEffort)
        "gpt-5.5-pro", "gpt-5.4-pro" -> gpt(1_050_000, 128_000, listOf("medium", "high", "xhigh"))
        "gpt-5.4" -> gpt(1_050_000, 128_000, listOf("none") + extendedGptEffort)
        "gpt-5.4-mini", "gpt-5.4-nano" -> gpt(400_000, 128_000, listOf("none") + extendedGptEffort)
        "gpt-5.2" -> gpt(400_000, 128_000, listOf("none") + extendedGptEffort)
        "gpt-5.2-pro" -> gpt(400_000, 128_000, listOf("medium", "high", "xhigh"))
        "gpt-5.1" -> gpt(400_000, 128_000, listOf("none") + standardEffort)
        "gpt-5", "gpt-5-mini", "gpt-5-nano" -> gpt(400_000, 128_000, listOf("minimal") + standardEffort)
        "gpt-5-pro" -> gpt(400_000, 272_000, listOf("high"))
        "gpt-5-search-api" -> ModelInfo(id, contextWindow = 200_000)
        else -> null
    }
}

internal fun usesAutomaticOpenAiSearch(modelId: String, style: ApiStyle): Boolean =
    style == ApiStyle.OPENAI && normalizedOpenAiModel(modelId) == "gpt-5-search-api"

internal fun supportsOpenAiSearchTool(baseUrl: String, modelId: String, style: ApiStyle): Boolean {
    if (style != ApiStyle.OPENAI_RESPONSES || !isOfficialOpenAiEndpoint(baseUrl)) return false
    return when (normalizedOpenAiModel(modelId)) {
        "chat-latest", "gpt-5.1", "gpt-5.2", "gpt-5.2-pro", "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-pro",
        "gpt-5.5", "gpt-5.5-pro", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
        "gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6.2-astra", "gpt-6.2-sol" -> true
        else -> false
    }
}
