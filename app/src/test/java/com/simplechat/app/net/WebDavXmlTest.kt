package com.simplechat.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 协议纯函数（docs/40 §50.8.1）。
 *
 * 这些是服务端实现的"兼容层"：命名空间前缀、href 形态（绝对 URL / 相对路径 /
 * 编码路径）、ETag 弱校验——各家网盘各写各的。这里钉住我们的容忍边界。
 */
class WebDavXmlTest {

    private val multistatus = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/dav/SimpleChat/conversations/</d:href>
    <d:propstat>
      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/SimpleChat/conversations/abc123.json</d:href>
    <d:propstat>
      <d:prop>
        <d:resourcetype/>
        <d:getetag>"ETAG-1"</d:getetag>
        <d:getcontentlength>2048</d:getcontentlength>
        <d:getlastmodified>Sun, 29 Nov 2026 02:00:00 GMT</d:getlastmodified>
      </d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/SimpleChat/other/outside.json</d:href>
    <d:propstat>
      <d:prop><d:resourcetype/><d:getetag>"outside"</d:getetag></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""

    @Test
    fun `parses entries under the queried directory only`() {
        val entries = parseDavMultistatus(multistatus, "SimpleChat/conversations")
        // 目录自身被跳过；父目录之外的条目被忽略；只剩一个文件
        assertEquals(1, entries.size)
        val shard = entries.single()
        assertEquals("abc123.json", shard.path)
        assertFalse(shard.isDirectory)
        assertEquals(2048L, shard.size)
        assertEquals("etag-1", shard.etag)
        assertEquals(1_795_917_600_000L, shard.lastModifiedMillis)
    }

    @Test
    fun `skips non-2xx propstat`() {
        val xml = """<d:multistatus xmlns:d="DAV:"><d:response>
            <d:href>/dav/SimpleChat/x.json</d:href>
            <d:propstat><d:prop><d:getetag>"no"</d:getetag></d:prop>
              <d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
            <d:propstat><d:prop><d:getetag>"yes"</d:getetag></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
        </d:response></d:multistatus>"""
        assertEquals("yes", parseDavMultistatus(xml, "SimpleChat").single().etag)
    }

    @Test
    fun `parses namespace prefixes other than d`() {
        val xml = """<D:multistatus xmlns:D="DAV:"><D:response>
            <D:href>/root/a.json</D:href>
            <D:propstat><D:prop><D:getetag>"A"</D:getetag></D:prop>
              <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
        </D:response></D:multistatus>"""
        assertEquals("a.json", parseDavMultistatus(xml, "root").single().path)
    }

    @Test
    fun `absolute href and encoded path are normalised`() {
        val xml = """<d:multistatus xmlns:d="DAV:"><d:response>
            <d:href>https://dav.example.com/dav/SimpleChat/%E4%B8%AD%E6%96%87.json</d:href>
            <d:propstat><d:prop><d:resourcetype/></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
        </d:response></d:multistatus>"""
        assertEquals("中文.json", parseDavMultistatus(xml, "SimpleChat").single().path)
    }

    @Test
    fun `invalid xml fails loudly`() {
        val error = runCatching { parseDavMultistatus("<html><d:response", "SimpleChat") }.exceptionOrNull()
        assertTrue(error is DavParseException)
    }

    @Test
    fun `etag normalisation`() {
        assertEquals("abc", normalizeDavEtag("\"abc\""))
        assertEquals("abc", normalizeDavEtag("W/\"abc\""))
        assertEquals("abc", normalizeDavEtag("  w/ABC  "))
        assertNull(normalizeDavEtag(null))
        assertNull(normalizeDavEtag("  "))
    }

    @Test
    fun `path helpers`() {
        assertEquals("SimpleChat/conversations", normalizeDavPath("/SimpleChat/conversations/"))
        assertEquals("https://host/dav/x", joinDavPath("https://host/dav", "x"))
        assertEquals("a/b/c", joinDavPath("a/", "/b/", "c"))
        assertEquals("abc.json", relativeDavPath("/dav/conv/abc.json", "conv"))
        assertNull(relativeDavPath("/dav/other/x.json", "conv"))
        assertEquals("中文.json", relativeDavPath("/dav/conv/%E4%B8%AD%E6%96%87.json", "conv"))
    }

    @Test
    fun `shard file name is stable sha256`() {
        val name = davShardFileName("会话-1")
        assertTrue(name.endsWith(".json"))
        // SHA-256 十六进制 64 位 + .json
        assertEquals(69, name.length)
        assertEquals(name, davShardFileName("会话-1"))
        assertFalse(name == davShardFileName("会话-2"))
        // 不允许出现路径分隔符/中文
        assertTrue(name.all { it.isDigit() || it in 'a'..'z' || it == '.' })
    }

    @Test
    fun `http date parsing tolerates garbage`() {
        assertEquals(1_795_917_600_000L, parseHttpDateMillis("Sun, 29 Nov 2026 02:00:00 GMT"))
        assertNull(parseHttpDateMillis("not a date"))
    }
}
