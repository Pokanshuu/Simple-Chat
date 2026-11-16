package com.simplechat.app.net

import com.simplechat.app.R
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 网络失败 → 一句人话的映射。
 *
 * 之前这里是把异常原文直接上屏的：用户截图里那条
 * `stream was reset: INTERNAL_ERROR` 就是 OkHttp 的 IOException 文案。
 * 现在只回资源 ID（可测），落成文字才需要 Android 资源。
 */
class NetworkErrorsTest {

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
}
