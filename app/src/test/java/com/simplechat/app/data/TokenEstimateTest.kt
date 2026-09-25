package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 估算器不追求精确，但**单调性与量级**必须成立 ——
 * 圆环和阈值提醒都建立在这上面：估偏一倍会让"该压缩了"的提示变得毫无意义。
 */
class TokenEstimateTest {

    @Test
    fun `empty text costs nothing`() {
        assertEquals(0, TokenEstimate.of(""))
        assertEquals(0, TokenEstimate.ofAll("", ""))
    }

    @Test
    fun `non empty text costs at least one token`() {
        // "÷4 后取整"会把一个字符压成 0，而 0 token 在 UI 上等于"空"
        assertEquals(1, TokenEstimate.of("a"))
        assertEquals(1, TokenEstimate.of("的"))
    }

    @Test
    fun `cjk is counted denser than latin`() {
        // 比的是**每个字符**的代价，不是等长字符串的总量 ——
        // 同样的字符数下，中文必须更贵
        val chinese = "这是一段中文文本内容"          // 10 个 CJK
        val english = "abcdefghij"                    // 10 个拉丁

        assertTrue(
            "单字中文应比单字英文贵：${TokenEstimate.of(chinese)} vs ${TokenEstimate.of(english)}",
            TokenEstimate.of(chinese) > TokenEstimate.of(english),
        )
    }

    @Test
    fun `scales linearly with repeated content`() {
        val once = TokenEstimate.of("你好世界")
        val tenTimes = TokenEstimate.of("你好世界".repeat(10))

        // of() 每次都会取整，放大十倍后误差也会放大 —— 断言比例而非绝对差
        val ratio = tenTimes.toDouble() / once
        assertTrue(
            "十倍内容应约等于十倍 token：$once -> $tenTimes（比值 $ratio）",
            ratio in 8.0..12.0,
        )
    }

    @Test
    fun `ofAll avoids per segment rounding`() {
        // 若逐段取整，"a"×1 会被抬到 1，十段就是 10；合计应为 10/4 ≈ 3
        val joined = TokenEstimate.ofAll(*Array(10) { "aaaa" })

        assertTrue("十段 4 字符合计应约 10 token，实际 $joined", joined in 9..11)
    }

    @Test
    fun `format is compact enough for the ring label`() {
        assertEquals("0", TokenEstimate.format(0))
        assertEquals("980", TokenEstimate.format(980))
        assertEquals("1k", TokenEstimate.format(1_000))
        // 一位小数只在 10k 以下保留；再往上位数更重要
        assertEquals("9.9k", TokenEstimate.format(9_940))
        assertEquals("12k", TokenEstimate.format(12_345))
        assertEquals("123k", TokenEstimate.format(123_456))
        assertEquals("1.2M", TokenEstimate.format(1_200_000))
    }

    @Test
    fun `format never exceeds five characters`() {
        // 圆环旁只有 ~4~5 个字符的位置
        listOf(1, 999, 1_000, 9_999, 12_345, 123_456, 999_999).forEach { value ->
            val text = TokenEstimate.format(value)
            assertTrue("format($value) = $text 太长了", text.length <= 5)
        }
    }
}
