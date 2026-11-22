package com.simplechat.app.data

import com.simplechat.app.R
import kotlinx.serialization.Serializable

/**
 * 第三方声音模型（TTS）接入配置。
 *
 * 结构对齐「独立工具 API」里的搜索 / 文件 / 生图三块：端点、密钥、能力开关
 * 都存在 DataStore，密钥加密。
 *
 * 与它们的区别是 [voices] 这一层：TTS 服务通常有**自己的音色清单**，
 * 需要从服务端拉取（[VoiceAdapter] 描述协议差异），再叠加用户手填的音色 id。
 */
@Serializable
data class VoiceApiConfig(
    val enabled: Boolean = false,
    /** 空 = 跟随聊天端点（多数聚合平台同一个 base）。 */
    val baseUrl: String = "",
    /** 复用聊天 key 还是用独立 key。 */
    val reuseChatKey: Boolean = true,
    val apiKeyEncrypted: String = "",
    /**
     * 协议适配：
     * - `OPENAI_TTS`：`POST {base}/v1/audio/speech`，音色是 `voice` 字段，
     *   音色清单要额外查 `/v1/audio/voices`（不是所有服务都提供）。
     * - `CUSTOM_OPENAI`：同上，但音色清单靠用户手填（服务端不提供列表接口）。
     */
    val adapter: String = "OPENAI_TTS",
    /** 服务端拉回来的音色 id（缓存，避免每次进页面都请求）。 */
    val voices: List<String> = emptyList(),
    /** 用户手填的音色 id（服务端没有列表接口时全靠它）。 */
    val customVoices: List<String> = emptyList(),
    /** 自定义播放时的模型名（如 `tts-1`、`qwen-tts`）。 */
    val model: String = "tts-1",
) {
    override fun toString() = "VoiceApiConfig(enabled=$enabled, adapter=$adapter, voices=${voices.size})"

    /** 端点到音频合成接口。 */
    fun speechEndpoint(chatBaseUrl: String): String {
        val configured = baseUrl.ifBlank { chatBaseUrl }
        val base = (com.simplechat.app.net.officialChatRoot(configured) ?: configured)
            .trim()
            .trimEnd('/')
            .removeSuffix("/anthropic/v1")
            .removeSuffix("/anthropic")
            .removeSuffix("/compatible-mode/v1")
            .removeSuffix("/v1")
        return "$base/v1/audio/speech"
    }

    fun key(chatKey: String): String {
        val key = if (reuseChatKey) chatKey else SecretBox.decrypt(apiKeyEncrypted)
        if (key.isBlank()) throw AppTextException(R.string.service_key_missing)
        if (!ApiKey.isHeaderSafe(key)) throw AppTextException(R.string.error_api_key_chars)
        return key
    }

    /** 可供选择的全部音色：服务端清单 + 手填，去重保序。 */
    val allVoices: List<String> get() = (voices + customVoices).distinct()
}
