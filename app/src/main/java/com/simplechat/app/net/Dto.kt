package com.simplechat.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI 兼容 `/chat/completions` 的传输层数据类。
 *
 * 注意：本项目**只实现 OpenAI 兼容协议**这一个适配器（见设计文档 §5.1）。
 * 因此不包含 Anthropic / Responses API 的 DTO。
 */

@Serializable
data class DeepSeekTool(
    val type: String,
    val function: FunctionDefinitionDto? = null,
    val name: String? = null,
    val description: String? = null,
    val parameters: JsonElement? = null,
)

@Serializable
data class FunctionDefinitionDto(val name: String, val description: String, val parameters: JsonElement)

@Serializable
data class ToolCallDto(val index: Int = 0, val id: String? = null, val function: FunctionCallDto? = null)

@Serializable
data class FunctionCallDto(val name: String? = null, val arguments: String? = null)

@Serializable
data class DeepSeekTitle(val enabled: Boolean = true)

@Serializable
data class ChatRequestDto(
    val model: String,
    val messages: List<OutMessageDto>,
    /**
     * ⚠️ **不能给默认值**。
     *
     * 序列化器配置了 `encodeDefaults = false`，若此处写成 `= true`，
     * kotlinx.serialization 会因为「值等于默认值」而**整个省略该字段**，
     * 服务端随即返回非流式的一整块 JSON —— 表现是「流跑完了但内容为空、也不报错」。
     */
    val stream: Boolean,
    val tools: List<DeepSeekTool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    @SerialName("web_search_options") val webSearchOptions: JsonElement? = null,
    val title: DeepSeekTitle? = null,
    /** DeepSeek 思考模式开关。不支持的模型不能带此字段。 */
    val thinking: ThinkingDto? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    /** 仅用于 KVCache 隔离与内容安全标识，可选。 */
    @SerialName("user_id") val userId: String? = null,
    val reasoning: ReasoningDto? = null,
    @SerialName("enable_thinking") val enableThinking: Boolean? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
)

@Serializable
data class ResponsesRequestDto(
    val model: String,
    val input: List<JsonElement>,
    val include: List<String>? = null,
    val stream: Boolean,
    val tools: List<DeepSeekTool>? = null,
    val title: DeepSeekTitle? = null,
    val store: Boolean = false,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val reasoning: ResponsesReasoningDto? = null,
)

@Serializable
data class ResponsesReasoningDto(val effort: String, val summary: String? = null)

@Serializable
data class ReasoningDto(val enabled: Boolean, val effort: String? = null)

@Serializable
data class ThinkingDto(val type: String) {
    companion object {
        val Enabled = ThinkingDto("enabled")
        val Disabled = ThinkingDto("disabled")
    }
}

/**
 * 发送给模型的消息。
 *
 * [content] 用 `JsonElement` 承载：纯文本时是 `JsonPrimitive`，
 * 带图片时是 content block 数组（图片**只允许出现在 user 消息中**，见设计文档 §11.3）。
 */
@Serializable
data class OutMessageDto(
    val role: String,
    val content: JsonElement,
    @SerialName("tool_calls") val toolCalls: List<JsonElement>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class ChatChunkDto(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChunkChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
)

@Serializable
data class ChunkChoiceDto(
    val index: Int = 0,
    val delta: DeltaDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class DeltaDto(
    val role: String? = null,
    val content: String? = null,
    /** 网关可能发送字符串、结构化摘要或元数据，按字段单独提取文本。 */
    @SerialName("reasoning_content") val reasoningContent: JsonElement? = null,
    val reasoning: JsonElement? = null,
    @SerialName("reasoning_text") val reasoningText: JsonElement? = null,
    @SerialName("reasoning_summary") val reasoningSummary: JsonElement? = null,
    @SerialName("reasoning_details") val reasoningDetails: JsonElement? = null,
    val title: String? = null,
    val annotations: JsonElement? = null,
    val refusal: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto> = emptyList(),
)

@Serializable
data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    /** DeepSeek 的缓存命中 token 数，用于成本可视化（可选字段）。 */
    @SerialName("prompt_cache_hit_tokens") val cacheHitTokens: Int? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetailsDto? = null,
)

@Serializable
data class PromptTokensDetailsDto(
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
)

/** 错误响应体。 */
@Serializable
data class ErrorEnvelopeDto(val error: ErrorBodyDto? = null)

@Serializable
data class ErrorBodyDto(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
