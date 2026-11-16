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
 * ### 两种目标，一套 DTO
 *
 * 同一条协议，DeepSeek 官方与"原版 Anthropic / 第三方 Claude 兼容网关"的要求**并不相同**：
 *
 * | 字段 | DeepSeek 官方 | 原版 Anthropic / 兼容网关 |
 * |---|---|---|
 * | `safety_check` | 扩展模式开关（`high`） | 未知字段 → 400 |
 * | `title` | 服务端标题 | 未知字段 → 400 |
 * | `tools`（`deepseek_*`） | 搜索 / 读链接 | 未知工具类型 → 400 |
 * | `latest_reminder` 角色 | 时间地点提醒 | 未知角色 → 400 |
 * | `thinking.type = disabled` | 收 | **不收**（关闭思考只能**省略**该字段） |
 * | `thinking.budget_tokens` | 不需要 | `type=enabled` 时**必填**（≥1024） |
 * | `max_tokens` | 可不传 | **必填** |
 *
 * 所以所有"非两边通吃"的字段都做成**可空**：可空 + `explicitNulls = false`
 * 就是"不发这个字段"。具体怎么填由 `buildAnthropicRequest` 按目标决定
 * （参数 `deepSeekExtensions`）。
 */
@Serializable
data class AnthropicRequest(
    val model: String,
    /** 系统提示词。原版协议里可以省略；这里统一给字符串，空串即"不发内容"。 */
    val system: String,
    val messages: List<AnthropicMessageParam>,
    /**
     * 思考开关。
     *
     * - 扩展模式：`enabled` / `disabled` 都发（官方 demo 就是这么做的）；
     * - 原版：开启时发 `enabled + budget_tokens`，关闭时**整个字段省略**（null）。
     */
    val thinking: AnthropicThinking? = null,
    /** 扩展模式的安全档位；原版没有这个字段。 */
    @SerialName("safety_check") val safetyCheck: AnthropicSafetyCheck? = null,
    val tools: List<AnthropicTool> = emptyList(),
    /** 只在"这个会话还没有标题"时传；仅扩展模式。 */
    val title: AnthropicTitle? = null,
    /**
     * 输出上限。
     *
     * 扩展模式可不传（DeepSeek 按思考开关给默认值）；原版**必填**，
     * 由装配层兜一个默认值（[AnthropicRequestDefaults.MaxTokens]）。
     */
    @SerialName("max_tokens") val maxTokens: Int? = null,
    /** 采样温度。原版在开启思考时不允许设置（只能为 1），装配层会置空。 */
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val stream: Boolean = true,
)

/** 原版 Anthropic 协议要求的默认值。 */
object AnthropicRequestDefaults {
    /** 不填 `max_tokens` 时的兜底（原版必填）。 */
    const val MaxTokens = 8_192

    /** 开启思考时的预算下限（原版约束）。 */
    const val MinThinkingBudgetTokens = 1_024

    /** 开启思考时给的预算上限：留出正文空间，也不至于把输出上限吃满。 */
    const val MaxThinkingBudgetTokens = 4_096
}

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
        /** 扩展角色：位于它所修饰的那条 user 之前，**只有 DeepSeek 官方收**。 */
        const val RoleReminder = "latest_reminder"
    }
}

@Serializable
data class AnthropicThinking(
    val type: String,
    /**
     * 思考预算。
     *
     * 原版 Anthropic 在 `type = enabled` 时**必填**（最小 1024，且必须小于 `max_tokens`）；
     * DeepSeek 官方扩展不认这个字段（官方 demo 也不传），所以扩展模式置空。
     */
    @SerialName("budget_tokens") val budgetTokens: Int? = null,
) {
    companion object {
        val Enabled = AnthropicThinking("enabled")
        val Disabled = AnthropicThinking("disabled")

        fun enabledWithBudget(budgetTokens: Int): AnthropicThinking =
            AnthropicThinking("enabled", budgetTokens)
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
