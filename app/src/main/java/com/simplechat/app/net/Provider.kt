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

    /**
     * 全部内置能力表按 id 索引（[deepSeek] 在前，同一 id 以先出现的为准）。
     *
     * 第三方端点（自建网关 / 中转站）常常原样代理官方模型，id 和官方**一模一样**：
     * `deepseek-flash`、`glm-5.2`、`qwen3.8-max`… 这时能力表当然该照样生效。
     * 早先只查「当前服务商」那张表，自定义服务商那张是空的 —— 于是一个明明是
     * 1M 上下文、带识图与思考的 `deepseek-flash`，被当成 128K 的未知模型：
     * 上下文占用条按 128K 算（显示 80% 实际才 10%）、压缩阈值提前触发、
     * 图片被当成"模型不支持"直接摘掉。
     *
     * ⚠️ 不能直接 `associateBy`：同一个 id 两张表都收（`deepseek-flash` 在
     * DeepSeek 官方表里带识图、在 OpenCode Go 表里不带），而 `associateBy`
     * 是**后写覆盖前写**。这里显式「先到先得」，语义才和"官方表优先"一致。
     */
    private val allKnown: Map<String, ModelInfo> by lazy {
        buildMap {
            deepSeek.forEach { putIfAbsent(it.id, it) }
            openCodeGo.forEach { putIfAbsent(it.id, it) }
        }
    }

    /**
     * 按 id 找能力：**先看当前服务商的那张表，再看全局表**，都没有就用保守默认值。
     *
     * 顺序不能反：同一个 id 在不同表里的能力可以不同（`deepseek-flash` 在
     * OpenCode Go 那张里明确标了不支持识图 —— 那是上游实测的路由结果），
     * 当前服务商的记录更可信。
     *
     * 全局表都没有的 id 一律回落到 [ModelInfo] 默认值（128K / 不支持识图与思考）。
     * **刻意不按 id 的字面去猜**：把上下文窗口猜大，压缩就不会及时触发，最后是
     * 服务端 400（`maximum context length`）；猜小只是早点压缩。识图/思考同理 ——
     * 猜错会直接发出模型不接受的参数。这三样都必须靠"确知"或用户手动填。
     */
    fun find(kind: ProviderKind, modelId: String): ModelInfo =
        knownModels(kind).firstOrNull { it.id == modelId }
            ?: allKnown[modelId]
            ?: ModelInfo(id = modelId)

    /**
     * 把运行时拉取到的模型 id 与已知能力表合并。
     * 已知的保留能力（含别的服务商表里认得的），未知的用保守默认值补进来。
     */
    fun merge(kind: ProviderKind, remoteIds: List<String>): List<ModelInfo> {
        if (remoteIds.isEmpty()) return knownModels(kind)
        return remoteIds.map { find(kind, it) }
    }

    /**
     * 设置页「模型」选择器要列出的模型。
     *
     * 与 [merge] 的差别是**不丢任何一边**：
     *
     * - 远端拉到的 id 排在最前 —— 它们是这个端点**真实可用**的模型，用户换端点
     *   之后找的就是这些；顺序按服务端给的来（中转站常把主力模型放前面）。
     * - 已知能力表里没被远端覆盖的条目补在后面。有些网关的 `/models` 只回一部分、
     *   或者干脆不实现这个端点（返回 404），不能让用户因此一个都选不到。
     *
     * `CUSTOM` 没有内置目录，于是「远端列表为空」时这里就是空 —— 界面必须给出
     * 空态提示和一个手动填写的入口，而不是弹一个只有标题的白框。
     */
    fun pickerModels(kind: ProviderKind, remoteIds: List<String>): List<ModelInfo> {
        val ids = remoteIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (ids.isEmpty()) return knownModels(kind)
        val merged = merge(kind, ids)
        val seen = merged.mapTo(HashSet()) { it.id }
        return merged + knownModels(kind).filter { it.id !in seen }
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

/**
 * 通信协议通道（设计文档 §5.1，v1.9 起由单通道改为双通道）。
 *
 * | 通道 | 端点 | 能拿到什么 |
 * |---|---|---|
 * | [OPENAI] | `/chat/completions` | 正文、`reasoning_content`、`reasoning_effort` 三档思考强度 |
 * | [ANTHROPIC] | `/anthropic/v1/messages` | 联网搜索、链接阅读、正文引用、服务端标题、时间地点提醒 |
 *
 * 官方 Chat Demo 的全部扩展能力只在后者上提供，而前者覆盖面更广
 * （OpenCode Go / 自建端点）。两者**并存**，由用户与端点共同决定走哪条。
 */
enum class ApiStyle {
    OPENAI,
    ANTHROPIC;

    companion object {
        fun fromId(id: String?): ApiStyle? = when (id) {
            "OPENAI" -> OPENAI
            "ANTHROPIC" -> ANTHROPIC
            // "AUTO" 与未知值都交给下面的按端点解析
            else -> null
        }

        /**
         * 实际走哪条通道。
         *
         * `AUTO` 的判据是**端点**而不是"当前模型"：
         * - `provider = DEEPSEEK` 且 Base URL 仍指向官方端点 → 扩展通道；
         * - 其余（OpenCode Go、自建、以及把 DeepSeek 的 Base URL 改成自家网关的）→ OpenAI 兼容。
         *
         * 为什么还要看 Base URL：`provider = DEEPSEEK` 只是个"预设"，
         * 用户可以把它改成任何地址。那种情况下扩展端点不存在，猜过去就是**每一次请求都 404**，
         * 而"没拿到搜索能力"只是少个功能 —— 两害相权，默认走覆盖面最广的那条。
         * 用户确实需要扩展能力时，在设置里把通道显式设成 Anthropic 即可。
         */
        fun resolve(kind: ProviderKind, configuredId: String?, baseUrl: String): ApiStyle =
            fromId(configuredId) ?: run {
                val official = baseUrl.trim().trimEnd('/').contains("api.deepseek.com")
                if (kind == ProviderKind.DEEPSEEK && official) ANTHROPIC else OPENAI
            }
    }
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
/**
 * Base URL 是否指向 **DeepSeek 官方域**。
 *
 * 官方扩展能力（Anthropic 扩展字段、服务端标题、`/v1/tools/tts` 语音）只在
 * `api.deepseek.com` 上提供；用户把 Base URL 改成自建网关后，这些字段/端点
 * 发出去只会 400 或 404。所有"要不要发官方专有东西"的判断都走这一个函数。
 */
fun isOfficialDeepSeekEndpoint(baseUrl: String): Boolean =
    baseUrl.trim().trimEnd('/').contains("api.deepseek.com")
