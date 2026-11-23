package com.simplechat.app.net

import com.simplechat.app.BuildConfig
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.net.anthropic.AnthropicRequest
import com.simplechat.app.net.anthropic.anthropicMessagesUrl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 对话接口门面。
 *
 * 负责请求构造与**参数条件化**（设计文档 §5.4）—— 这是协议层的硬约束，
 * 不做的话用户会以为参数生效了，其实被服务端静默忽略。
 */
class ChatApi(
    private val client: OkHttpClient,
    private val sseClient: SseClient,
    private val anthropicApi: AnthropicApi,
    private val json: Json,
) {

    /**
     * 走 Anthropic 兼容通道发起流式对话（扩展模式：搜索 / 读链接 / 引用 / 服务端标题）。
     *
     * 与 [streamChat] 并列而不是合并：两条通道的请求体、事件模型、结束信号都不同，
     * 合并只会让两套判断缠在一起（见 `AnthropicApi` 的注释）。
     */
    fun streamMessage(
        baseUrl: String,
        apiKey: String,
        request: AnthropicRequest,
        extraHeaders: Map<String, String> = emptyMap(),
        contextWindow: Int? = null,
        retryBeforeContent: Boolean = true,
    ): Flow<StreamEvent> = anthropicApi.streamMessage(
        url = anthropicMessagesUrl(baseUrl),
        apiKey = apiKey,
        request = request,
        extraHeaders = extraHeaders,
        contextWindow = contextWindow,
        retryBeforeContent = retryBeforeContent,
    )

    /**
     * 发起流式对话。
     *
     * 参数条件化规则：
     * - 模型不支持思考 → 完全不带 `thinking` / `reasoning_effort` 字段
     * - 思考开启 → **省略 `temperature`**（思考模式下它会被静默忽略）
     * - 思考关闭 → **省略 `top_p`**（非思考模式下它恒为 1.0）
     */
    fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: ModelInfo,
        messages: List<OutMessageDto>,
        thinkingEnabled: Boolean,
        reasoningEffort: String? = null,
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        retryBeforeContent: Boolean = true,
        apiStyle: ApiStyle = ApiStyle.OPENAI,
        search: Boolean = false,
        openAiSearch: Boolean = false,
        readLink: Boolean = false,
        requestTitle: Boolean = false,
        generateImageTool: Boolean = false,
        requireImageTool: Boolean = false,
        independentWeb: Boolean = false,
        responseHistory: List<JsonElement> = emptyList(),
        onResponseOutput: ((Int, kotlinx.serialization.json.JsonObject) -> Unit)? = null,
        imageToolCompleted: Boolean = false,
        webToolCompleted: Boolean = false,
        independentFetch: Boolean = true,
        independentSearch: Boolean = true,
        agentStateInstruction: String = "",
        automaticWebSearchAllowed: Boolean = true,
        onRequestMetadata: ((List<String>, String?) -> Unit)? = null,
    ): Flow<StreamEvent> {
        val flash = isOfficialDeepSeekEndpoint(baseUrl) && model.id == DEEPSEEK_FLASH
        val tools = if (flash) buildList {
            // Flash accepts client tools alongside built-in search only in thinking mode.
            if (search && (!generateImageTool || thinkingEnabled)) add(DeepSeekTool("deepseek_web_search"))
            if (readLink && !generateImageTool) add(DeepSeekTool("deepseek_read_url"))
        }.ifEmpty { null } else null
        val openAiSearchEnabled = openAiSearch && supportsOpenAiSearchTool(baseUrl, model.id, apiStyle)
        // DeepSeek native tools use `type` (not `name`); client function tools use the `name` field.
        val serverSearchEnabled = tools?.any { it.type == "deepseek_web_search" } == true || openAiSearchEnabled
        val automaticSearch = automaticWebSearchAllowed && isOfficialOpenAiEndpoint(baseUrl) && usesAutomaticOpenAiSearch(model.id, apiStyle)
        val appInstructions = buildList {
            if (independentWeb) add(webToolInstruction)
            else if (webToolCompleted) add(webAnswerInstruction)
            if (imageToolCompleted) add(imageAnswerInstruction)
            if (agentStateInstruction.isNotBlank()) add(agentStateInstruction)
            if (generateImageTool) add(imageToolInstruction)
            if (serverSearchEnabled || automaticSearch) {
                add(serverSearchInstruction)
            }
        }
        val systemPrefixSize = messages.indexOfFirst { it.role != "system" && it.role != "developer" }
            .let { if (it < 0) messages.size else it }
        val requestMessages = messages.take(systemPrefixSize) +
            appInstructions.map { OutMessageDto("system", MessageContent.text(it)) } +
            messages.drop(systemPrefixSize)
        val title = if (flash && requestTitle && messages.sumOf {
            ((it.content as? JsonPrimitive)?.content?.length ?: 8_001).toLong() + 32
        } <= 8_000) DeepSeekTitle() else null
        if (apiStyle == ApiStyle.OPENAI_RESPONSES) {
            return sseClient.streamResponses(
                url = responsesUrl(baseUrl), apiKey = apiKey,
                request = buildResponsesRequest(model, requestMessages, thinkingEnabled, reasoningEffort, maxTokens, temperature).let { request ->
                    val imageTool = imageToolDefinition.takeIf { generateImageTool }?.let {
                        DeepSeekTool("function", name = it.name, description = it.description, parameters = it.parameters)
                    }
                    val searchTool = DeepSeekTool("web_search").takeIf {
                        openAiSearchEnabled
                    }
                    request.copy(
                        input = request.input + responseHistory,
                        include = listOf("reasoning.encrypted_content").takeIf { onResponseOutput != null && model.supportsThinking },
                        tools = (tools.orEmpty() + listOfNotNull(imageTool, searchTool) + webTools(independentWeb, independentFetch, independentSearch).map {
                            DeepSeekTool("function", name = it.name, description = it.description, parameters = it.parameters)
                        }).ifEmpty { null },
                        title = title,
                        reasoning = if (isOfficialDeepSeekEndpoint(baseUrl) && !thinkingEnabled) ResponsesReasoningDto("none") else request.reasoning,
                    ).also { onRequestMetadata?.invoke(it.tools.orEmpty().map { tool -> tool.function?.name ?: tool.name ?: tool.type }, null) }
                },
                messages = requestMessages, model = model, extraHeaders = extraHeaders, retryBeforeContent = retryBeforeContent,
                onOutput = onResponseOutput,
            )
        }
        val imageTool = if (generateImageTool) DeepSeekTool("function", imageToolDefinition) else null
        val allTools = (tools.orEmpty() + listOfNotNull(imageTool) + webTools(independentWeb, independentFetch, independentSearch).map { DeepSeekTool("function", it) }).ifEmpty { null }
        val request = buildChatRequest(model, requestMessages, thinkingEnabled, reasoningEffort, maxTokens, temperature, topP, isOfficialDeepSeekEndpoint(baseUrl))
            .let {
                if (allTools == null && title == null) it else it.copy(
                    tools = allTools,
                    toolChoice = when {
                        imageTool == null -> null
                        requireImageTool && allTools?.size == 1 && isOfficialDeepSeekEndpoint(baseUrl) &&
                            it.thinking == ThinkingDto.Disabled -> "required"
                        else -> "auto"
                    },
                    title = title, temperature = null, topP = null,
                )
            }

        onRequestMetadata?.invoke(request.tools.orEmpty().map { it.function?.name ?: it.name ?: it.type } +
            listOfNotNull("web_search_options".takeIf { automaticSearch }), request.toolChoice)
        return sseClient.stream(
            url = chatCompletionsUrl(baseUrl),
            apiKey = apiKey,
            request = if (automaticSearch) {
                request.copy(webSearchOptions = buildJsonObject {})
            } else request,
            extraHeaders = extraHeaders,
            contextWindow = model.contextWindow,
            retryBeforeContent = retryBeforeContent,
            maxOutputTokens = model.maxOutputTokens,
        )
    }

    /**
     * 拉取模型列表。OpenCode Go 的 `/models` 无需鉴权（设计文档 §5.2）。
     * 失败不抛异常，由调用方决定提示方式。
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> =
        fetchModelCatalog(baseUrl, apiKey).map { it.ids }

    internal suspend fun fetchModelCatalog(
        baseUrl: String,
        apiKey: String,
        apiStyle: ApiStyle = ApiStyle.OPENAI,
    ): Result<RemoteModelCatalog> =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = modelListingRequest(baseUrl, apiKey, apiStyle)
                client.newCall(builder).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}")
                    }
                    val text = response.body?.string().orEmpty()
                    parseRemoteModelCatalog(json, text)
                }
            }
        }

    companion object {

        /**
         * 官方要求客户端明确标识自身，不要使用宽泛（如 okhttp/x.y）的 User-Agent。
         *
         * 版本号**从 BuildConfig 取**，不写死 —— 写死之后每次改版本都会忘记同步，
         * 服务端看到的客户端版本就成了假的（改包名 / 定 0.9.0-beta 那次就漏了）。
         */
        val USER_AGENT: String = "SimpleChat/${BuildConfig.VERSION_NAME} (Android)"

        /**
         * 本地 OkHttpClient。
         *
         * 流式响应不能设 readTimeout —— 模型思考时可能几十秒不吐一个字节，
         * 设了会被误判断线。只保留连接与写超时。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val requestBody = chain.request().body
                val sending = if (requestBody is StreamingRequestBody<*> && requestBody.contentLength() >= LargeRequestBytes) {
                    chain.withWriteTimeout(LargeRequestWriteTimeoutSeconds, TimeUnit.SECONDS)
                } else chain
                sending.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build(),
                )
            }
            .build()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            // 必须为 true：`stream` / `thinking.type` 这类字段一旦被当作默认值省略，
            // 请求语义就完全变了（见 ChatRequestDto.stream 的注释）。
            encodeDefaults = true
            isLenient = true
            explicitNulls = false
        }
    }
}

internal fun modelListingRequest(baseUrl: String, apiKey: String, apiStyle: ApiStyle): Request {
    val official = isOfficialDeepSeekEndpoint(baseUrl)
    val nativeAnthropic = apiStyle == ApiStyle.ANTHROPIC && !official
    val url = when {
        nativeAnthropic -> anthropicMessagesUrl(baseUrl).removeSuffix("/messages") + "/models"
        official -> modelsUrl(officialChatRoot(baseUrl) ?: baseUrl)
        else -> modelsUrl(baseUrl)
    }
    return Request.Builder().url(url).get().header("Accept", "application/json").apply {
        if (nativeAnthropic) {
            header("anthropic-version", com.simplechat.app.net.anthropic.claudeVersionFor(baseUrl) ?: "2023-06-01")
            if (apiKey.isNotBlank()) header("x-api-key", apiKey)
        } else if (apiKey.isNotBlank()) {
            header("Authorization", "Bearer $apiKey")
        }
    }.build()
}

/** Build only the reasoning fields understood by the configured OpenAI-compatible route. */
internal fun buildChatRequest(
    model: ModelInfo,
    messages: List<OutMessageDto>,
    thinkingEnabled: Boolean,
    reasoningEffort: String? = null,
    maxTokens: Int? = null,
    temperature: Double? = null,
    topP: Double? = null,
    officialDeepSeek: Boolean = false,
): ChatRequestDto {
    val wire = model.reasoningWire.takeIf { model.supportsThinking }
    val effort = when {
        wire == null -> null
        thinkingEnabled -> model.resolveReasoningEffort(reasoningEffort ?: "high")
        wire == ReasoningWire.EFFORT -> model.disabledReasoningEffort()
        else -> null
    }
    val output = model.clampOutputTokens(maxTokens)
    return ChatRequestDto(
        model = model.id,
        messages = messages,
        stream = true,
        thinking = if (wire == ReasoningWire.DEEPSEEK) {
            if (thinkingEnabled) ThinkingDto.Enabled else ThinkingDto.Disabled
        } else null,
        reasoningEffort = effort.takeIf { wire == ReasoningWire.DEEPSEEK || wire == ReasoningWire.EFFORT },
        reasoning = if (wire == ReasoningWire.REASONING_OBJECT) ReasoningDto(thinkingEnabled, effort) else null,
        enableThinking = thinkingEnabled.takeIf { wire == ReasoningWire.ENABLE_THINKING },
        maxTokens = output.takeUnless { model.completionTokenField },
        maxCompletionTokens = output.takeIf { model.completionTokenField },
        temperature = temperature.takeUnless { model.completionTokenField || (wire != null && thinkingEnabled) },
        topP = topP.takeIf { wire == ReasoningWire.DEEPSEEK && if (officialDeepSeek) adjustableTopP(model.id, thinkingEnabled) else thinkingEnabled },
    )
}

/** 构造消息 `content`。文本 → 字符串；带图 → content block 数组。 */
object MessageContent {

    fun text(text: String): JsonElement = JsonPrimitive(text)

    /**
     * 带图片的 content。
     *
     * ⚠️ 图片**只允许出现在 user 消息中**，放在 system / assistant 会返回 400（§11.3）。
     */
    fun withImages(text: String, images: List<ImageInput>): JsonElement = buildJsonArray {
        if (text.isNotBlank()) {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                },
            )
        }
        images.forEach { image ->
            add(
                buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", image.dataUrl)
                        put("detail", image.detail)
                    }
                },
            )
        }
    }
}

/**
 * 图片输入。[dataUrl] 形如 `data:image/jpeg;base64,...`。
 *
 * `detail` 的实际取值由调用方决定（见 `ui/chat/RequestContent.kt` 的 `ImageDetail`）——
 * 本项目固定用 `high`：`low` 会把图缩到 512×512，截图里的小字会糊掉，
 * 而"看截图里的文字"恰恰是最常用的场景。
 */
data class ImageInput(
    val dataUrl: String,
    val detail: String = "high",
)

internal const val imageToolInstruction = "SimpleChat provides generate_image in this conversation, regardless of the chat model's native capabilities or earlier replies about tools. For actual image requests, including follow-ups such as 'can you draw that?', resolve the subject from the conversation and call the tool with a self-contained visual prompt. Use create for a new image and edit for a change to an existing image. For questions about this chat's abilities, report this configuration without searching the web or generating an image. Do not generate for image analysis, prompt writing, quoted requests, or explicit requests not to generate. Do not claim that an image was generated until the tool confirms success."

internal const val serverSearchInstruction = "$webTargetInstruction This turn has access to the app's server-side web search tool. For current or time-sensitive questions, use the tool and base your answer on its returned results. When search tool results are present in this turn, you have searched: synthesize them and cite the supplied sources. Distinguish failed, empty or unattempted search from a disabled tool. Do not invent search results."

internal val imageToolDefinition = FunctionDefinitionDto(
    name = "generate_image",
    description = "Create, redraw, or edit an image for the current user. Use for visual scene requests and contextual changes to an existing image. Return the intended visual result as the prompt, not a chat reply. Do not use for capability questions, image analysis, or prompt writing.",
    parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("operation") {
                put("type", "string")
                put("enum", buildJsonArray { add(JsonPrimitive("create")); add(JsonPrimitive("edit")) })
                put("description", "Create a new image, or edit using an existing image as reference")
            }
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "Visual description of the requested image")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("prompt")); add(JsonPrimitive("operation")) })
        put("additionalProperties", false)
    },
)
