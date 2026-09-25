package com.simplechat.app.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI 兼容 `/chat/completions` 的传输层数据类。
 *
 * 注意：本项目**只实现 OpenAI 兼容协议**这一个适配器（见设计文档 §5.1）。
 * 因此不包含 Anthropic / Responses API 的 DTO。
 */

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
    /** DeepSeek 思考模式开关。不支持的模型不能带此字段。 */
    val thinking: ThinkingDto? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    /** 仅用于 KVCache 隔离与内容安全标识，可选。 */
    @SerialName("user_id") val userId: String? = null,
)

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
    val content: kotlinx.serialization.json.JsonElement,
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
    /** 思维链增量。与 `content` 同级（设计文档 §11.5）。 */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    /** DeepSeek 的缓存命中 token 数，用于成本可视化（可选字段）。 */
    @SerialName("prompt_cache_hit_tokens") val cacheHitTokens: Int? = null,
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

/** `GET /models` 响应（OpenCode Go 的模型列表无需鉴权即可访问）。 */
@Serializable
data class ModelListDto(val data: List<ModelEntryDto> = emptyList())

@Serializable
data class ModelEntryDto(
    val id: String,
    @SerialName("owned_by") val ownedBy: String? = null,
)
