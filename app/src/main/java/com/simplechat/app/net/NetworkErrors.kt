package com.simplechat.app.net

import androidx.annotation.StringRes
import com.simplechat.app.R
import com.simplechat.app.data.Res
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 网络异常 / HTTP 状态码 → **用户能懂、也知道下一步做什么**的一句话。
 *
 * ### 为什么不让异常原文直接上屏
 *
 * 之前这里是把 `e.message` 原样透给界面的。OkHttp 的流被服务端掐断时，
 * 消息长这样（v1.1.3 之前的用户截图）：
 *
 * ```text
 * stream was reset: INTERNAL_ERROR
 * ```
 *
 * 用户看到的是：一条英文、没有主语、也没有下一步的句子。而它其实只是
 * "这次连接断了，重试一下就好"。同理 `HTTP 500` / `HTTP 429`。
 *
 * ### 原则
 *
 * 1. 已知的失败类型 → 一句**说清怎么办**的中文（[networkFailureRes] / [httpStatusRes]）；
 * 2. 服务端自己报的原因（`No available channel for model ...`、`Authentication Fails` 等）
 *    → 先按关键字翻成人话（[providerHintRes]），原文仍收进括号，诊断信息不丢；
 * 3. 都认不出来 → 原文或带状态码的兜底句，至少让人知道是哪一层出的问题；
 * 4. 原始异常文本一律进日志，不丢弃 —— 排查时还要靠它。
 *
 * 这些映射函数都是纯函数（只回资源 ID），能单测；落成文字才需要 Android 资源。
 */
@StringRes
internal fun networkFailureRes(error: Throwable): Int = when (error) {
    is UnknownHostException -> R.string.error_network_offline
    is SocketTimeoutException -> R.string.error_network_timeout
    // OkHttp 对"读超时 / 连接被对端关掉"统一抛 InterruptedIOException
    is InterruptedIOException -> R.string.error_network_timeout
    is SSLException -> R.string.error_network_insecure
    // 包括 `stream was reset: INTERNAL_ERROR`：HTTP/2 流被服务端/中间层重置
    is IOException -> R.string.error_network_interrupted
    else -> R.string.error_network
}

/** 常见的 HTTP 状态码给一句人话；没覆盖到返回 null（交给服务端文案 / 兜底句）。 */
@StringRes
internal fun httpStatusRes(code: Int): Int? = when (code) {
    400, 422 -> R.string.error_http_bad_request
    408 -> R.string.error_network_timeout
    415 -> R.string.service_file_format
    410 -> R.string.service_file_expired
    401 -> R.string.error_http_unauthorized
    402 -> R.string.error_http_payment_required
    403 -> R.string.error_http_forbidden
    404 -> R.string.error_http_not_found
    413 -> R.string.error_http_too_large
    429 -> R.string.error_http_rate_limited
    in 500..599 -> R.string.error_server_busy
    else -> null
}

/**
 * 服务商（OpenCode Zen / 自建中转等上游）在响应体里自报的失败原因 → 一句「下一步做什么」。
 *
 * 用户截图里那两条都是上游原文，客户端修不了：
 * - `No available channel for model deepseek-flash`：上游没给这个模型分配通道；
 * - `Authentication Fails (governor)`：上游鉴权 / 风控拒绝。
 * 原文上屏等于让用户对着英文猜；所以先按关键字翻成人话，原文再由 [httpErrorText]
 * 收进括号里留给排查。
 *
 * 匹配规则：
 * - 大小写不敏感（先转小写），子串匹配；
 * - 顺序 = 鉴权 > 通道 > 模型 > 额度 > 限流 > 上下文，多个同时命中时取第一个；
 * - `balance` 这类日常词只在明确的欠费语境里才算数（`insufficient balance` 等），
 *   否则 `Please write a poem about balance` 也会被判成余额不足。
 *
 * 纯函数、只回资源 ID，可以在 JVM 单测里钉死；落成当前语言的文字才需要 Android 资源。
 */
@StringRes
internal fun providerHintRes(serverMessage: String?): Int? {
    val text = serverMessage?.trim()?.lowercase()
    if (text.isNullOrEmpty()) return null
    return when {
        text.containsAny(CONTENT_RISK_KEYWORDS) -> R.string.error_provider_content_risk
        "deepseek-v4-pro" in text && "unavailable" in text &&
            text.containsAny(listOf("deepseek_web_search", "deepseek_read_url", "title generation")) -> R.string.error_deepseek_flash_feature
        text.contains("file") && text.containsAny(listOf("not found", "expired", "does not exist")) -> R.string.service_file_expired
        text.containsAny(listOf("unsupported file", "password-protected", "failed to parse", "file format")) -> R.string.service_file_format
        text.contains("purpose") -> R.string.error_http_bad_request
        text.containsAny(INVALID_API_KEY_KEYWORDS) -> R.string.error_http_unauthorized
        text.containsAny(AUTH_KEYWORDS) -> R.string.error_provider_auth
        text.containsAny(NO_CHANNEL_KEYWORDS) -> R.string.error_provider_no_channel
        text.containsAny(MODEL_MISSING_KEYWORDS) -> R.string.error_provider_model_not_found
        text.looksLikeQuotaProblem() -> R.string.error_provider_quota
        text.containsAny(RATE_LIMIT_KEYWORDS) -> R.string.error_provider_rate_limit
        text.containsAny(PAYLOAD_KEYWORDS) -> R.string.error_http_too_large
        text.containsAny(CONTEXT_KEYWORDS) -> R.string.error_provider_context_length
        isEmptyMessagesRejection(text) -> R.string.error_provider_empty_messages
        text.contains("invalid_request_error") -> R.string.error_http_bad_request
        else -> null
    }
}

/** Retry only request-content/tool incompatibilities, never credentials, configuration, or transport errors. */
internal fun canRetryWithoutAttachmentsAndTools(code: Int?, serverMessage: String?): Boolean {
    val status = code ?: return false
    if (status !in 400..499 || status == 401 || status == 403 || status == 408 || status == 429) return false
    val text = serverMessage?.lowercase().orEmpty()
    if (text.isBlank()) return false
    if (text.containsAny(AUTH_KEYWORDS + INVALID_API_KEY_KEYWORDS + QUOTA_KEYWORDS + CONTEXT_KEYWORDS + MODEL_MISSING_KEYWORDS)) {
        return false
    }
    return text.containsAny(OPTIONAL_FEATURE_ERROR_KEYWORDS) ||
        (status == 413 && text.containsAny(PAYLOAD_KEYWORDS))
}

private val CONTENT_RISK_KEYWORDS = listOf(
    "content exists risk",
    "content risk detected",
    "content moderation rejected",
)

private val OPTIONAL_FEATURE_ERROR_KEYWORDS = listOf(
    "content exists risk",
    "content risk detected",
    "content moderation rejected",
    "deepseek_web_search",
    "deepseek_read_url",
    "server-side tool calls",
    "tool calls are unavailable",
    "tool is not supported",
    "unsupported tool",
    "file_id",
    "files api",
    "unsupported image",
    "images are not supported",
    "file format",
    "unsupported file",
    "payload too large",
    "request body too large",
    "request_too_large",
)

internal fun isEmptyMessagesRejection(message: String?): Boolean =
    message?.lowercase()?.contains("messages: at least one message is required") == true

private fun String.containsAny(keywords: List<String>): Boolean = keywords.any { contains(it) }

/** `quota` 相关词本身够明确；`balance` 必须搭配“不足”语境才算欠费。 */
private fun String.looksLikeQuotaProblem(): Boolean =
    containsAny(QUOTA_KEYWORDS) ||
        (contains("balance") && containsAny(BALANCE_SHORTFALL_KEYWORDS))

private val INVALID_API_KEY_KEYWORDS = listOf(
    "invalid api key",
    "invalid_api_key",
    "invalid api-key",
    "incorrect api key",
    "api key is invalid",
    "api key is not valid",
    "api_key_invalid",
    "invalid key",
    "key is invalid",
)

private val AUTH_KEYWORDS = listOf(
    "authentication fails", // OpenCode Zen：`Authentication Fails (governor)`
    "authentication failed",
    "invalid api key",
    "invalid_api_key",
    "invalid api-key",
    "incorrect api key",
    "api key is invalid",
    "unauthorized",
)

private val NO_CHANNEL_KEYWORDS = listOf(
    "no available channel", // OpenCode Zen：`No available channel for model ...`
    "no channel available",
    "no channels available",
    "channel not available",
)

private val MODEL_MISSING_KEYWORDS = listOf(
    "model not found",
    "model_not_found",
    "does not exist",
    "unknown model",
    "no such model",
)

private val QUOTA_KEYWORDS = listOf(
    "insufficient_quota",
    "insufficient quota",
    "quota exceeded",
    "exceeded your quota",
    "exceeded your current quota",
    "quota has been exceeded",
    "余额不足",
    "额度不足",
)

private val BALANCE_SHORTFALL_KEYWORDS = listOf(
    "insufficient",
    "not enough",
    "low balance",
    "balance is low",
    "balance low",
    "depleted",
    "exhausted",
    "negative",
    "不足",
    "欠费",
)

private val RATE_LIMIT_KEYWORDS = listOf(
    "rate limit",
    "rate_limit",
    "too many requests",
)

private val CONTEXT_KEYWORDS = listOf(
    "context length",
    "context_length",
    "maximum context",
    "context window",
    "too many tokens",
    "prompt is too long",
    "prompt too long",
    "input is too long",
    "input too long",
    "input token limit",
    "max model len",
    "上下文长度",
    "上下文超",
    "输入过长",
)

private val PAYLOAD_KEYWORDS = listOf(
    "request body too large",
    "request entity too large",
    "payload too large",
    "request_too_large",
    "request too large",
    "request size exceeds",
)

/**
 * HTTP 失败最终给用户的那句话。
 *
 * 认得出上游原因时三段（去重后拼接）：
 * 1. 状态码提示（[httpStatusRes]，已知状态码才有）；
 * 2. 服务商提示（[providerHintRes]）——补的正是状态码看不出来的“为什么”和“下一步做什么”；
 * 3. 服务端原文，包在括号里（`error_provider_raw`），过长截断 —— 诊断信息不丢，但不抢在第一位。
 *
 * 提示之间用本地化分隔符（中文 `：`）连接，括号段直接跟在末尾，不再多一个冒号。
 *
 * **认不出任何模式时保持原行为**：状态码提示 → 服务端原文 → 带状态码的兜底句。
 */
internal fun httpErrorText(code: Int, serverMessage: String?, largeRequest: Boolean = false): String {
    val raw = serverMessage?.trim()?.takeIf { it.isNotEmpty() }
    val providerRes = providerHintRes(raw)
    if (code == 401 || providerRes == R.string.error_http_unauthorized) {
        return Res.get(R.string.error_http_unauthorized)
    }
    if (providerRes == null) {
        // 没认出上游原因 → 一字不改地走老逻辑，避免影响现有表现
        if (code in listOf(400, 422) && raw != null) {
            return Res.get(R.string.error_parameter_detail, code, abbreviateProviderMessage(raw)) + " " + Res.get(R.string.error_http_bad_request)
        }
        val hint = httpStatusRes(code)?.let { Res.get(it) }
            ?: raw
            ?: Res.get(R.string.error_http_status, code)
        return if (largeRequest && code in 500..599) {
            Res.get(R.string.error_large_request_interrupted, hint)
        } else hint
    }

    val statusHint = httpStatusRes(code)?.takeUnless { it == R.string.error_http_bad_request }?.let { Res.get(it) }
    val providerHint = Res.get(providerRes)
    // 原文与提示文字完全一样就不再重复（“与提示不同”才附）
    val rawDetail = raw
        ?.takeIf { it != statusHint && it != providerHint }
        ?.let { Res.get(R.string.error_provider_raw, abbreviateProviderMessage(it)) }
    return composeProviderErrorText(
        statusHint = statusHint,
        providerHint = providerHint,
        rawDetail = rawDetail,
        separator = Res.get(R.string.error_provider_separator),
    )
}

/**
 * 拼接逻辑本身不碰 Android 资源，拆出来给 JVM 单测用。
 *
 * [httpErrorText] 要 `Res.get` 取当前语言文案，纯 JVM 单测没有 Resources
 * （见 `data/AppText.kt` 的说明），所以资源之外的两块（拼接 / 截断）单独钉住。
 */
internal fun composeProviderErrorText(
    statusHint: String?,
    providerHint: String,
    rawDetail: String?,
    separator: String,
): String {
    val hints = listOfNotNull(
        statusHint?.takeIf { it.isNotBlank() },
        providerHint.takeIf { it.isNotBlank() },
    ).distinct()
    return hints.joinToString(separator) + (rawDetail ?: "")
}

/** 服务端原文超过 300 字符就截断并加省略号；别把代理对（emoji）截成半个。 */
internal fun abbreviateProviderMessage(message: String): String {
    if (message.length <= MAX_PROVIDER_MESSAGE_LENGTH) return message
    var end = MAX_PROVIDER_MESSAGE_LENGTH
    if (Character.isHighSurrogate(message[end - 1])) end--
    return message.take(end).trimEnd() + "…"
}

/** 服务端原文在界面上的保留上限。 */
private const val MAX_PROVIDER_MESSAGE_LENGTH = 300

/**
 * 流内错误（服务端在 SSE 里直接给的错误对象）→ 人话。
 *
 * 与 [httpErrorText] 的区别：这里**没有 HTTP 状态码**可用，只有服务端原文。
 * 认得出就翻成人话、并把原文收进括号；认不出就原样返回（至少那是服务端说的）。
 *
 * 为什么需要它：`SseLineParser` / `AnthropicStreamParser` 从流里取出的
 * `{"error":{"message":"…"}}` 是**上游网关原文**，链路里没有状态码可补
 * （`No available channel for model …` 就出现在这里）。
 */
internal fun serverErrorText(serverMessage: String?): String {
    val raw = serverMessage?.trim()?.takeIf { it.isNotEmpty() }
        ?: return Res.get(R.string.error_network)
    val hintRes = providerHintRes(raw) ?: return raw
    if (hintRes == R.string.error_http_unauthorized) return Res.get(hintRes)
    return composeProviderErrorText(
        statusHint = null,
        providerHint = Res.get(hintRes),
        rawDetail = Res.get(R.string.error_provider_raw, abbreviateProviderMessage(raw)),
        separator = Res.get(R.string.error_provider_separator),
    )
}

/**
 * 这段文字像不像**系统/网络层抛出的英文原文**。
 *
 * 用途只有一个：旧版本把异常 `message` 原样落库了，历史消息读回来时据此翻成人话
 * （见 `data.storedErrorText`）。纯函数、无资源依赖，可以单测。
 *
 * 只认那些**确定出自 OkHttp / socket / TLS** 的片段 —— 宁可漏判（保持原文，
 * 至少信息不丢），也不能把用户/服务端正常写的英文误翻成"网络中断"。
 */
internal fun isRawNetworkError(text: String?): Boolean {
    val lower = text?.trim()?.lowercase()
    if (lower.isNullOrEmpty()) return false
    return RAW_NETWORK_ERROR_HINTS.any { lower.contains(it) }
}

private val RAW_NETWORK_ERROR_HINTS = listOf(
    "software caused connection abort",
    "stream was reset",
    "connection reset",
    "connection abort",
    "broken pipe",
    "unexpected end of stream",
    "failed to connect",
    "connection refused",
    "connection timed out",
    "read timed out",
    "connect timed out",
    "timeout",
    "sslhandshake",
    "certificate",
    "unknownhost",
    "unable to resolve host",
    "eofexception",
)

/** 网络异常落成文字（供流式客户端在 `catch (IOException)` 里用）。 */
internal fun Throwable.networkText(largeRequestBeforeResponse: Boolean = false, receivedHeaders: Boolean = false): String {
    val hint = Res.get(networkFailureRes(this))
    return if (needsLargeRequestHint(this, largeRequestBeforeResponse)) {
        Res.get(if (receivedHeaders) R.string.error_large_stream_before_reply else R.string.error_large_send_before_reply)
    } else hint
}

internal fun needsLargeRequestHint(error: Throwable, largeRequestBeforeResponse: Boolean): Boolean =
    largeRequestBeforeResponse && error is IOException &&
        error !is UnknownHostException && error !is SSLException && error !is java.net.ConnectException
