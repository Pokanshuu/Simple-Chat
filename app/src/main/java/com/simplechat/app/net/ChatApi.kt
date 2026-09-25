package com.simplechat.app.net

import com.simplechat.app.BuildConfig
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
    private val json: Json,
) {

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
    ): Flow<StreamEvent> {
        val thinkingActive = model.supportsThinking && thinkingEnabled

        val request = ChatRequestDto(
            model = model.id,
            messages = messages,
            stream = true,
            thinking = when {
                !model.supportsThinking -> null
                thinkingEnabled -> ThinkingDto.Enabled
                else -> ThinkingDto.Disabled
            },
            reasoningEffort = if (thinkingActive) reasoningEffort else null,
            maxTokens = maxTokens,
            temperature = if (thinkingActive) null else temperature,
            topP = if (thinkingActive) topP else null,
        )

        return sseClient.stream(
            url = chatCompletionsUrl(baseUrl),
            apiKey = apiKey,
            request = request,
            extraHeaders = extraHeaders,
        )
    }

    /**
     * 拉取模型列表。OpenCode Go 的 `/models` 无需鉴权（设计文档 §5.2）。
     * 失败不抛异常，由调用方决定提示方式。
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val builder = Request.Builder().url(modelsUrl(baseUrl)).get()
                if (apiKey.isNotBlank()) {
                    builder.header("Authorization", "Bearer $apiKey")
                }
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code}")
                    }
                    val text = response.body?.string().orEmpty()
                    json.decodeFromString(ModelListDto.serializer(), text)
                        .data
                        .map { it.id }
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
                chain.proceed(
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
