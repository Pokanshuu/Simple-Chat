package com.simplechat.app.net.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * DeepSeek **Anthropic 兼容** Messages API 的请求 DTO（`POST /anthropic/v1/messages`）。
 *
 * 官方 Chat Demo 的全部扩展能力（联网搜索、链接阅读、服务端标题、时间地点提醒、
 * 引用角标）都只在这个协议上提供，OpenAI 兼容的 `/chat/completions` 拿不到。
 * 因此本项目**双通道并存**：DeepSeek 官方端点可选择走这条，其余端点照旧。
 *
 * 协议要点（与官方 demo 逐字对齐）：
 * - 鉴权既能 `x-api-key` 也能 `Authorization: Bearer`，本项目统一用 Bearer（与 OpenAI 通道一致，
 *   少一个分支；官方 demo 自己也注明两种都收）。
 * - `thinking.type` 决定是否开启深度思考；扩展模式下 `thinking` 与 `tools` 可以同时出现。
 * - `safety_check.level = high` 是**进入扩展模式**的开关，不传就拿不到搜索块。
 * - `latest_reminder` 是一个只有本协议才有的角色，位于它所修饰的那条 user 之前。
 * - 流以 `message_stop` 结束，**不发** `[DONE]`。
 */
@Serializable
data class AnthropicRequest(
    val model: String,
    /** 系统提示词。协议要求必填且非空，空值由调用方兜底。 */
    val system: String,
    val messages: List<AnthropicMessageParam>,
    val thinking: AnthropicThinking,
    @SerialName("safety_check") val safetyCheck: AnthropicSafetyCheck = AnthropicSafetyCheck(),
    val tools: List<AnthropicTool> = emptyList(),
    /** 只在"这个会话还没有标题"时传，服务端在 `message_delta` 里回一个标题。 */
    val title: AnthropicTitle? = null,
    /**
     * 输出上限。
     *
     * Anthropic 原版把它列为必填，但 DeepSeek 的兼容端点**不传也收**
     * （官方 Chat Demo 就完全没传，交给服务端按思考开关给 8K / 64K 默认值）。
     * 因此这里可空：用户在参数面板里填了才发。
     */
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = true,
)

/**
 * 一条消息。`content` 用 [JsonElement] 承载：
 * 纯文本时是字符串，带图片 / 带思考与工具块时是 block 数组。
 */
@Serializable
data class AnthropicMessageParam(
    /** `user` / `assistant` / `latest_reminder` */
    val role: String,
    val content: JsonElement,
) {
    companion object {
        const val RoleUser = "user"
        const val RoleAssistant = "assistant"
        /** 扩展角色：位于它所修饰的那条 user 之前，需要服务端支持。 */
        const val RoleReminder = "latest_reminder"
    }
}

@Serializable
data class AnthropicThinking(val type: String) {
    companion object {
        val Enabled = AnthropicThinking("enabled")
        val Disabled = AnthropicThinking("disabled")
    }
}

/** 安全审核档位。`high` 即扩展模式 —— 不传这个就拿不到搜索工具。 */
@Serializable
data class AnthropicSafetyCheck(val level: String = "high")

@Serializable
data class AnthropicTool(val type: String, val name: String) {
    companion object {
        /** 联网搜索（含边想边搜与图搜）。 */
        val WebSearch = AnthropicTool("deepseek_web_search", "deepseek_web_search")

        /** 阅读本轮用户消息里的链接。与搜索工具**独立**声明。 */
        val ReadUrl = AnthropicTool("deepseek_read_url", "deepseek_read_url")
    }
}

@Serializable
data class AnthropicTitle(val enabled: Boolean = true)
