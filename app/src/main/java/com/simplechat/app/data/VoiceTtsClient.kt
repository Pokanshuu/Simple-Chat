package com.simplechat.app.data

import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 第三方 TTS 合成：把文本变成音频字节。
 *
 * 目前支持两种协议（见 [VoiceApiConfig.adapter]）：
 *
 * - `DASHSCOPE_TTS`（千问 MaaS，**实测可用**）：
 *   `POST {base}/api/v1/services/aigc/multimodal-generation/generation`
 *   返回 `{"output":{"audio":{"url":"...wav?签名"}}}`，再下载一次。
 *   坑：这个平台没有 `/v1/audio/speech`（404）；`input` 必须是
 *   `{"text": ...}`，写成 `{"messages": [...]}` 会报
 *   "Due to invalid text, invalid audio was returned"。
 *
 * - `OPENAI_TTS` / `CUSTOM_OPENAI`：
 *   `POST {base}/v1/audio/speech`，响应体直接就是音频。
 *
 * 返回统一是**音频字节**（wav / mp3 都可能），由播放侧按文件头判断。
 */
object VoiceTtsClient {

    sealed interface Result {
        data class Ok(val bytes: ByteArray) : Result
        data class Failed(val reason: String) : Result
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // 长文本合成 + 二次下载，给足余量
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class DashScopeResponse(
        val output: DashScopeOutput? = null,
        val code: String? = null,
        val message: String? = null,
    )

    @Serializable
    private data class DashScopeOutput(val audio: AudioRef? = null)

    @Serializable
    private data class AudioRef(val url: String? = null)

    /**
     * 合成一段文本。
     *
     * @param voiceName 音色 id（如 `Cherry`）；为空时用服务端默认。
     */
    suspend fun synthesize(
        config: VoiceApiConfig,
        chatKey: String,
        chatBaseUrl: String,
        text: String,
        voiceName: String,
    ): Result = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.Failed("文本为空")
        val key = runCatching { config.key(chatKey) }.getOrElse {
            return@withContext Result.Failed(it.message ?: "没有可用的 Key")
        }
        val endpoint = runCatching { config.speechEndpoint(chatBaseUrl) }.getOrElse {
            return@withContext Result.Failed(it.message ?: "端点无效")
        }
        Diagnostics.log("TTS", "synthesize adapter=${config.adapter} endpoint=$endpoint")
        try {
            when (config.adapter) {
                "DASHSCOPE_TTS" -> dashScope(config, endpoint, key, text, voiceName)
                else -> openAiAudio(config, endpoint, key, text, voiceName)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Exception) {
            Diagnostics.log("TTS", "exception ${t::class.simpleName}: ${t.message}")
            Result.Failed(t.message ?: t::class.simpleName ?: "合成失败")
        }
    }

    /** 千问 MaaS：先拿签名 URL，再下载 wav。 */
    private suspend fun dashScope(
        config: VoiceApiConfig,
        endpoint: String,
        key: String,
        text: String,
        voiceName: String,
    ): Result {
        val payload = buildString {
            append("{\"model\":")
            append(quote(config.model.ifBlank { "qwen3-tts-flash" }))
            append(",\"input\":{\"text\":")
            append(quote(text))
            append("},\"parameters\":{")
            if (voiceName.isNotBlank()) {
                append("\"voice\":")
                append(quote(voiceName))
                append(',')
            }
            append("\"format\":\"wav\"}}")
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val audioUrl = request.readResponse { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val parsed = runCatching { json.decodeFromString<DashScopeResponse>(body) }.getOrNull()
                val reason = humanize(parsed?.message ?: parsed?.code ?: "http ${response.code}")
                Diagnostics.log("TTS", "dashscope failed http=${response.code} reason=$reason")
                throw IOException(reason)
            }
            val parsed = runCatching { json.decodeFromString<DashScopeResponse>(body) }.getOrNull()
            /*
             * 服务端给的音频地址是 **http**（阿里云 OSS）；Android 9+ 默认禁明文，
             * 直接下载会抛 UnknownServiceException 而不是"没声音"。见 [secureUrl]。
             */
            parsed?.output?.audio?.url ?: throw IOException("响应里没有音频地址")
        }
        val bytes = download(audioUrl) ?: return Result.Failed("音频下载失败")
        Diagnostics.log("TTS", "dashscope ok bytes=${bytes.size} model=${config.model}")
        return Result.Ok(bytes)
    }

    /** OpenAI 兼容：响应体直接是音频二进制。 */
    private suspend fun openAiAudio(
        config: VoiceApiConfig,
        endpoint: String,
        key: String,
        text: String,
        voiceName: String,
    ): Result {
        val payload = buildString {
            append("{\"model\":")
            append(quote(config.model.ifBlank { "tts-1" }))
            append(",\"input\":")
            append(quote(text))
            append(",\"voice\":")
            append(quote(voiceName.ifBlank { "alloy" }))
            append('}')
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return request.readResponse { response ->
            if (!response.isSuccessful) {
                val text = response.body.string().take(200)
                return@readResponse Result.Failed(humanize(text.ifBlank { "http ${response.code}" }))
            }
            val bytes = response.body.bytes()
            if (bytes.isEmpty()) return@readResponse Result.Failed("服务端返回了空音频")
            Diagnostics.log("TTS", "openai ok bytes=${bytes.size}")
            Result.Ok(bytes)
        }
    }

    /**
     * 把音频 URL 的 scheme 提成 https。
     *
     * 千问 MaaS 的响应里 `output.audio.url` 是 **http**（阿里云 OSS 域名），
     * 而 Android 9+ 默认禁止明文流量 —— OkHttp 会直接抛
     * `UnknownServiceException: CLEARTEXT communication ... not permitted`，
     * 表现就是"合成成功、没有任何声音、也看不到明显报错"。
     * OSS 签名只覆盖 path + query、不含 scheme，同一个对象 https 也能下载。
     */
    private fun secureUrl(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    private suspend fun download(url: String): ByteArray? = try {
        Request.Builder().url(secureUrl(url)).get().build().readResponse { response ->
            Diagnostics.log("TTS", "download http=${response.code}")
            if (response.isSuccessful) response.body.bytes().takeIf { it.isNotEmpty() } else null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Exception) {
        Diagnostics.log("TTS", "download failed ${t::class.simpleName}: ${t.message}")
        null
    }

    private suspend fun <T> Request.readResponse(read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(this)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    val value = try {
                        response.use(read)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                        return
                    }
                    continuation.resume(value)
                }
            })
        }

    /**
     * 服务端错误里有两句特别难懂，转成人话：
     * - `Due to invalid text, invalid audio was returned` → 实际是 `input` 结构不对；
     * - `Invalid voice specified ... not licensed` → 音色不在这个 key 的授权里。
     */
    private fun humanize(raw: String): String = when {
        raw.contains("invalid audio was returned", ignoreCase = true) ->
            "合成失败：请求结构不被接受（input 应为 {\"text\": ...}）"
        raw.contains("not licensed", ignoreCase = true) ->
            "该音色不在当前 Key 的授权范围内"
        raw.contains("voice property is required", ignoreCase = true) ->
            "缺少音色参数"
        raw.startsWith("http ") -> "请求失败（$raw）"
        else -> raw
    }

    /** 手写 JSON 转义：文本是任意用户输入。 */
    private fun quote(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
