package com.simplechat.app.net

/** 流式事件。UI 层只依赖这个抽象，不接触 SSE 细节。 */
sealed interface StreamEvent {

    /** 正文增量。 */
    data class Content(val text: String) : StreamEvent

    /** 思维链增量（DeepSeek 的 `reasoning_content`）。 */
    data class Reasoning(val text: String) : StreamEvent

    /** 用量统计，通常在流末尾到达。 */
    data class Usage(
        val promptTokens: Int,
        val completionTokens: Int,
        val cacheHitTokens: Int?,
    ) : StreamEvent

    /** 正常结束（收到 `data: [DONE]` 或 finish_reason）。 */
    data object Done : StreamEvent

    /** 失败。`code` 为 HTTP 状态码，网络异常时为 null。 */
    data class Error(
        val message: String,
        val code: Int? = null,
        val retryable: Boolean = false,
    ) : StreamEvent
}
