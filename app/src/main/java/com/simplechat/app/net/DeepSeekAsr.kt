package com.simplechat.app.net

import com.simplechat.app.data.ApiKey
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

/** DeepSeek 官方语音转文字。录音和密钥只发送到固定的官方端点。 */
internal object DeepSeekAsr {
    private const val OFFICIAL_HOST = "api.deepseek.com"
    private const val ENDPOINT = "https://$OFFICIAL_HOST/v1/tools/asr"
    private const val MAX_WAV_BYTES = 8 * 1024 * 1024
    const val MAX_AUDIO_SECONDS = 120
    private const val MAX_RESPONSE_BYTES = 256L * 1024
    private const val SAMPLE_RATE = 16_000
    private const val BYTES_PER_SAMPLE = 2

    class Config internal constructor(internal val apiKey: String) {
        override fun equals(other: Any?): Boolean = other is Config && other.apiKey == apiKey
        override fun hashCode(): Int = apiKey.hashCode()
    }

    class AsrException(val code: Int) : IOException("ASR HTTP $code")

    internal val client by lazy {
        ChatApi.defaultClient().newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun configFor(providerId: String, baseUrl: String, apiKey: String): Config? {
        if (providerId != ProviderKind.DEEPSEEK.name) return null
        val key = apiKey.trim()
        if (key.isEmpty() || !ApiKey.isHeaderSafe(key)) return null
        val base = baseUrl.trim()
        if (base.isNotEmpty()) {
            val uri = try {
                URI(base)
            } catch (_: URISyntaxException) {
                return null
            }
            if (!"https".equals(uri.scheme, ignoreCase = true) ||
                !OFFICIAL_HOST.equals(uri.host, ignoreCase = true) ||
                (uri.port != -1 && uri.port != 443) ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
            ) return null
        }
        return Config(key)
    }

    suspend fun transcribe(config: Config, wav: ByteArray): String = withContext(Dispatchers.IO) {
        val call = client.newCall(requestFor(config, wav))
        val cancellation = call.cancelWithCoroutine()
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw AsrException(response.code)
                val source = response.body.source()
                val buffer = Buffer()
                while (buffer.size <= MAX_RESPONSE_BYTES) {
                    val count = source.read(buffer, minOf(8192L, MAX_RESPONSE_BYTES + 1 - buffer.size))
                    if (count == -1L) break
                }
                if (buffer.size > MAX_RESPONSE_BYTES) throw IOException("ASR response too large")
                transcriptOf(buffer.readUtf8())
            }
        } finally {
            cancellation.cancel()
            ensureActive()
        }
    }

    internal fun requestFor(config: Config, wav: ByteArray): Request {
        validateWav(wav)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        return Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Accept", "application/json")
            .post(multipart)
            .build()
    }

    internal fun transcriptOf(raw: String): String {
        val document = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val text = document?.get("text") as? JsonPrimitive
        return text?.takeIf { it.isString }?.content ?: throw IOException("Invalid ASR response")
    }

    private fun validateWav(wav: ByteArray) {
        require(wav.size in 44..MAX_WAV_BYTES) { "Invalid ASR WAV file size" }
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(header.int == 0x46464952 && header.int == wav.size - 8 && header.int == 0x45564157) {
            "Invalid ASR WAV RIFF header"
        }
        var hasFormat = false
        var hasAudio = false
        while (header.remaining() >= 8) {
            val id = header.int
            val size = header.int
            require(size >= 0 && size <= header.remaining() - (size and 1)) { "Invalid ASR WAV chunk size" }
            val end = header.position() + size + (size and 1)
            when (id) {
                0x20746d66 -> { // Format chunk
                    require(!hasFormat && size >= 16 && header.short.toInt() == 1 &&
                        header.short.toInt() == 1 && header.int == SAMPLE_RATE &&
                        header.int == SAMPLE_RATE * BYTES_PER_SAMPLE && header.short.toInt() == BYTES_PER_SAMPLE &&
                        header.short.toInt() == 16
                    ) { "ASR requires 16-bit PCM WAV at 16000 Hz mono" }
                    hasFormat = true
                }
                0x61746164 -> { // data
                    require(hasFormat && !hasAudio && size in BYTES_PER_SAMPLE..MAX_AUDIO_SECONDS * SAMPLE_RATE * BYTES_PER_SAMPLE &&
                        size % BYTES_PER_SAMPLE == 0
                    ) { "Invalid ASR WAV audio data or duration" }
                    hasAudio = true
                }
            }
            header.position(end)
        }
        require(hasAudio && !header.hasRemaining()) { "Invalid ASR WAV audio data" }
    }
}
