package com.simplechat.app.ui.chat

import com.simplechat.app.data.ApiImage
import com.simplechat.app.data.Attachment
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.anthropic.AnthropicMessageParam
import com.simplechat.app.net.anthropic.AnthropicRequest
import com.simplechat.app.net.anthropic.AnthropicThinking
import com.simplechat.app.net.anthropic.AnthropicTitle
import com.simplechat.app.net.anthropic.AnthropicTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 把当前会话装配成 Anthropic 兼容通道的请求体。
 *
 * 与 OpenAI 通道的 `buildRequestMessages` 是**两套**，不是一份带分支的：
 * 两条协议的"消息"根本不是同一个东西 —— 一边是 `role + content`，
 * 另一边是 `role + 块数组`，而且只有后者能带思考签名、工具调用与网页结果。
 *
 * 纯函数，有单测（`AnthropicRequestBuilderTest`）。这一层错了只表现为
 * "模型答非所问 / 搜索不触发 / 400"，从界面上完全看不出是哪一环。
 *
 * ### 回传规则（逐条对齐官方协议）
 *
 * | 块 | 历史里回传 | 说明 |
 * |---|---|---|
 * | `text` | ✅ 拼接成一块 | citations 去掉（服务端忽略） |
 * | `thinking` | ❌ | 只有**续写**时保留本轮这一条（含签名） |
 * | `search_result` | ❌ | 历史搜索结果不回传 |
 * | `tool_use(open_page/find_in_page)` | ❌ | 边想边搜的过程不进历史 |
 * | `tool_use(read_link)` + `fetch_result` | ✅ 原样 | 服务端靠 signature 解密恢复网页正文 |
 */
internal fun buildAnthropicRequest(
    model: String,
    systemPrompt: String,
    path: List<UiMessage>,
    searchEnabled: Boolean,
    thinkingEnabled: Boolean,
    requestTitle: Boolean,
    latestReminder: String?,
    maxTokens: Int? = null,
    /** 续写：把这条 assistant 当作末尾前缀发出去，服务端据此接着写。 */
    prefix: UiMessage? = null,
): AnthropicRequest {
    val messages = mutableListOf<AnthropicMessageParam>()

    path.forEachIndexed { index, message ->
        // 提醒快照只挂在本轮提问之前：需要的是"现在几点、在哪儿"，历史时间点是噪音
        if (index == path.lastIndex && latestReminder != null) {
            messages += AnthropicMessageParam(
                role = AnthropicMessageParam.RoleReminder,
                content = JsonPrimitive(latestReminder),
            )
        }
        if (message.role == MessageRole.USER) {
            messages += userParam(message)
        } else {
            val content = assistantContent(message)
            // 一条既没正文也没工具痕迹的 assistant 发出去就是空块，服务端会 400
            if (isSendable(content)) {
                messages += AnthropicMessageParam(
                    role = AnthropicMessageParam.RoleAssistant,
                    content = JsonArray(content),
                )
            }
        }
    }

    prefix?.let { tail ->
        val content = assistantContent(tail, keepThinking = thinkingEnabled)
        if (isSendable(content)) {
            messages += AnthropicMessageParam(
                role = AnthropicMessageParam.RoleAssistant,
                content = JsonArray(content),
            )
        }
    }

    return AnthropicRequest(
        model = model,
        system = systemPrompt.trim().ifBlank { DefaultSystemPrompt },
        messages = messages,
        thinking = if (thinkingEnabled) AnthropicThinking.Enabled else AnthropicThinking.Disabled,
        tools = buildList {
            // 读链接始终开着（用户贴了 URL 就该被读），搜索才受开关控制
            add(AnthropicTool.ReadUrl)
            if (searchEnabled) add(AnthropicTool.WebSearch)
        },
        title = if (requestTitle) AnthropicTitle() else null,
        maxTokens = maxTokens?.takeIf { it > 0 },
    )
}

/**
 * `system` 在协议里必填且非空，用户又可能一个预设都没配。
 * 此时用一句助手身份说明兜底 —— 与官方 Chat Demo 发给服务端的同一句。
 */
internal const val DefaultSystemPrompt = "该助手为DeepSeek，由深度求索公司创造。"

/**
 * 一条 user 消息的 `content`。
 *
 * 三种形态，按"能简单就简单"排（与 OpenAI 通道的 `userContent` 同一条原则）：
 *
 * | 附件 | content |
 * |---|---|
 * | 无 | 纯字符串 |
 * | 只有文本文件 | 纯字符串（附件按定界块拼进正文） |
 * | 有图片 | 块数组：先图后文 |
 *
 * 图片走 `source.type = base64`（不是 OpenAI 的 `image_url` + data URL）：
 * 这是 Anthropic 协议的写法，也是本项目不引入 Files API 上传链路时唯一的选择。
 */
private fun userParam(message: UiMessage): AnthropicMessageParam {
    val text = bodyText(message)
    /*
     * 只发**有内容**的图片。
     *
     * 落盘附件的 `data` 默认是空的，要靠 `prepareRequestAttachments` 读回；
     * 万一某条路径漏了这一步，这里必须把空图挡掉 —— 空 base64 发上去，
     * 服务端只会回 `unsupported image`，而且整个会话每轮都会失败。
     * 少一张图 < 整个会话发不出去。
     */
    val images = message.attachments.filter { it.isImage && it.data.isNotBlank() }
    if (images.isEmpty()) {
        return AnthropicMessageParam(
            role = AnthropicMessageParam.RoleUser,
            content = JsonPrimitive(text),
        )
    }
    return AnthropicMessageParam(
        role = AnthropicMessageParam.RoleUser,
        content = buildJsonArray {
            // 先图后文：块数组是有序的，图片放在问题前面才符合"这是我看的资料"的语序
            images.forEach { image ->
                addJsonObject {
                    put("type", "image")
                    put(
                        "source",
                        buildJsonObject {
                            put("type", "base64")
                            // 协议 enum 只认四个值（见 ApiImage.legalMediaType）
                            put("media_type", ApiImage.legalMediaType(image.mime))
                            put("data", image.data)
                        },
                    )
                }
            }
            if (text.isNotBlank()) {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
        },
    )
}

/** 正文 + 文本附件拼成的一段话（附件用定界块，模型才分得清哪段是资料）。 */
private fun bodyText(message: UiMessage): String {
    val textFiles = message.attachments.filter { it.kind == Attachment.Kind.TEXT }
    if (textFiles.isEmpty()) return message.content
    return buildString {
        append(message.content)
        textFiles.forEach { file ->
            if (isNotEmpty()) appendLine()
            appendLine()
            append(file.toPromptBlock())
        }
    }.trim()
}

/** 块数组里至少得有一块真内容，否则不发出这条消息。 */
private fun isSendable(blocks: List<JsonElement>): Boolean = blocks.isNotEmpty()

/**
 * 一条 assistant 消息的块数组。
 *
 * [keepThinking] 只对**续写前缀**为 true：官方协议要求历史轮次的思考不回传
 * （回了既浪费 token，也可能让服务端把旧思考当成新前缀），而本轮续写必须带上
 * 思考与其签名，否则服务端没有可续的上下文。
 *
 * 文本块在遇到"要发出去的非文本块"时才切分 —— 被丢弃的搜索块不应该把
 * 两段连续正文切成两块。
 */
private fun assistantContent(message: UiMessage, keepThinking: Boolean = false): List<JsonElement> {
    // 没有时间线（OpenAI 通道写的、或升级前的老消息）→ 就一段正文
    if (message.timeline.isEmpty()) {
        return listOfNotNull(textBlock(message.content))
    }

    val out = mutableListOf<JsonElement>()
    // 缺少有效签名的读链接：调用与结果都不回传，否则服务端解不开
    val unsigned = message.timeline
        .filterIsInstance<ReplyBlock.FetchResult>()
        .filter { it.kind == ReadLinkKind && !it.failed && it.signature.isNullOrBlank() }
        .map { it.toolUseId }
        .toSet()

    val buffer = StringBuilder()
    fun flush() {
        textBlock(buffer.toString())?.let { out += it }
        buffer.clear()
    }

    message.timeline.forEach { block ->
        when (block) {
            is ReplyBlock.Text -> buffer.append(block.text)

            is ReplyBlock.Thinking -> {
                if (!keepThinking) return@forEach
                // 空白思考不改变续写位置，也不该把两段正文隔开
                if (block.thinking.isBlank()) return@forEach
                flush()
                out += buildJsonObject {
                    put("type", "thinking")
                    put("thinking", block.thinking)
                    put("signature", block.signature.orEmpty())
                }
            }

            is ReplyBlock.ToolUse -> {
                if (block.kind != ReadLinkKind || block.id in unsigned) return@forEach
                flush()
                out += buildJsonObject {
                    put("type", "server_tool_use")
                    put("id", block.id)
                    put("name", block.name)
                    put("kind", block.kind)
                    put(
                        "input",
                        buildJsonObject {
                            put("action", block.action)
                            put("url", block.url)
                        },
                    )
                }
            }

            is ReplyBlock.FetchResult -> {
                if (block.kind != ReadLinkKind || block.toolUseId in unsigned) return@forEach
                flush()
                out += buildJsonObject {
                    put("type", "web_fetch_tool_result")
                    put("tool_use_id", block.toolUseId)
                    put("kind", block.kind)
                    block.signature?.let { put("signature", it) }
                    put("content", fetchContent(block))
                }
            }

            // 搜索结果不回传
            is ReplyBlock.SearchResult -> Unit
        }
    }
    flush()
    return out
}

private fun fetchContent(block: ReplyBlock.FetchResult): JsonElement = buildJsonObject {
    if (block.failed) {
        put("type", "web_fetch_tool_result_error")
        put("error_code", "unavailable")
        return@buildJsonObject
    }
    put("type", "web_fetch_result")
    put("url", block.url)
    put("title", block.title)
    put("published_at", block.publishedAt)
    put(
        "content",
        buildJsonObject {
            put("type", "document")
            put("title", block.title)
            // 页面正文是加密下发的，客户端只回传签名；这里只能是空串
            put(
                "source",
                buildJsonObject {
                    put("type", "text")
                    put("media_type", "text/plain")
                    put("data", "")
                },
            )
        },
    )
}

private fun textBlock(text: String): JsonElement? {
    if (text.isEmpty()) return null
    return buildJsonObject {
        put("type", "text")
        put("text", text)
    }
}

/** 读链接的 kind 字面值。集中一处，免得散落各处的字符串拼错还查不出来。 */
internal const val ReadLinkKind = "read_link"
