package com.simplechat.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek 官方 TTS 的请求格式与分块。
 *
 * 没有 Key 也能把这些钉死：端点怎么拼、字段叫什么、PCM 怎么分块 —— 这三样一旦错，
 * 用户看到的是"点了朗读没反应/报错"，而错误信息未必指得出来。
 */
class DeepSeekTtsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 端点 ──────────────────────────────────────────────

    @Test
    fun `端点固定在官方域，不随对话 Base URL 变`() {
        // 对话 Base URL 可能被改成网关 / 加了 /v1；官方语音合成只认官方域
        assertEquals(
            "https://api.deepseek.com/v1/tools/tts",
            DeepSeekTts.endpoint(),
        )
    }

    // ── 音色名字的存储/解析 ───────────────────────────────

    @Test
    fun `官方音色用前缀存名字，和系统音色区分开`() {
        assertEquals("deepseek:stella", DeepSeekTts.storageNameOf("stella"))
        assertTrue(DeepSeekTts.isOfficialVoice("deepseek:tide"))
        assertFalse(DeepSeekTts.isOfficialVoice("zh-cn-x-ccc-local"))
        assertEquals("tide", DeepSeekTts.voiceIdOf("deepseek:tide"))
        assertNull(DeepSeekTts.voiceIdOf("zh-cn-x-ccc-local"))
        assertNull(DeepSeekTts.voiceIdOf("deepseek:"))
    }

    @Test
    fun `服务商选 DeepSeek 且填了 Key 就认为官方朗读可用`() {
        // 只看"服务商 + Key"：官方语音端点固定在官方域，与对话 Base URL 无关
        // （用户可能把 Base URL 加了 /v1 或交给网关，语音仍应可用）
        assertTrue(DeepSeekTts.isOfficialTtsVoiceAvailable(providerId = "DEEPSEEK", apiKey = "sk-xxx"))
        // OpenCode Go 没有官方 TTS
        assertFalse(DeepSeekTts.isOfficialTtsVoiceAvailable(providerId = "OPENCODE_GO", apiKey = "sk-xxx"))
        // 没填 Key
        assertFalse(DeepSeekTts.isOfficialTtsVoiceAvailable(providerId = "DEEPSEEK", apiKey = ""))
    }

    // ── 请求体 ────────────────────────────────────────────

    @Test
    fun `请求体字段与官方文档一致`() {
        val body = json.parseToJsonElement(
            DeepSeekTts.requestBody("念这一段", "tide"),
        ).jsonObject
        assertEquals("念这一段", body.getValue("text").jsonPrimitive.content)
        assertEquals("tide", body.getValue("voice_id").jsonPrimitive.content)
        assertEquals("pcm", body.getValue("format").jsonPrimitive.content)
        assertTrue(body.getValue("filter_markdown").jsonPrimitive.content.toBoolean())
    }

    // ── 分块 ──────────────────────────────────────────────

    @Test
    fun `短文本不切块`() {
        assertEquals(listOf("你好，世界。"), DeepSeekTts.splitForSpeech("你好，世界。"))
        assertTrue(DeepSeekTts.splitForSpeech("   ").isEmpty())
    }

    @Test
    fun `长文本每块不超过上限且内容不丢`() {
        val text = (1..400).joinToString("") { "第${it}句。" }
        val chunks = DeepSeekTts.splitForSpeech(text, maxChars = 120)
        assertTrue("应当切成多块", chunks.size > 1)
        assertTrue("每块都不能超限", chunks.all { it.length <= 120 })
        // 中文句子之间无空格：拼回去应当与原文完全一致
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `尽量切在句号后面`() {
        // 120 的窗口里第 90 个字符附近是句号，切点应落在它后面
        val head = "甲".repeat(88) + "。"
        val text = head + "乙".repeat(200)
        val chunks = DeepSeekTts.splitForSpeech(text, maxChars = 120)
        assertEquals(head, chunks.first())
    }

    @Test
    fun `不会把 emoji 的代理对切成两半`() {
        // 让 emoji 正好落在硬切边界上
        val text = "字".repeat(119) + "😀" + "字".repeat(50)
        val chunks = DeepSeekTts.splitForSpeech(text, maxChars = 120)
        assertTrue(chunks.all { it.length <= 120 })
        chunks.forEach { chunk ->
            assertFalse("块首不能是低代理", chunk.firstOrNull()?.isLowSurrogate() == true)
            assertFalse("块尾不能是高代理", chunk.lastOrNull()?.isHighSurrogate() == true)
        }
    }

    // ── 错误原因提取 ──────────────────────────────────────

    @Test
    fun `从错误响应里取可读原因`() {
        assertEquals(
            "no readable text in input",
            DeepSeekTts.serverMessageOf("""{"error":{"message":"no readable text in input"}}"""),
        )
        assertEquals(
            "rate limited",
            DeepSeekTts.serverMessageOf("""{"message":"rate limited"}"""),
        )
        assertEquals("Bad Request", DeepSeekTts.serverMessageOf("Bad Request"))
        assertNull(DeepSeekTts.serverMessageOf("   "))
        assertNull(DeepSeekTts.serverMessageOf(null))
    }
}

