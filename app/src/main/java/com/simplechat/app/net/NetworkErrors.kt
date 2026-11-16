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
 * 2. 服务端自己给了具体原因（如 `invalid api key`）→ 保留原文，那才是诊断信息；
 * 3. 都没有 → 带状态码的兜底句，至少让人知道是哪一层出的问题；
 * 4. 原始异常文本一律进日志，不丢弃 —— 排查时还要靠它。
 *
 * 这两个函数是纯函数（只回资源 ID），能单测；落成文字才需要 Android 资源。
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
 * HTTP 失败最终给用户的那句话。
 *
 * 顺序：已知状态码 → 服务端具体文案 → 带状态码的兜底。
 */
internal fun httpErrorText(code: Int, serverMessage: String?): String =
    httpStatusRes(code)?.let { Res.get(it) }
        ?: serverMessage?.trim()?.takeIf { it.isNotEmpty() }
        ?: Res.get(R.string.error_http_status, code)

/** 网络异常落成文字（供流式客户端在 `catch (IOException)` 里用）。 */
internal fun Throwable.networkText(): String = Res.get(networkFailureRes(this))
