package com.simplechat.app.net

import com.simplechat.app.ui.chat.VoiceWavBuffer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import okhttp3.MultipartBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DeepSeekAsrTest {
    @Test
    fun configRequiresOfficialProviderAndHttpsHost() {
        for (base in listOf("", "https://api.deepseek.com", " https://API.DEEPSEEK.COM:443/v1/ ", "https://api.deepseek.com/anthropic")) {
            assertNotNull(base, DeepSeekAsr.configFor("DEEPSEEK", base, "sk-example"))
        }
        for (base in listOf(
            "api.deepseek.com",
            "http://api.deepseek.com",
            "https://api.deepseek.com.evil.test",
            "https://evil.test/api.deepseek.com",
            "https://api.deepseek.com@evil.test",
            "https://user@api.deepseek.com",
            "https://api.deepseek.com:444",
            "https://api.deepseek.com?api_key=secret",
            "https://api.deepseek.com#fragment",
            "https://api.deepseek.com./",
        )) {
            assertNull(base, DeepSeekAsr.configFor("DEEPSEEK", base, "sk-example"))
        }
        assertNull(DeepSeekAsr.configFor("CUSTOM", "https://api.deepseek.com", "sk-example"))
        assertNull(DeepSeekAsr.configFor("OPENCODE_GO", "", "sk-example"))
    }

    @Test
    fun configRequiresNonemptyHeaderSafeKey() {
        for (key in listOf("", "  ", "sk-test\nother", "sk-你的密钥", "Bearer sk-test", "sk-test\u0000")) {
            assertNull(DeepSeekAsr.configFor("DEEPSEEK", "", key))
        }
        assertNotNull(DeepSeekAsr.configFor("DEEPSEEK", "", " sk-example "))
    }

    @Test
    fun requestPostsExactlyOneWavPartToFixedEndpointWithoutRedirects() {
        val wav = wavWithSamples(2)
        val request = DeepSeekAsr.requestFor(config(), wav)
        assertEquals("POST", request.method)
        assertEquals("https://api.deepseek.com/v1/tools/asr", request.url.toString())
        assertEquals("Bearer sk-example", request.header("Authorization"))
        assertEquals("application/json", request.header("Accept"))
        assertFalse(DeepSeekAsr.client.followRedirects)
        assertFalse(DeepSeekAsr.client.followSslRedirects)
        val body = request.body as MultipartBody
        assertEquals(MultipartBody.FORM, body.type)
        val part = body.parts.single()
        assertEquals("form-data; name=\"file\"; filename=\"speech.wav\"", part.headers?.get("Content-Disposition"))
        assertEquals("audio/wav", part.body.contentType().toString())
        val bytes = Buffer().apply { part.body.writeTo(this) }.readByteArray()
        assertArrayEquals(wav, bytes)
    }

    @Test
    fun invalidWavFormatSizeAndDurationAreRejectedBeforeUpload() {
        val wrongSampleRate = wavWithSamples(1)
        ByteBuffer.wrap(wrongSampleRate).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 48_000)
        val config = config()
        DeepSeekAsr.requestFor(config, wavWithSamples(120 * 16_000))
        for (wav in listOf(
            byteArrayOf(0), wrongSampleRate, wavWithSamples(0),
            wavWithSamples(120 * 16_000 + 1), ByteArray(8 * 1024 * 1024 + 1),
        )) {
            assertThrows(IllegalArgumentException::class.java) { DeepSeekAsr.requestFor(config, wav) }
        }
    }

    @Test
    fun transcriptRequiresAJsonStringAndPreservesItsContent() {
        assertEquals("你好，世界。", DeepSeekAsr.transcriptOf("""{"text":"你好，世界。","duration_ms":2000}"""))
        assertEquals("", DeepSeekAsr.transcriptOf("""{"text":""}"""))
        for (raw in listOf("{", "[]", "{}", """{"text":null}""", """{"text":1}""", """{"text":{}}""")) {
            assertThrows(IOException::class.java) { DeepSeekAsr.transcriptOf(raw) }
        }
    }

    private fun config() = requireNotNull(DeepSeekAsr.configFor("DEEPSEEK", "", " sk-example "))

    private fun wavWithSamples(count: Int) = VoiceWavBuffer(count).apply {
        append(ShortArray(count), count)
    }.toWav()
}
