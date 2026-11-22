package com.simplechat.app.data

/**
 * 内置音色目录。
 *
 * 第三方 TTS 的音色在协议里只是一个 id（`Cherry` / `alloy` / `zh-CN-XiaoxiaoNeural`）。
 * 让用户去服务商文档里抄 id 再粘回来，是这个功能最难用的地方 —— 抄错一个字母就是
 * 400 `not licensed`，而这时报错看起来像"Key 没权限"。
 *
 * 所以这里把**已知且实测能出声**的音色写进 App：选音色变成点选。
 * 目录只负责"显示成什么"和"一键填充"，真正的 id 仍然原样发给服务端。
 *
 * 换 Key / 换服务商后某个音色没被授权时会报 400，那是服务端的事；
 * 界面把原因原话带出来，用户删掉那一行就行。
 */
data class VoiceEntry(
    /** 发给服务端的音色 id，必须逐字一致。 */
    val id: String,
    /** 用户看到的标题（通常就是 id 本身，便于和文档对上）。 */
    val name: String,
    /** 一句话说明：性别 / 音色气质。 */
    val desc: String,
) {
    /** 列表、朗读页统一用这个串：`Cherry · 女声 · 温柔`。 */
    val label: String get() = if (desc.isBlank()) name else "$name · $desc"
}

object VoiceCatalog {

    /** 千问 MaaS / DashScope（`qwen3-tts-flash`，实测可用）。 */
    val dashScope: List<VoiceEntry> = listOf(
        VoiceEntry("Cherry", "Cherry", "女声 · 温柔"),
        VoiceEntry("Ethan", "Ethan", "男声 · 沉稳"),
        VoiceEntry("Nofish", "Nofish", "女声 · 活泼"),
        VoiceEntry("Jennifer", "Jennifer", "女声 · 知性"),
        VoiceEntry("Dylan", "Dylan", "男声 · 少年"),
    )

    /** OpenAI 兼容的 `/v1/audio/speech`（`tts-1` / `tts-1-hd`）。 */
    val openAi: List<VoiceEntry> = listOf(
        VoiceEntry("alloy", "alloy", "中性 · 百搭"),
        VoiceEntry("echo", "echo", "男声 · 清亮"),
        VoiceEntry("fable", "fable", "中性 · 叙事"),
        VoiceEntry("onyx", "onyx", "男声 · 低沉"),
        VoiceEntry("nova", "nova", "女声 · 明快"),
        VoiceEntry("shimmer", "shimmer", "女声 · 柔和"),
    )

    /** 当前协议对应的内置清单。 */
    fun builtIn(adapter: String): List<VoiceEntry> =
        if (adapter == VoiceApiConfig.AdapterDashScope) dashScope else openAi

    /** 内置目录里的显示名；没有这个 id 时返回 null。 */
    fun builtInLabel(adapter: String, id: String): String? =
        builtIn(adapter).firstOrNull { it.id == id }?.label

    /** 协议的中文名（界面用）。 */
    fun adapterLabel(adapter: String): String = when (adapter) {
        VoiceApiConfig.AdapterDashScope -> "千问 MaaS（DashScope）"
        else -> "OpenAI 兼容（/v1/audio/speech）"
    }
}
