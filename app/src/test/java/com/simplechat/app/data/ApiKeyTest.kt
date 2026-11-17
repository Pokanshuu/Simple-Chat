package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API Key 的清洗与字符集判断。
 *
 * 真机反馈过一条错误：`Unexpected char 0x4f55 at 8 in Authorization value` ——
 * 0x4f55 就是「何」，也就是 Key 里混进了中文。OkHttp 只报第一个非法字符的码点，
 * 所以这里把"什么算合法"钉死，并在入口把空白清掉（粘贴带换行是最常见的一种）。
 */
class ApiKeyTest {

    @Test
    fun `两端与中间的空白都被清掉`() {
        assertEquals("sk-abc123", ApiKey.normalize("  sk-abc123\n"))
        assertEquals("sk-abc123", ApiKey.normalize("sk-\nab\r\nc123"))
        // 全角空格也算空白
        assertEquals("sk-abc", ApiKey.normalize("sk-\u3000abc"))
    }

    @Test
    fun `纯英文数字与常见符号是合法的`() {
        assertTrue(ApiKey.isHeaderSafe("sk-abc_DEF.123~+/="))
        assertNull(ApiKey.firstIllegalChar("sk-abc_DEF.123~+/="))
    }

    @Test
    fun `中文会被判为非法并给出第一个非法字符`() {
        val key = "sk-你的密钥"
        assertFalse(ApiKey.isHeaderSafe(key))
        assertEquals('你', ApiKey.firstIllegalChar(key))
    }

    @Test
    fun `全角标点与换行同样非法`() {
        assertFalse(ApiKey.isHeaderSafe("sk-abc。"))
        assertEquals('。', ApiKey.firstIllegalChar("sk-abc。"))
        assertFalse(ApiKey.isHeaderSafe("sk-abc\ndef"))
        assertEquals('\n', ApiKey.firstIllegalChar("sk-abc\ndef"))
    }

    @Test
    fun `空 Key 不算非法`() {
        // "没填"由另一条路径提示（error_no_api_key），不该在这里报字符问题
        assertTrue(ApiKey.isHeaderSafe(""))
        assertNull(ApiKey.firstIllegalChar(""))
    }
}
