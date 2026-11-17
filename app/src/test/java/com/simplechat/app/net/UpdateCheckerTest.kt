package com.simplechat.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号比较。
 *
 * 「检查更新」判断错了的两种代价都很直接：把老版本当新版（每次点都让人下载），
 * 或者把新版当老版（永远提示已是最新）。所以把规则钉死：去 `v` 前缀、逐段比数字、
 * 缺段按 0、预发布后缀不参与比较。
 */
class UpdateCheckerTest {

    @Test
    fun `补丁号变大就是新版本`() {
        assertTrue(isNewerVersion("v1.1.6", "1.1.5"))
        assertFalse(isNewerVersion("v1.1.5", "1.1.5"))
        assertFalse(isNewerVersion("v1.1.4", "1.1.5"))
    }

    @Test
    fun `次版本与主版本同样按数值比较`() {
        assertTrue(isNewerVersion("v1.2.0", "1.1.9"))
        assertTrue(isNewerVersion("2.0.0", "1.99.99"))
        // 字符串比较会把 10 判成小于 9
        assertTrue(isNewerVersion("1.1.10", "1.1.9"))
    }

    @Test
    fun `缺段按零处理`() {
        assertFalse(isNewerVersion("1.1", "1.1.0"))
        assertTrue(isNewerVersion("1.1.1", "1.1"))
    }

    @Test
    fun `预发布后缀不参与比较`() {
        // v1.1.6-beta.1 的版本号本身仍是 1.1.6，比 1.1.5 新
        assertTrue(isNewerVersion("v1.1.6-beta.1", "1.1.5"))
        // 但它不比正式版 1.1.6 新
        assertFalse(isNewerVersion("v1.1.6-beta.1", "1.1.6"))
    }

    @Test
    fun `大小写 v 与空白都不影响`() {
        assertTrue(isNewerVersion(" V1.1.6 ", "1.1.5"))
    }
}
