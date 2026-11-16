package com.simplechat.app.net

import androidx.annotation.StringRes
import com.simplechat.app.R
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepSeek **官方**语音合成（TTS）。
 *
 * 端点与参数来自官方文档：`POST {base}/v1/tools/tts`
 *
 * ```json
 * { "text": "…", "voice_id": "stella" | "tide", "format": "pcm", "filter_markdown": true }
 * ```
 *
 * 响应是**裸 PCM**：16 bit、单声道、24 kHz。所以播放要自己用 `AudioTrack` 边收边写，
 * 不能等整段下来 —— 一次朗读可能是几千字的回复，攒成一个 `ByteArray` 既费内存、
 * 又要等全部合成完才出声。
 *
 * ### 为什么单独一个类
 *
 * - 请求/分块/URL 都是**纯函数**，能单测（没有 Key 也能把格式钉死）；
 * - 只有 [streamPcm] 碰网络，且它把"逐块回调"暴露给界面层，播放器不关心协议。
 *
 * ### 只对官方服务商可用
 *
 * 官方 TTS 只在 `api.deepseek.com` 上提供，第三方中转（OpenCode Go / 自建）没有。
 * 所以设置里只有"服务商 = DeepSeek 官方 + Key 已填"时才列出这两个音色（见
 * [isOfficialTtsVoiceAvailable]），系统 TTS 仍然照常可用。
 */
object DeepSeekTts {

    /** 音色 id 的存储前缀：与系统音色的机器名（`zh-cn-x-…`）区分开。 */
    const val VOICE_PREFIX = "deepseek:"

    /** 默认音色（官方文档：Stella 为女声、默认）。 */
    const val DEFAULT_VOICE = "stella"

    /** PCM 采样率。官方文档：16 bit / 单声道 / 24 kHz。 */
    const val SAMPLE_RATE = 24_000

    /** 单声道 16 bit：每帧 2 字节。 */
    const val BYTES_PER_FRAME = 2

    /**
     * 每次请求最多多少字符。
     *
     * 官方上限是 10,000 计费字符 / 64KiB 请求体；这里取 3,000：
     * 首块更快出声、失败重试的代价更小，而长文只是多分几块、边下边播听不出停顿。
     */
    const val MAX_CHARS_PER_CHUNK = 3_000

    /** 官方支持的两个音色。[labelRes] 是界面上的名字。 */
    enum class Voice(val id: String, @StringRes val labelRes: Int) {
        STELLA(DEFAULT_VOICE, R.string.tts_voice_stella),
        TIDE("tide", R.string.tts_voice_tide),
    }

    /**
     * 一次合成所需的接入参数。
     *
     * 只带 Key：**官方 TTS 端点固定在 `api.deepseek.com`**，与"对话用的 Base URL"无关。
     * 用户把对话 Base URL 改成自建网关（或加了 `/v1`）时，官方语音仍然应该走官方域 ——
     * 那个地址只认官方签发的 Key。
     */
    data class Config(val apiKey: String)

    /** 官方接口失败（带状态码与可读原因），供界面层翻成人话。 */
    class TtsException(val code: Int, val detail: String?) : IOException("TTS HTTP $code: ${detail.orEmpty()}")

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 官方 TTS 的 HTTP 客户端。
     *
     * 与对话共用同一套 UA / 连接设置，但**必须有读超时**：对话流可以几十秒不吐字
     * （那是模型在思考，正常），而 TTS 是"请求后很快开始吐音频"，卡住就是卡住。
     */
    private val client by lazy {
        ChatApi.defaultClient().newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 音色 id → 存储用的名字（设置里/DataStore 里存这个）。 */
    fun storageNameOf(voiceId: String): String = "$VOICE_PREFIX$voiceId"

    /** 这个名字是不是官方音色。 */
    fun isOfficialVoice(name: String): Boolean = name.startsWith(VOICE_PREFIX)

    /** 官方音色名字 → 音色 id；不是官方音色返回 null。 */
    fun voiceIdOf(name: String): String? =
        if (!isOfficialVoice(name)) null else name.removePrefix(VOICE_PREFIX).takeIf { it.isNotBlank() }

    /** 官方域（对话 Base URL 可能被改成网关，但语音合成只认官方域）。 */
    const val OFFICIAL_BASE_URL = "https://api.deepseek.com"

    /** 官方 TTS 端点：固定官方域，不随对话 Base URL 变。 */
    fun endpoint(): String = "$OFFICIAL_BASE_URL/v1/tools/tts"

    /**
     * 这次朗读是否可用官方通道。
     *
     * 判据只有两条：**服务商选了 DeepSeek 官方**、**填了 Key**。
     * 不再看 Base URL —— 官方语音端点固定在官方域，用户即使把对话 Base URL
     * 改成网关或加了 `/v1`，只要手里是官方 Key 就仍然可用。
     */
    fun isOfficialTtsVoiceAvailable(providerId: String, apiKey: String): Boolean =
        providerId == ProviderKind.DEEPSEEK.name && apiKey.isNotBlank()

    /** 请求体（纯函数，单测钉住字段名与取值）。 */
    fun requestBody(text: String, voiceId: String): String = buildJsonObject {
        put("text", text)
        put("voice_id", voiceId)
        put("format", "pcm")
        // 让服务端也过滤一遍 markdown：本地已经剥过一层，双保险
        put("filter_markdown", true)
    }.toString()

    /**
     * 把正文切成若干段（每段 ≤ [maxChars]），用于逐段合成、边下边播。
     *
     * 尽量切在**段/句**边界上：TTS 对"半句话"的停顿处理不好，切在逗号或句号后面
     * 最自然。硬边界也不能切坏代理对（emoji）—— 那会产生半个字符，整段音频都废。
     *
     * 纯函数，有单测（`DeepSeekTtsTest`）。
     */
    fun splitForSpeech(text: String, maxChars: Int = MAX_CHARS_PER_CHUNK): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || maxChars <= 0) return emptyList()
        val out = ArrayList<String>(trimmed.length / maxChars + 2)
        var start = 0
        while (start < trimmed.length) {
            var end = minOf(start + maxChars, trimmed.length)
            if (end < trimmed.length) {
                // 在窗口的最后三分之一里找句读边界，找不到就直接硬切
                val floor = start + maxChars / 3 * 2
                var cut = -1
                for (index in end - 1 downTo floor) {
                    if (trimmed[index].isSentenceBoundary()) {
                        cut = index + 1
                        break
                    }
                }
                if (cut > start) end = cut
                // 别把代理对切成两半
                if (end < trimmed.length && end - 1 >= start && trimmed[end - 1].isHighSurrogate()) end--
            }
            val chunk = trimmed.substring(start, end).trim()
            if (chunk.isNotEmpty()) out += chunk
            start = end
        }
        return out
    }

    private fun Char.isSentenceBoundary(): Boolean =
        this == '。' || this == '！' || this == '？' || this == '\n' ||
            this == '.' || this == '!' || this == '?' || this == ';' || this == '；'

    /**
     * 合成一段文字，按到达顺序回调 PCM 字节块。
     *
     * 回调在 IO 线程上执行，用户应当**在这里直接写 `AudioTrack`**（阻塞写会把
     * 网络读取自然地限速到播放速度，不需要额外缓冲）。
     *
     * @throws TtsException 服务端返回非 2xx（带状态码与响应体里的错误原因）。
     * @throws IOException 网络层失败。
     */
    suspend fun streamPcm(
        config: Config,
        text: String,
        voiceId: String,
        onPcm: (bytes: ByteArray, length: Int) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint())
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Accept", "audio/pcm, application/octet-stream")
                .post(requestBody(text, voiceId).toByteArray(Charsets.UTF_8).toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = runCatching { response.body?.string() }.getOrNull()
                    throw TtsException(response.code, serverMessageOf(raw))
                }
                val body = response.body ?: throw IOException("empty TTS response")
                body.byteStream().use { stream ->
                    val buffer = ByteArray(STREAM_CHUNK_BYTES)
                    while (true) {
                        ensureActive()
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        onPcm(buffer, read)
                    }
                }
            }
        }
    }

    /** 从错误响应体里取一句可读原因（OpenAI 风格 `{"error":{"message":…}}` 或原文）。 */
    internal fun serverMessageOf(raw: String?): String? {
        val text = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val message = parsed?.get("error")?.let { it as? JsonObject }
            ?.get("message")?.jsonPrimitive?.contentOrNull
            ?: parsed?.get("message")?.jsonPrimitive?.contentOrNull
        return (message ?: text).take(MaxErrorMessageChars)
    }

    /** 错误原因在界面上的保留上限。 */
    private const val MaxErrorMessageChars = 200

    /**
     * 一次网络读最多拿回多少字节。
     *
     * 播放器要用它来算"预滚阶段最多能往缓冲里塞多少"：预滚时轨道还没开始放，
     * 数据不会被消费，塞超缓冲就会阻塞（见 `SpeechOutput.preRollBytesFor`）。
     */
    const val STREAM_CHUNK_BYTES = 8 * 1024
}



