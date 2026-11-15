package com.simplechat.app.data.import

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.BackupFile
import com.simplechat.app.data.BackupMessage
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * DeepSeek 官方导出的聊天记录（`deepseek_data-日期.zip`）。
 *
 * ### 格式来源
 *
 * 官方开源前端 `deepseek-ai/chat-demo`（MIT）里的 `src/services/chat/history/`，
 * 有 README + `format.ts` 逐字段定义，**不是逆出来的**。归档结构：
 *
 * ```text
 * manifest.json                                        { "export_format_version": 3 }
 * chat_sessions/{session_id}.json                      会话（含消息树）
 * chat_session_index.json                              会话索引
 * chat_sessions/{session_id}_files/{file_id}_{name}    图片原件
 * ```
 *
 * ### 与 Chatbox 那套的差别
 *
 * Chatbox 是"把外面一个陌生模型猜着翻译过来"；这边是**有规格可依**：
 * 字段名、类型、状态取值、时间格式全都是文档写死的，所以这里不做"猜"，
 * 也不做"宽松兼容"——版本对不上就直接报错，比安静地导入一堆空会话好。
 *
 * ### 刻意不导的内容
 *
 * - `TIP` / `CONTENT_RISK` / `PARTIAL_FILE_READ` / `TOOL_IMAGE_SEARCH`：纯展示提示，无信息量；
 * - `TOOL_SEARCH` / `TOOL_OPEN` / `TOOL_FIND` / `READ_LINK`：搜索与读链过程。
 *   它们进的是我们的 `timeline`，映射规则比正文复杂得多（状态、分组、签名），
 *   这一步先不做 —— 正文、思考、附件、状态、时间先落地。
 * - 非图片附件：归档里只有元数据，没有字节，导进来会变成点不开的附件。
 */
internal object DeepSeekHistory {

    /** 认得的是第 3 版；别的版本不猜。 */
    private const val FORMAT_VERSION = 3

    private const val MANIFEST = "manifest.json"
    private const val INDEX = "chat_session_index.json"
    private const val SESSIONS = "chat_sessions/"

    /** 会话 id 前缀：避免与本地 / Chatbox 导入的 id 撞车。 */
    private const val ID_PREFIX = "deepseek-"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 认出来就翻译成我们的备份模型；`manifest.json` 里没有
     * `export_format_version` 说明不是这份格式，返回 null（交给别的分支）。
     */
    fun parse(zip: ZipFile, naming: ExternalBackup.ImportNaming): BackupFile? {
        val manifest = readJson(zip, MANIFEST) ?: return null
        val version = manifest["export_format_version"]?.jsonPrimitive?.intOrNull ?: return null
        if (version != FORMAT_VERSION) {
            throw AppTextException(R.string.err_deepseek_version, version, FORMAT_VERSION)
        }

        val indexOrder = readJson(zip, INDEX)
            ?.let { it.arrOrEmpty("sessions").mapNotNull { entry -> entry.str("id") } }
            .orEmpty()

        val sessionEntries = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(SESSIONS) && it.name.endsWith(".json") }
            // 图片目录 `chat_sessions/{id}_files/` 里的东西不在这里（它们不叫 .json）
            .filter { !it.name.removePrefix(SESSIONS).contains('/') }
            .toList()

        val sessions = sessionEntries.mapNotNull { entry ->
            val raw = runCatching { zip.getInputStream(entry).readBytes().decodeToString() }
                .getOrNull() ?: return@mapNotNull null
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: return@mapNotNull null
            // 先把这一条会话用到的图片读出来，再解析 —— 图片按 `export_path` 直接定位条目
            val images = imagePathsOf(obj).mapNotNull { path ->
                readImage(zip, path, naming)?.let { path to it }
            }.toMap()
            mapSession(obj, naming) { images[it] }
        }

        if (sessions.isEmpty()) throw AppTextException(R.string.err_no_sessions)

        // 索引里的顺序就是用户在 DeepSeek 里看到的顺序；没有索引就按时间倒序
        val ordered = if (indexOrder.isEmpty()) {
            sessions.sortedByDescending { it.updatedAt }
        } else {
            val rank = indexOrder.withIndex().associate { (i, id) -> "$ID_PREFIX$id" to i }
            sessions.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        }

        return BackupFile(exportedAt = System.currentTimeMillis(), conversations = ordered)
    }

    /**
     * 一条会话 → [BackupConversation]。纯函数（图片由 [imageOf] 提供），有单测。
     *
     * @param imageOf 给出 ZIP 条目名（`export_path`）取图片附件；拿不到返回 null，
     *   该图片会记进 `droppedImages`。
     */
    internal fun mapSession(
        session: JsonObject,
        naming: ExternalBackup.ImportNaming,
        imageOf: (String) -> Attachment?,
    ): BackupConversation? {
        val sessionId = session.str("id") ?: return null
        val messages = session.arrOrEmpty("messages")
        if (messages.isEmpty()) return null

        val branch = linearize(messages, session.long("current_message_id"))
        if (branch.isEmpty()) return null

        val title = session.str("title")?.takeIf { it.isNotBlank() }
            ?: branch.firstOrNull { it.str("role") == "USER" }
                ?.textOf("REQUEST")
                ?.take(FallbackTitleChars)
                ?.takeIf { it.isNotBlank() }
            ?: naming.conversationFallbackTitle()

        val insertedAt = parseIso(session.str("inserted_at")) ?: System.currentTimeMillis()
        val updatedAt = parseIso(session.str("updated_at")) ?: insertedAt

        val mapped = branch.map { message ->
            val number = message.long("id") ?: 0L
            val role = if (message.str("role") == "USER") "user" else "assistant"
            val content = if (role == "user") {
                message.textOf("REQUEST")
            } else {
                // 模板正文（超时 / 内容过滤那类）也是正文：我们只透传，不做取舍
                message.textOf("RESPONSE") + message.textOf("TEMPLATE_RESPONSE")
            }
            val think = message.fragmentsOf("THINK")
            val elapsedSecs = think.sumOf { it.longOrZero("elapsed_secs") }
            val images = message.fragmentsOf("FILE").flatMap { file ->
                file.arrOrEmpty("files").mapNotNull { entry ->
                    if (entry.str("is_image") != "true") return@mapNotNull null
                    val path = entry.str("export_path") ?: return@mapNotNull null
                    imageOf(path)
                }
            }

            BackupMessage(
                id = "$ID_PREFIX$sessionId-$number",
                parentId = message.long("parent_id")
                    ?.let { "$ID_PREFIX$sessionId-$it" }
                    ?: "",
                role = role,
                content = content,
                attachments = images,
                reasoning = think.joinToString("\n") { it.str("content").orEmpty() }.takeIf { it.isNotBlank() },
                status = statusOf(message.str("status")),
                model = null,
                thinkingMs = elapsedSecs.takeIf { it > 0L }?.times(1000L),
                // 导出的历史不参与续写：没有服务端需要的签名与工具块
                stopReason = null,
                createdAt = parseIso(message.str("inserted_at")) ?: insertedAt,
            )
        }

        return BackupConversation(
            id = "$ID_PREFIX$sessionId",
            title = title,
            mode = "CREATIVE",
            provider = "DEEPSEEK",
            model = "",
            pinned = session.str("pinned") == "true",
            createdAt = insertedAt,
            updatedAt = updatedAt,
            messages = mapped,
        )
    }

    /**
     * 把消息树线性化成"当前这一条分支"。
     *
     * 从 `current_message_id` 沿 `parent_id` 往上走，再反转 —— 用户当时看到的就是这条链。
     * 兄弟版本（重新生成留下的分支）**只保留当前选中的那一支**：我们的备份模型能表达
     * 变体，但导入时再造一套分支只会让"这条对话怎么长这样"变得难解释。
     *
     * `current_message_id` 缺失或不认识时退回"id 最大的那条"（导出工具都会写它，
     * 这里只是不让数据异常导致整条会话丢失）。
     */
    internal fun linearize(messages: List<JsonObject>, currentId: Long?): List<JsonObject> {
        val byId = HashMap<Long, JsonObject>()
        for (message in messages) {
            message.long("id")?.let { byId[it] = message }
        }
        if (byId.isEmpty()) return emptyList()

        var cursor = currentId?.takeIf { byId.containsKey(it) } ?: byId.keys.maxOrNull()
        val chain = mutableListOf<JsonObject>()
        val guard = HashSet<Long>()
        while (cursor != null) {
            val message = byId[cursor] ?: break
            // 环保护：坏数据不该把导入卡死
            if (!guard.add(cursor)) break
            chain += message
            cursor = message.long("parent_id")
        }
        chain.reverse()
        return chain
    }

    /** 状态映射，规则来自官方 README（`FINISHED` / `INCOMPLETE` / `FAILED` …）。 */
    private fun statusOf(status: String?): String = when (status) {
        "FINISHED" -> "DONE"
        "INCOMPLETE", "WIP" -> "STOPPED"
        "FAILED", "CONTEXT_LENGTH_EXCEEDED", "TIMEOUT" -> "ERROR"
        // 内容过滤在官方那边是"拒绝"：这里是**正常收尾**，正文照旧显示，
        // 并且按我们的规矩不做任何裁量（见 ChatViewModel 收尾处的说明）
        "CONTENT_FILTER" -> "DONE"
        else -> "DONE"
    }

    /** `2026-11-11T02:33:23.270Z` → 毫秒。解析不了返回 null（由调用方兜底）。 */
    private fun parseIso(text: String?): Long? =
        text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** 一条会话里所有"有原件"的图片条目名。 */
    private fun imagePathsOf(session: JsonObject): Set<String> {
        val paths = mutableSetOf<String>()
        for (message in session.arrOrEmpty("messages")) {
            for (file in message.fragmentsOf("FILE")) {
                for (entry in file.arrOrEmpty("files")) {
                    if (entry.str("is_image") != "true") continue
                    entry.str("export_path")?.let { paths += it }
                }
            }
        }
        return paths
    }

    /**
     * 读一张图片并转成我们的附件。
     *
     * 宽高从字节里现算（[imageSizeOf]），不信任导出的元数据：归档里 `width` / `height`
     * 允许为 null，而且同一个文件在不同版本里写没写也不一定。
     * 认不出格式时仍按图片留下（mime 退回 `image/jpeg`），总比丢一张图好。
     */
    private fun readImage(
        zip: ZipFile,
        path: String,
        naming: ExternalBackup.ImportNaming,
    ): Attachment? {
        val entry = zip.getEntry(path) ?: return null
        val bytes = runCatching { zip.getInputStream(entry).readBytes() }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        val size = imageSizeOf(bytes)
        return Attachment(
            kind = Attachment.Kind.IMAGE,
            name = size?.let { naming.imageName(it.first, it.second) } ?: naming.imageFallbackName(),
            mime = when {
                bytes.size >= 8 && bytes[0] == 0x89.toByte() -> "image/png"
                bytes.size >= 3 && bytes[0] == 0xFF.toByte() -> "image/jpeg"
                else -> "image/jpeg"
            },
            data = Base64.getEncoder().encodeToString(bytes),
            width = size?.first ?: 0,
            height = size?.second ?: 0,
        )
    }

    private fun readJson(zip: ZipFile, name: String): JsonObject? {
        val entry = zip.getEntry(name) ?: return null
        val text = runCatching { zip.getInputStream(entry).readBytes().decodeToString() }.getOrNull()
            ?: return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    /** 一条消息里某类片段的正文拼接。 */
    private fun JsonObject.fragmentsOf(type: String): List<JsonObject> =
        arrOrEmpty("fragments").filter { it.str("type") == type }

    private fun JsonObject.textOf(type: String): String =
        fragmentsOf(type).joinToString("") { it.str("content").orEmpty() }

    private fun JsonObject.arrOrEmpty(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

    private fun JsonObject.longOrZero(key: String): Long = long(key) ?: 0L

    /** 没有标题的会话用首条提问的前若干字当标题。 */
    private const val FallbackTitleChars = 24
}
