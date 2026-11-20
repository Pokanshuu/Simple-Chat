package com.simplechat.app.net

import com.simplechat.app.R
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络失败 → 一句人话的映射。
 *
 * 之前这里是把异常原文直接上屏的：用户截图里那条
 * `stream was reset: INTERNAL_ERROR` 就是 OkHttp 的 IOException 文案。
 * 现在只回资源 ID（可测），落成文字才需要 Android 资源。
 *
 * 另一类上屏原文是**服务商自报**的失败（`No available channel for model ...`、
 * `Authentication Fails (governor)`），由 [providerHintRes] 按关键字翻译。
 *
 * ⚠️ [httpErrorText] 本身要 `Res.get(...)` 取当前语言文案，纯 JVM 单测里没有
 * Android Resources（见 `data/AppText.kt` 的说明），所以拆出资源之外的两块
 * [composeProviderErrorText]（拼接 / 去重）与 [abbreviateProviderMessage]（300 字截断）
 * 单独钉住；关键字 → 资源 ID 的映射由 [providerHintRes] 的用例覆盖。
 */
class NetworkErrorsTest {

    @Test fun `long input and payload errors have actionable context hints`() {
        for (message in listOf("prompt is too long: 150000 tokens", "input too long", "上下文长度超过限制", "input token limit exceeded")) {
            assertEquals(R.string.error_provider_context_length, providerHintRes(message))
        }
        for (message in listOf("Request body too large", "request_too_large", "Payload Too Large", "request size exceeds limit")) {
            assertEquals(R.string.error_http_too_large, providerHintRes(message))
        }
    }

    @Test fun `large request advice is limited to pre response interruption not DNS TLS or offline`() {
        assertTrue(needsLargeRequestHint(IOException("reset"), true))
        assertTrue(needsLargeRequestHint(SocketTimeoutException(), true))
        assertFalse(needsLargeRequestHint(IOException("reset"), false))
        assertFalse(needsLargeRequestHint(UnknownHostException(), true))
        assertFalse(needsLargeRequestHint(SSLHandshakeException("certificate"), true))
        assertFalse(needsLargeRequestHint(java.net.ConnectException(), true))
    }

    @Test
    fun `断网`() {
        assertEquals(R.string.error_network_offline, networkFailureRes(UnknownHostException("api.deepseek.com")))
    }

    @Test
    fun `超时`() {
        assertEquals(R.string.error_network_timeout, networkFailureRes(SocketTimeoutException("timeout")))
        assertEquals(R.string.error_network_timeout, networkFailureRes(InterruptedIOException("timeout")))
    }

    @Test
    fun `证书问题`() {
        assertEquals(R.string.error_network_insecure, networkFailureRes(SSLHandshakeException("bad cert")))
    }

    @Test
    fun `流被掐断也是 IOException`() {
        // OkHttp 的 HTTP/2 流重置就是这一条：`stream was reset: INTERNAL_ERROR`
        assertEquals(
            R.string.error_network_interrupted,
            networkFailureRes(IOException("stream was reset: INTERNAL_ERROR")),
        )
    }

    @Test
    fun `其它异常走通用文案`() {
        assertEquals(R.string.error_network, networkFailureRes(IllegalStateException("boom")))
    }

    @Test
    fun `常见 HTTP 状态码各有各的说法`() {
        assertEquals(R.string.error_http_unauthorized, httpStatusRes(401))
        assertEquals(R.string.error_http_payment_required, httpStatusRes(402))
        assertEquals(R.string.error_http_forbidden, httpStatusRes(403))
        assertEquals(R.string.error_http_not_found, httpStatusRes(404))
        assertEquals(R.string.error_http_too_large, httpStatusRes(413))
        assertEquals(R.string.error_http_rate_limited, httpStatusRes(429))
        assertEquals(R.string.error_server_busy, httpStatusRes(500))
        assertEquals(R.string.error_server_busy, httpStatusRes(503))
        // 没覆盖的状态码交给服务端文案 / 兜底句，不硬编
        assertNull(httpStatusRes(418))
    }

    @Test
    fun `preserves provider diagnosis for rejected messages`() {
        assertEquals(R.string.error_provider_empty_messages,
            providerHintRes("messages: at least one message is required (request_id: example)"))
    }

    @Test fun `content risk errors are distinguished from model and endpoint errors`() {
        assertEquals(R.string.error_provider_content_risk,
            providerHintRes("Content Exists Risk (request_id: example-123)"))
        assertEquals(R.string.error_provider_content_risk, providerHintRes("content moderation rejected"))
        assertNull(providerHintRes("HTTP 400: invalid request parameter"))
    }

    @Test fun `retryable optional-feature errors are separated from setup and transport failures`() {
        assertTrue(canRetryWithoutAttachmentsAndTools(400, "Content Exists Risk"))
        assertTrue(canRetryWithoutAttachmentsAndTools(422, "deepseek_web_search is unavailable"))
        assertTrue(canRetryWithoutAttachmentsAndTools(413, "request body too large"))
        assertFalse(canRetryWithoutAttachmentsAndTools(401, "invalid api key"))
        assertFalse(canRetryWithoutAttachmentsAndTools(400, "unknown model"))
        assertFalse(canRetryWithoutAttachmentsAndTools(503, "upstream unavailable"))
        assertFalse(canRetryWithoutAttachmentsAndTools(null, "socket reset"))
    }

    // ── providerHintRes：服务商自报原因 → 人话 ──────────────────────────

    @Test
    fun `没有可用通道`() {
        // 用户反馈原文：`No available channel for model deepseek-flash`
        assertEquals(
            R.string.error_provider_no_channel,
            providerHintRes("No available channel for model deepseek-flash"),
        )
        assertEquals(R.string.error_provider_no_channel, providerHintRes("no channel available"))
        // 大小写不敏感，首尾空白不影响
        assertEquals(
            R.string.error_provider_no_channel,
            providerHintRes("  NO AVAILABLE CHANNEL FOR MODEL X  "),
        )
    }

    @Test
    fun `鉴权失败`() {
        // 用户反馈原文：`Authentication Fails (governor)`
        assertEquals(R.string.error_provider_auth, providerHintRes("Authentication Fails (governor)"))
        assertEquals(R.string.error_http_unauthorized, providerHintRes("invalid api key"))
        assertEquals(R.string.error_http_unauthorized, providerHintRes("invalid_api_key"))
        assertEquals(R.string.error_http_unauthorized, providerHintRes("Incorrect API key provided"))
        assertEquals(R.string.error_http_unauthorized, providerHintRes("API key is not valid"))
        assertEquals(R.string.error_http_unauthorized, providerHintRes("key is invalid"))
        assertEquals(R.string.error_provider_auth, providerHintRes("Authentication Fails (governor)"))
        assertEquals(R.string.error_provider_auth, providerHintRes("Unauthorized"))
    }

    @Test
    fun `额度不足`() {
        assertEquals(R.string.error_provider_quota, providerHintRes("insufficient_quota"))
        assertEquals(R.string.error_provider_quota, providerHintRes("insufficient quota"))
        // balance 单独出现不算，得带“不足”语境
        assertEquals(R.string.error_provider_quota, providerHintRes("Insufficient balance"))
        assertEquals(R.string.error_provider_quota, providerHintRes("Your balance is not enough"))
        assertEquals(R.string.error_provider_quota, providerHintRes("余额不足"))
    }

    @Test
    fun `请求太频繁`() {
        assertEquals(R.string.error_provider_rate_limit, providerHintRes("Rate limit reached for requests"))
        assertEquals(R.string.error_provider_rate_limit, providerHintRes("Too many requests"))
    }

    @Test
    fun `模型不存在`() {
        assertEquals(R.string.error_provider_model_not_found, providerHintRes("model not found"))
        assertEquals(R.string.error_provider_model_not_found, providerHintRes("model_not_found"))
        assertEquals(
            R.string.error_provider_model_not_found,
            providerHintRes("The model `deepseek-flash` does not exist"),
        )
    }

    @Test
    fun `上下文超限`() {
        assertEquals(
            R.string.error_provider_context_length,
            providerHintRes("This model's maximum context length is 65536 tokens"),
        )
        assertEquals(R.string.error_provider_context_length, providerHintRes("context length exceeded"))
    }

    @Test
    fun `普通正文不误伤`() {
        assertNull(providerHintRes(null))
        assertNull(providerHintRes(""))
        assertNull(providerHintRes("   "))
        // 正文/提示词里出现 balance 不能被当成欠费
        assertNull(providerHintRes("Please write a poem about balance"))
        assertNull(providerHintRes("Balance the budget in this chapter and keep the model town quiet"))
        // 关键词的“碎片”出现在正常句子里也不该命中
        assertNull(providerHintRes("The channel is open and the key is in the drawer"))
        assertNull(providerHintRes("Write a novel about a strict governor"))
        assertNull(providerHintRes("Context matters in this chapter"))
    }

    // ── httpErrorText 里不依赖 Android 资源的拼接 / 截断 ─────────────────

    @Test
    fun `拼接时状态码提示在前 原文进括号`() {
        assertEquals(
            "HTTP 401：服务商鉴权失败：请检查 Key（服务端原文：Authentication Fails (governor)）",
            composeProviderErrorText(
                statusHint = "HTTP 401",
                providerHint = "服务商鉴权失败：请检查 Key",
                rawDetail = "（服务端原文：Authentication Fails (governor)）",
                separator = "：",
            ),
        )
    }

    @Test
    fun `没有状态码提示时从服务商提示开始`() {
        assertEquals(
            "服务商没有这个模型，请换模型（服务端原文：model not found）",
            composeProviderErrorText(
                statusHint = null,
                providerHint = "服务商没有这个模型，请换模型",
                rawDetail = "（服务端原文：model not found）",
                separator = "：",
            ),
        )
    }

    @Test
    fun `提示文字重复时只留一段`() {
        assertEquals(
            "请求太频繁，请稍后再试",
            composeProviderErrorText(
                statusHint = "请求太频繁，请稍后再试",
                providerHint = "请求太频繁，请稍后再试",
                rawDetail = null,
                separator = "：",
            ),
        )
    }

    @Test
    fun `没有原文就不带括号`() {
        assertEquals(
            "请求太频繁，请稍后再试",
            composeProviderErrorText(
                statusHint = null,
                providerHint = "请求太频繁，请稍后再试",
                rawDetail = null,
                separator = "：",
            ),
        )
    }

    @Test
    fun `空白的状态码提示被忽略`() {
        assertEquals(
            "服务商没有这个模型",
            composeProviderErrorText(
                statusHint = "   ",
                providerHint = "服务商没有这个模型",
                rawDetail = null,
                separator = "：",
            ),
        )
    }

    @Test
    fun `原文不超过 300 字原样保留`() {
        val short = "x".repeat(300)
        assertEquals(short, abbreviateProviderMessage(short))
        assertEquals("model not found", abbreviateProviderMessage("model not found"))
    }

    @Test
    fun `原文超过 300 字截断并加省略号`() {
        val abbreviated = abbreviateProviderMessage("x".repeat(301))
        assertEquals(301, abbreviated.length) // 300 字 + 一个省略号
        assertTrue(abbreviated.endsWith("…"))
    }

    @Test
    fun `截断不留下半个 emoji`() {
        // 299 个 a + 😀（两个 char），按 char 数会正好切在代理对中间
        val message = "a".repeat(299) + "\uD83D\uDE00"
        val abbreviated = abbreviateProviderMessage(message)
        assertTrue(abbreviated.endsWith("…"))
        assertFalse(Character.isHighSurrogate(abbreviated[abbreviated.length - 2]))
    }
    // ── 旧数据里的英文原文：只翻"确定出自网络层"的那些 ──────────

    @Test
    fun `旧版本落库的网络异常原文能被识别`() {
        // v1.1.3 及以前 userMessage() 会把异常 message 原样落库（真机截图）
        assertTrue(isRawNetworkError("Software caused connection abort"))
        assertTrue(isRawNetworkError("stream was reset: INTERNAL_ERROR"))
        assertTrue(isRawNetworkError("Connection reset by peer"))
        assertTrue(isRawNetworkError("unexpected end of stream"))
        assertTrue(isRawNetworkError("Failed to connect to /10.0.2.2:11434"))
        assertTrue(isRawNetworkError("Read timed out"))
        assertTrue(isRawNetworkError("SSLHandshakeException: Trust anchor for certification path not found"))
    }

    @Test
    fun `正常文案不会被误判成网络异常`() {
        // 服务商侧的错误、用户/服务端正常写的英文都不能被翻成"网络中断"
        assertFalse(isRawNetworkError("No available channel for model deepseek-flash"))
        assertFalse(isRawNetworkError("Authentication Fails (governor)"))
        assertFalse(isRawNetworkError("Please write a poem about balance"))
        assertFalse(isRawNetworkError("这张图片不支持"))
        assertFalse(isRawNetworkError(""))
        assertFalse(isRawNetworkError(null))
    }
}
