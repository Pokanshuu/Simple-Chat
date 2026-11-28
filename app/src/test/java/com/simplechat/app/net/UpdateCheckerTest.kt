package com.simplechat.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号比较。
 *
 * 「检查更新」判断错了的两种代价都很直接：把老版本当新版（每次点都让人下载），
 * 或者把新版当老版（永远提示已是最新）。所以把规则钉死：去 `v` 前缀、逐段比数字、
 * 缺段按 0、同号时**正式版比预发布新**、预发布之间逐标识符比较。
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

    /**
     * 当前发布渠道的关键回归：`/releases/latest` 只给正式版，
     * 而 App 自己装在 `1.2.6-beta.5` 上 —— 不比较后缀就永远收不到 1.2.6。
     */
    @Test
    fun `beta 用户能收到同号正式版`() {
        assertTrue(isNewerVersion("v1.2.6", "1.2.6-beta.5"))
        assertFalse(isNewerVersion("1.2.6-beta.5", "1.2.6"))
        assertFalse(isNewerVersion("v1.2.6-beta.5", "1.2.6-beta.5"))
        // 后续版本的预发布也比当前 beta 新
        assertTrue(isNewerVersion("v1.2.7-beta.1", "1.2.6-beta.5"))
        // 更老的正式版仍然不是更新
        assertFalse(isNewerVersion("v1.2.5", "1.2.6-beta.5"))
    }

    @Test
    fun `预发布之间逐标识符比较`() {
        assertTrue(isNewerVersion("1.2.6-beta.6", "1.2.6-beta.5"))
        assertFalse(isNewerVersion("1.2.6-beta.5", "1.2.6-beta.6"))
        // 数字标识符按数值比，不是字符串
        assertTrue(isNewerVersion("1.2.6-beta.10", "1.2.6-beta.9"))
        // rc 比 beta 新
        assertTrue(isNewerVersion("1.2.6-rc.1", "1.2.6-beta.9"))
        // 前缀相同则短的更旧
        assertFalse(isNewerVersion("1.2.6-alpha", "1.2.6-alpha.1"))
        assertTrue(isNewerVersion("1.2.6-alpha.1", "1.2.6-alpha"))
    }

    @Test
    fun `认不出的 tag 不当成更新`() {
        assertFalse(isNewerVersion("release-2026.11", "1.2.6-beta.5"))
        assertFalse(isNewerVersion("", "1.2.6"))
        assertFalse(isNewerVersion("v", "1.2.6"))
    }

    @Test
    fun `大小写 v 与空白都不影响`() {
        assertTrue(isNewerVersion(" V1.1.6 ", "1.1.5"))
    }

    /**
     * 镜像前缀是「整段拼在 GitHub 链接前面」。
     *
     * 拼错了就是 404 —— 用户只会看到"下载失败"，根本不知道是链接构造错了。
     */
    @Test
    fun `镜像前缀按约定拼接`() {
        val url = "https://github.com/o/r/releases/download/v1/x.apk"
        assertEquals(url, UpdateChecker.Mirror.GITHUB.apply(url))
        assertEquals("https://gh-proxy.org/$url", UpdateChecker.Mirror.GH_PROXY.apply(url))
        assertEquals("https://ghfast.top/$url", UpdateChecker.Mirror.GHFAST.apply(url))
    }

    /** 直连的 prefix 必须为空，否则会拼出 `nullhttps://…` 这种东西。 */
    @Test
    fun `直连不加前缀`() {
        assertEquals("", UpdateChecker.Mirror.GITHUB.prefix)
        assertTrue(UpdateChecker.Mirror.GH_PROXY.prefix.endsWith("/"))
        assertTrue(UpdateChecker.Mirror.GHFAST.prefix.endsWith("/"))
    }
}
