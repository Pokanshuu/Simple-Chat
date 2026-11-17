package com.simplechat.app.ui.chat

import com.simplechat.app.data.ApiImage
import com.simplechat.app.data.Attachment
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.anthropic.AnthropicMessageParam
import com.simplechat.app.net.anthropic.AnthropicRequestDefaults
import com.simplechat.app.net.anthropic.AnthropicSafetyCheck
import com.simplechat.app.net.anthropic.AnthropicRequest
import com.simplechat.app.net.anthropic.AnthropicThinking
import com.simplechat.app.net.anthropic.AnthropicTitle
import com.simplechat.app.net.anthropic.AnthropicTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
    /**
     * 目标是不是 **DeepSeek 官方**的扩展协议。
     *
     * 同一条协议两边要求不同：`safety_check` / `title` / `deepseek_*` 工具 /
     * `latest_reminder` 角色 / `thinking.type=disabled` 只有 DeepSeek 收；
     * 原版 Anthropic（含 Claude 兼容网关）遇到这些是 **400**，而它又强制要求
     * `max_tokens` 与（开启思考时）`budget_tokens`。详见 `AnthropicRequest` 的表格。
     *
     * 由装配层按「服务商 + Base URL」判定（见 `isOfficialDeepSeekEndpoint`）。
     */
    deepSeekExtensions: Boolean = true,
    /** 采样参数。原版 Anthropic 在开启思考时不允许设置温度，装配层会置空。 */
    temperature: Double? = null,
    topP: Double? = null,
): AnthropicRequest {
    val messages = mutableListOf<AnthropicMessageParam>()

    path.forEach { message ->
        if (message.role == MessageRole.USER) {
            messages += userParam(message)
        } else {
            val content = assistantContent(
                message,
                keepThinking = deepSeekExtensions && thinkingEnabled,
                deepSeekBlocks = deepSeekExtensions,
            )
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
        val content = assistantContent(
            tail,
            keepThinking = deepSeekExtensions && thinkingEnabled,
            deepSeekBlocks = deepSeekExtensions,
        )
        if (isSendable(content)) {
            messages += AnthropicMessageParam(
                role = AnthropicMessageParam.RoleAssistant,
                content = JsonArray(content),
            )
        }
    }

    /*
     * ★ **提醒必须紧挨着一条 `user`，而且要在它前面。**
     *
     * 服务端对 `latest_reminder` 有一条硬校验（真机 400 原文）：
     * `messages.N: a 'latest_reminder' message must be immediately followed by a 'user' message`。
     *
     * 先前是"插在路径最后一条之前" —— 只要最后一条是 assistant 就踩线：
     * 「编辑 AI 回复 → 保存并发送」正是这种形状（新回复是流式的、不在路径里，
     * 路径以被编辑的那条 assistant 结尾），于是这条路径上必 400。
     *
     * 也不能简单地"插到最末"：末尾是 assistant 时，提醒后面还是没有 user。
     * 唯一对所有形状都成立的位置是：**最后一条 user 前面**。
     * 一条 user 都没有（理论上到不了这里，前面会抛 `error_no_sendable_messages`）
     * 就不发提醒 —— 少一条时间信息远好过整轮 400。
     */
    if (deepSeekExtensions && latestReminder != null) {
        val anchor = messages.indexOfLast { it.role == AnthropicMessageParam.RoleUser }
        if (anchor >= 0) {
            messages.add(
                anchor,
                AnthropicMessageParam(
                    role = AnthropicMessageParam.RoleReminder,
                    content = JsonPrimitive(latestReminder),
                ),
            )
        }
    }

    /*
     * 原版 Anthropic 的硬约束（DeepSeek 扩展不适用）：
     * - `max_tokens` 必填；
     * - 开启思考时 `thinking.budget_tokens` 必填（≥1024）且**必须小于** `max_tokens`；
     * - 开启思考时 `temperature` 只能为 1（干脆不发）。
     */
    var effectiveMaxTokens = if (deepSeekExtensions) {
        maxTokens?.takeIf { it > 0 }
    } else {
        maxTokens?.takeIf { it > 0 } ?: AnthropicRequestDefaults.MaxTokens
    }
    val vanillaThinking = !deepSeekExtensions && thinkingEnabled
    if (vanillaThinking) {
        val budget = AnthropicRequestDefaults.MaxThinkingBudgetTokens
        // 给正文留出空间：max_tokens 至少要比预算多 1024
        effectiveMaxTokens = maxOf(
            effectiveMaxTokens ?: 0,
            budget + AnthropicRequestDefaults.MinThinkingBudgetTokens,
        )
    }

    /*
     * ★ **历史对话的跨端点兼容**（换服务商 / 换端口后继续聊）。
     *
     * 同一条历史可能先在 DeepSeek 官方聊出来，之后用户换到 Claude 兼容网关继续。
     * 原版 Anthropic 对消息序列有两条硬性要求，不满足直接 400：
     *
     * 1. 第一条必须是 `user`（导入的历史、删过消息的历史都可能以 assistant 开头）；
     * 2. `user` / `assistant` 必须**交替**（`roles must alternate between …`）。
     *    本地把两条用户消息叠在一起（删掉中间的回复）是很常见的，DeepSeek 官方
     *    端点宽松、网关就未必。
     *
     * 所以发之前按严格规则整理一遍：同角色合并（正文用空行连接）、丢掉开头的
     * assistant、丢掉空消息。**只改发给服务端的这一份，本地消息一条不动。**
     */
    val sanitizedMessages = sanitizeTurns(messages)

    return AnthropicRequest(
        model = model,
        system = if (deepSeekExtensions) {
            systemPrompt.trim().ifBlank { DefaultSystemPrompt }
        } else {
            systemPrompt.trim()
        },
        messages = sanitizedMessages,
        thinking = when {
            deepSeekExtensions && thinkingEnabled -> AnthropicThinking.Enabled
            deepSeekExtensions -> AnthropicThinking.Disabled
            vanillaThinking -> AnthropicThinking.enabledWithBudget(
                AnthropicRequestDefaults.MaxThinkingBudgetTokens,
            )
            // 原版：关闭思考 = **省略这个字段**（不接受 type=disabled）
            else -> null
        },
        safetyCheck = if (deepSeekExtensions) AnthropicSafetyCheck() else null,
        tools = if (deepSeekExtensions) {
            buildList {
                // 读链接始终开着（用户贴了 URL 就该被读），搜索才受开关控制
                add(AnthropicTool.ReadUrl)
                if (searchEnabled) add(AnthropicTool.WebSearch)
            }
        } else {
            emptyList()
        },
        title = if (deepSeekExtensions && requestTitle) AnthropicTitle() else null,
        maxTokens = effectiveMaxTokens,
        // 原版 Anthropic 不允许同时改 temperature 与 top_p；这里只发 temperature
        temperature = if (!deepSeekExtensions && !thinkingEnabled) temperature ?: 1.0 else null,
        topP = if (deepSeekExtensions) topP else null,
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
private fun assistantContent(
    message: UiMessage,
    keepThinking: Boolean = false,
    /**
     * 是否允许回传 DeepSeek 扩展块（思考签名 / server_tool_use / web_fetch_tool_result）。
     *
     * 原版 Anthropic 不认识这些块（签名也不是它签的），回传只会 400；
     * 对原版目标一律退化成"一段正文"。
     */
    deepSeekBlocks: Boolean = true,
): List<JsonElement> {
    // 没有时间线（OpenAI 通道写的、或升级前的老消息）→ 就一段正文
    if (message.timeline.isEmpty()) {
        return listOfNotNull(textBlock(message.content))
    }
    if (!deepSeekBlocks) {
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

// ══════════════════════════════════════════════════════════
//  消息序列整理（跨端点兼容）
// ══════════════════════════════════════════════════════════

/**
 * 把消息序列整理成原版 Anthropic 接受的形状：
 *
 * - 空消息丢掉（空白正文 / 空块数组都会 400）；
 * - **同角色相邻两条合并**（正文用空行连接，块数组则拼起来）；
 * - 开头的 `assistant` 丢掉（第一条必须是 user）；
 * - `latest_reminder` 是扩展角色，原样保留 —— 但**只在它后面紧跟 user 时**保留：
 *   服务端会硬校验这一条（`a 'latest_reminder' message must be immediately followed
 *   by a 'user' message`），合并 / 丢空消息之后落单的提醒宁可丢掉，也不能发出去换 400。
 *
 * 纯函数，有单测。只影响请求，不碰本地数据。
 */
internal fun sanitizeTurns(messages: List<AnthropicMessageParam>): List<AnthropicMessageParam> {
    val out = ArrayList<AnthropicMessageParam>(messages.size)
    for (message in messages) {
        if (message.role == AnthropicMessageParam.RoleReminder) {
            out += message
            continue
        }
        if (isBlankContent(message.content)) continue
        val last = out.lastOrNull()
        if (last != null && last.role == message.role && last.role != AnthropicMessageParam.RoleReminder) {
            out[out.lastIndex] = AnthropicMessageParam(
                role = message.role,
                content = mergeContent(last.content, message.content),
            )
        } else {
            out += message
        }
    }
    /*
     * 提醒角色还有一条硬约束：**后面必须紧跟一条 user**。
     * 上面丢空消息之后，提醒可能落到末尾、或落到一条 assistant 前面 —— 都是 400。
     */
    val kept = out.filterIndexed { index, message ->
        message.role != AnthropicMessageParam.RoleReminder ||
            out.getOrNull(index + 1)?.role == AnthropicMessageParam.RoleUser
    }
    // 第一条必须是 user：导入数据 / 删过消息的历史可能以 assistant 开头
    return kept.dropWhile { it.role == AnthropicMessageParam.RoleAssistant }
}

/** 这条消息有没有可发的内容（空字符串 / 空白文本块 / 空数组都算没有）。 */
private fun isBlankContent(content: JsonElement): Boolean = when (content) {
    is JsonArray -> content.isEmpty() || content.all { block ->
        val obj = block as? JsonObject ?: return@all false
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
        type == "text" && (obj["text"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
    }

    is JsonPrimitive -> !content.isString || content.content.isBlank()
    else -> true
}

/** 合并两条同角色消息的 content。 */
private fun mergeContent(first: JsonElement, second: JsonElement): JsonElement {
    fun JsonElement.asBlocks(): JsonArray = when (this) {
        is JsonArray -> this
        else -> buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", (this@asBlocks as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
        }
    }

    if (first is JsonPrimitive && first.isString && second is JsonPrimitive && second.isString) {
        // 两条纯文本消息：空行连接，模型能看出这是两次输入
        val text = listOf(first.content, second.content)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return JsonPrimitive(text)
    }
    return JsonArray(first.asBlocks() + second.asBlocks())
}



