package com.simplechat.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 缓存命中率的口径。
 *
 * 这一行数字会直接摆在「上下文占用」面板里给用户看，算错会误导成本判断，
 * 所以把边界钉死：没报缓存字段、prompt 为 0、以及个别网关把命中数报得比
 * 输入总数还大（钳到 100%，不出现 137% 这种数字）。
 */
class RequestUsageTest {

    @Test
    fun `命中率是命中数除以输入总数`() {
        val usage = RequestUsage(promptTokens = 10_000, completionTokens = 200, cacheHitTokens = 6_000)
        assertEquals(0.6f, usage.cacheHitRatio!!, 0.0001f)
    }

    @Test
    fun `服务端没报缓存字段时不显示命中率`() {
        assertNull(RequestUsage(promptTokens = 1_000, completionTokens = 10, cacheHitTokens = null).cacheHitRatio)
    }

    @Test
    fun `输入为 0 时不算命中率`() {
        assertNull(RequestUsage(promptTokens = 0, completionTokens = 0, cacheHitTokens = 0).cacheHitRatio)
    }

    @Test
    fun `命中数异常偏大时钳到 100%`() {
        val usage = RequestUsage(promptTokens = 100, completionTokens = 5, cacheHitTokens = 137)
        assertEquals(1f, usage.cacheHitRatio!!, 0.0001f)
    }

    @Test
    fun `全部命中就是 100%`() {
        val usage = RequestUsage(promptTokens = 8_000, completionTokens = 128, cacheHitTokens = 8_000)
        assertEquals(1f, usage.cacheHitRatio!!, 0.0001f)
    }
}
