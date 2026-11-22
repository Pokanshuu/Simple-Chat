package com.simplechat.app.data

import com.simplechat.app.R
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    /**
     * 音色 id → 显示名。
     *
     * 只为一件事：界面上别再出现光秃秃的 `Cherry`，更别出现一串平台生成的
     * 克隆音色 id。用户给音色起的名字存在这里；缺省时回退到 [VoiceCatalog]
     * 的内置目录，再回退到 id 本身。
     */
    val voiceLabels: Map<String, String> = emptyMap(),
    /** 自定义播放时的模型名（如 `tts-1`、`qwen-tts`）。 */
    val model: String = "tts-1",
) {
    override fun toString() = "VoiceApiConfig(enabled=$enabled, adapter=$adapter, voices=${voices.size})"

    /** Local readiness only; displaying a voice does not make a network request. */
    internal fun isConfigured(chatBaseUrl: String, chatKey: String): Boolean =
        enabled && baseUrl.ifBlank { chatBaseUrl }.trim().toHttpUrlOrNull() != null &&
            (if (reuseChatKey) chatKey.isNotBlank() && ApiKey.isHeaderSafe(chatKey) else apiKeyEncrypted.isNotBlank())

    /** 端点到音频合成接口。各协议路径不同，按 [adapter] 分支。 */
    fun speechEndpoint(chatBaseUrl: String): String {
        val configured = baseUrl.ifBlank { chatBaseUrl }
        val base = (com.simplechat.app.net.officialChatRoot(configured) ?: configured)
            .trim()
            .trimEnd('/')
            .removeSuffix("/anthropic/v1")
            .removeSuffix("/anthropic")
            .removeSuffix("/compatible-mode/v1")
            .removeSuffix("/v1")
        return when (adapter) {
            /*
             * 千问 MaaS（实测可用）：
             *   POST {base}/api/v1/services/aigc/multimodal-generation/generation
             *   {"model":"qwen3-tts-flash","input":{"text":"..."},
             *    "parameters":{"voice":"Cherry"}}
             * 返回 {"output":{"audio":{"url":"...wav?签名"}}}
             *
             * 注意两个坑（都踩过）：
             * - 这个平台**没有** /v1/audio/speech（404）；
             * - input 必须是 `{"text": ...}`，写成 `{"messages": [...]}`
             *   会报 "Due to invalid text, invalid audio was returned"。
             */
            "DASHSCOPE_TTS" -> "$base/api/v1/services/aigc/multimodal-generation/generation"
            else -> "$base/v1/audio/speech"        }
    }

    companion object {
        /** DashScope（千问 MaaS）协议。 */
        const val AdapterDashScope = "DASHSCOPE_TTS"
        /** OpenAI 兼容的 `/v1/audio/speech`。 */
        const val AdapterOpenAi = "OPENAI_TTS"
        /** 同上，但音色清单只能手填（服务端不提供列表接口）。 */
        const val AdapterCustomOpenAi = "CUSTOM_OPENAI"

        /** 千问 MaaS 的快速预设（key 仍走复用聊天 key 或手填）。 */
        fun qwenDashScopePreset(chatKey: String? = null): VoiceApiConfig = VoiceApiConfig(
            enabled = true,
            baseUrl = "https://maas.qianwenaiapi.com",
            reuseChatKey = true,
            adapter = AdapterDashScope,
            model = "qwen3-tts-flash",
            customVoices = VoiceCatalog.dashScope.map { it.id },
            voiceLabels = VoiceCatalog.dashScope.associate { it.id to it.label },
        )

        /** 把某个协议的内置音色一次性并进来（保留用户已有的自定义音色）。 */
        fun withBuiltInVoices(config: VoiceApiConfig): VoiceApiConfig {
            val entries = VoiceCatalog.builtIn(config.adapter).filterNot { it.id in config.allVoices }
            return config.copy(
                customVoices = (config.customVoices + entries.map { it.id }).distinct(),
                voiceLabels = entries.associate { it.id to it.label } + config.voiceLabels,
            )
        }
    }

    /**
     * 音色显示名：用户起的名字 → 内置目录 → id 本身。
     *
     * 最后那一层不能省：克隆音色 / 服务端新上线的音色我们不可能都内置，
     * 显示原 id 总比显示空白强。
     */
    fun labelOf(id: String): String =
        voiceLabels[id]?.takeIf { it.isNotBlank() }
            ?: VoiceCatalog.builtInLabel(adapter, id)
            ?: id

    fun key(chatKey: String): String {
        val key = if (reuseChatKey) chatKey else SecretBox.decrypt(apiKeyEncrypted)
        if (key.isBlank()) throw AppTextException(R.string.service_key_missing)
        if (!ApiKey.isHeaderSafe(key)) throw AppTextException(R.string.error_api_key_chars)
        return key
    }

    /** 可供选择的全部音色：服务端清单 + 手填，去重保序。 */
    val allVoices: List<String> get() = (voices + customVoices).distinct()
}
