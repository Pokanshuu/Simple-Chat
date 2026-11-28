package com.simplechat.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 下载文件名的安全化。
 *
 * 资产名来自网络 JSON（可能经由第三方加速站），直接 `File(dir, name)` 会被
 * `../databases/...` 穿出下载目录，覆盖应用私有文件。规则：只留安全字符、
 * 必须是 `.apk`，不符合就退化成固定名。
 */
class UpdateDownloaderTest {

    @Test
    fun `正常发布名原样保留`() {
        assertEquals(
            "SimpleChat-1.2.6-beta.5-release.apk",
            UpdateDownloader.safeApkName("SimpleChat-1.2.6-beta.5-release.apk"),
        )
    }

    @Test
    fun `路径分隔符被剥掉`() {
        assertEquals("x.apk", UpdateDownloader.safeApkName("../databases/x.apk"))
        assertEquals("x.apk", UpdateDownloader.safeApkName("..\\shared_prefs\\x.apk"))
        assertEquals("x.apk", UpdateDownloader.safeApkName("/data/data/x.apk"))
    }

    @Test
    fun `非 apk 或可疑名字退化成固定名`() {
        assertEquals("update.apk", UpdateDownloader.safeApkName("app.apk.exe"))
        assertEquals("update.apk", UpdateDownloader.safeApkName("app.zip"))
        assertEquals("update.apk", UpdateDownloader.safeApkName(".hidden.apk"))
        assertEquals("update.apk", UpdateDownloader.safeApkName("my app.apk"))
        assertEquals("update.apk", UpdateDownloader.safeApkName(".."))
        assertEquals("update.apk", UpdateDownloader.safeApkName(""))
    }

    @Test
    fun `超长名字不被接受`() {
        val long = "a".repeat(200) + ".apk"
        assertEquals("update.apk", UpdateDownloader.safeApkName(long))
    }
}
