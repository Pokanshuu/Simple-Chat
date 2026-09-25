package com.simplechat.app.net

import androidx.annotation.StringRes
import com.simplechat.app.R
import com.simplechat.app.data.Res

/**
 * 服务商。三选一，见设计文档 §5.2。
 *
 * 统一走 OpenAI 兼容 `/chat/completions`，只做这一个适配器。
 */
enum class ProviderKind(
    @StringRes private val nameRes: Int,
    val defaultBaseUrl: String,
    val defaultModel: String,
    /** 模型列表接口是否需要鉴权。OpenCode Go 的 `/models` 实测无需鉴权。 */
    val modelListNeedsAuth: Boolean = true,
) {
    DEEPSEEK(
        nameRes = R.string.provider_name_deepseek,
        defaultBaseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-flash",
    ),
    OPENCODE_GO(
        nameRes = R.string.provider_name_opencode,
        defaultBaseUrl = "https://opencode.ai/zen/go/v1",
        defaultModel = "deepseek-v4-flash",
        modelListNeedsAuth = false,
    ),
    CUSTOM(
        nameRes = R.string.provider_name_custom,
        defaultBaseUrl = "",
        defaultModel = "",
    ),
    ;

    /** 显示名，随界面语言取（`provider_name_*`）。 */
    val displayName: String get() = Res.get(nameRes)
}

/**
 * 模型能力。驱动 UI（例如不具备识图能力时不显示输入区的 ⊕）与参数条件显示（§5.4）。
 *
 * 能力不全的模型（尤其自定义端点）由用户在设置里手动勾选覆盖。
 */
data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val supportsVision: Boolean = false,
    val supportsThinking: Boolean = false,
    /** 上下文窗口，用于占用进度条与压缩阈值（§9.1.1③）。 */
    val contextWindow: Int = DEFAULT_CONTEXT_WINDOW,
) {
    companion object {
        const val DEFAULT_CONTEXT_WINDOW = 128_000
    }
}

/** 已知模型的能力表。未知模型回落到保守默认值，可在设置里手动覆盖。 */
object ModelCatalog {

    private val deepSeek = listOf(
        ModelInfo(
            id = "deepseek-flash",
            displayName = "DeepSeek V4.1 Flash",
            supportsVision = true,
            supportsThinking = true,
            contextWindow = 1_000_000,
        ),
        ModelInfo(
            id = "deepseek-v4-pro",
            displayName = "DeepSeek V4 Pro",
            supportsVision = false,
            supportsThinking = true,
            contextWindow = 1_000_000,
        ),
    )

    /**
     * OpenCode Go。能力来自官方目录与文档，未列出的模型按保守值处理。
     * 模型列表本身运行时从 `/models` 拉取（§5.2）。
     */
    private val openCodeGo = listOf(
        ModelInfo("deepseek-v4-flash", "DeepSeek V4 Flash", supportsVision = false, supportsThinking = true, contextWindow = 1_000_000),
        ModelInfo("deepseek-flash", "DeepSeek Flash", supportsVision = false, supportsThinking = true, contextWindow = 1_000_000),
        ModelInfo("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", supportsVision = false, supportsThinking = true, contextWindow = 1_000_000),
        ModelInfo("deepseek-v4-pro", "DeepSeek V4 Pro", supportsVision = false, supportsThinking = true, contextWindow = 1_000_000),
        ModelInfo("deepseek-v4-flash-vision-exp", "DeepSeek V4 Flash Vision (exp)", supportsVision = true, supportsThinking = true, contextWindow = 1_000_000),
        ModelInfo("kimi-k3", "Kimi K3", supportsVision = true, contextWindow = 262_144),
        ModelInfo("kimi-k2.7-code", "Kimi K2.7 Code", supportsVision = true, contextWindow = 262_144),
        ModelInfo("kimi-k2.6", "Kimi K2.6", supportsVision = true, contextWindow = 262_144),
        ModelInfo("kimi-k2.5", "Kimi K2.5", supportsVision = true, contextWindow = 262_144),
        ModelInfo("glm-5.3", "GLM-5.3", contextWindow = 202_752),
        ModelInfo("glm-5.3-flash", "GLM-5.3 Flash", contextWindow = 202_752),
        ModelInfo("glm-5.2", "GLM-5.2", contextWindow = 1_000_000),
        ModelInfo("glm-5.1", "GLM-5.1", contextWindow = 202_752),
        ModelInfo("glm-5", "GLM-5", contextWindow = 202_752),
        ModelInfo("mimo-v2.5", "MiMo V2.5", supportsVision = true, contextWindow = 1_000_000),
        ModelInfo("mimo-v2.5-pro", "MiMo V2.5 Pro", supportsVision = false, contextWindow = 1_048_576),
        ModelInfo("mimo-v2-pro", "MiMo V2 Pro", supportsVision = true, contextWindow = 1_000_000),
        ModelInfo("mimo-v2-omni", "MiMo V2 Omni", supportsVision = true, contextWindow = 1_000_000),
        ModelInfo("qwen3.8-max", "Qwen3.8 Max", contextWindow = 1_000_000),
        ModelInfo("qwen3.7-max", "Qwen3.7 Max", contextWindow = 1_000_000),
        ModelInfo("qwen3.7-plus", "Qwen3.7 Plus", supportsVision = true, contextWindow = 1_000_000),
        ModelInfo("qwen3.6-plus", "Qwen3.6 Plus", supportsVision = true, contextWindow = 262_144),
        ModelInfo("qwen3.5-plus", "Qwen3.5 Plus", supportsVision = true, contextWindow = 262_144),
        ModelInfo("grok-4.6", "Grok 4.6", supportsVision = true, contextWindow = 256_000),
        ModelInfo("grok-4.5", "Grok 4.5", supportsVision = true, contextWindow = 256_000),
        ModelInfo("minimax-m3", "MiniMax M3", contextWindow = 204_800),
        ModelInfo("minimax-m2.7", "MiniMax M2.7", contextWindow = 204_800),
        ModelInfo("minimax-m2.5", "MiniMax M2.5", contextWindow = 204_800),
        ModelInfo("hy4-preview", "HY4 Preview", contextWindow = 262_144),
        ModelInfo("hy3", "HY3", contextWindow = 262_144),
        ModelInfo("hy3-preview", "HY3 Preview", contextWindow = 262_144),
        ModelInfo("longcat-2.0", "LongCat 2.0", contextWindow = 262_144),
    )

    fun knownModels(kind: ProviderKind): List<ModelInfo> = when (kind) {
        ProviderKind.DEEPSEEK -> deepSeek
        ProviderKind.OPENCODE_GO -> openCodeGo
        ProviderKind.CUSTOM -> emptyList()
    }

    fun find(kind: ProviderKind, modelId: String): ModelInfo =
        knownModels(kind).firstOrNull { it.id == modelId } ?: ModelInfo(id = modelId)

    /**
     * 把运行时拉取到的模型 id 与已知能力表合并。
     * 已知的保留能力，未知的用保守默认值补进来。
     */
    fun merge(kind: ProviderKind, remoteIds: List<String>): List<ModelInfo> {
        if (remoteIds.isEmpty()) return knownModels(kind)
        val known = knownModels(kind).associateBy { it.id }
        return remoteIds.map { id -> known[id] ?: ModelInfo(id = id) }
    }
}

/** 服务商配置。 */
data class ProviderConfig(
    val kind: ProviderKind,
    val baseUrl: String = kind.defaultBaseUrl,
    val model: ModelInfo = ModelCatalog.find(kind, kind.defaultModel),
)

/**
 * OpenCode Go 要求必填 `x-opencode-session` 请求头，否则直接 400：
 *
 * ```
 * {"error":{"type":"MissingSessionID",
 *           "message":"... Request is missing x-opencode-session and cannot be routed efficiently."}}
 * ```
 *
 * 该头用于路由与流量识别，取一个**安装级稳定标识**即可（不随请求变化）。
 * 官方另要求客户端「明确标识自身，不要用宽泛的 User-Agent」。
 */
fun ProviderKind.headersFor(sessionId: String): Map<String, String> = when (this) {
    ProviderKind.OPENCODE_GO -> mapOf("x-opencode-session" to sessionId)
    ProviderKind.DEEPSEEK, ProviderKind.CUSTOM -> emptyMap()
}

/** 拼装 `/chat/completions` 地址，兼容用户填不填 `/v1`、末尾带不带斜杠。 */
fun chatCompletionsUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
}

/** 拼装 `/models` 地址。 */
fun modelsUrl(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    return if (base.endsWith("/models")) base else "$base/models"
}
