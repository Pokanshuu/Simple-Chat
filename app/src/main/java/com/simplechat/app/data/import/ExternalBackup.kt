package com.simplechat.app.data.import

import androidx.annotation.StringRes
import com.simplechat.app.R
import com.simplechat.app.data.AppText
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.BackupFile
import com.simplechat.app.data.BackupMessage
import com.simplechat.app.data.BackupPreset
import com.simplechat.app.data.Res
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 识别一份外部备份，并把它翻译成**我们自己的** `BackupFile` 模型。
 *
 * ### 为什么翻译成自己的模型，而不是直接写库
 *
 * 落库那段逻辑（去重、事务、预设挂载、覆盖/合并）已经写好并且测过了。
 * 换一种备份来源，变的**只是"怎么读"**；再写一遍"怎么写"除了多一处要维护的
 * 分叉，什么也换不来。所以这里只做翻译，写完交给现成的导入器。
 *
 * ### 支持的来源
 *
 * | 来源 | 载体 | 说明 |
 * |---|---|---|
 * | 本应用 | JSON | 原样透传 |
 * | **Chatbox 完整备份** | **ZIP**（`manifest.json` + 每个会话一个 JSON 条目 + 图片资源） | 见设计文档 §12.2 |
 * | Chatbox 备份（已解开的 JSON） | JSON | 有些版本导出的是单个 JSON |
 *
 * **不支持** Chatbox 的 Markdown 导出：那份格式是给人看的，
 * 靠"空行交替归属"猜角色，猜错了用户还看不出来 —— 不如直接说清楚。
 *
 * ### ⚠️ 这份格式是逆出来的，不是文档写好的
 *
 * 字段名对不上、类型猜错都**不会报错**，只会安静地导入出"一堆空对话"。
 * 所以下面每一条与真实文件的出入都有注释标明，并有单测钉住。
 * 已核实的真实结构（Chatbox 1.23.3 / formatVersion 2）：
 *
 * - 会话路径是 `sessions/<id>/session.json`（**带一层子目录**），不是 `sessions/<id>.json`；
 * - 消息**不写 `content`**，正文一律在 `contentParts[type=text]` 里；
 * - **系统提示词是一条 `role: "system"` 消息**，不存在 `session.systemPrompt`；
 * - 温度与模型在 `session.settings`（`{temperature, provider, modelId}`）；
 * - 附件图片在 ZIP 里（`resources` 条目），消息里只留一个 `storageKey`，
 *   要靠 manifest 的 `originalStorageKeys` 找回文件。
 */
object ExternalBackup {

    /** 认出来的来源，用于给用户一句准确的反馈。文案随界面语言取（见 [Source.nameRes]）。 */
    enum class Source(@StringRes val nameRes: Int) {
        SIMPLE_CHAT(R.string.backup_source_simplechat),
        CHATBOX(R.string.backup_source_chatbox),
    }

    /**
     * 导入途中给"从备份里长出来的条目"起名用的三句话。
     *
     * 为什么是**函数**而不是字符串：`parse` 是纯函数、有单测，而这几个名字要随
     * 界面语言取（走 [Res]）。做成 lambda 之后，默认实现只在**真被调用**时才碰
     * Android Resources —— 单测不走"导入图片 / 无名会话"那两条路，就一直是纯 JVM 的。
     */
    class ImportNaming(
        val imageName: (width: Int, height: Int) -> String =
            { w, h -> Res.get(R.string.import_image_name, w, h) },
        val imageFallbackName: () -> String =
            { Res.get(R.string.import_image_default_name) },
        val conversationFallbackTitle: () -> String =
            { Res.get(R.string.import_conversation_default) },
    )

    data class Parsed(
        val source: Source,
        val file: BackupFile,
        /** 有损/跳过的部分，如实告诉用户，别让他以为"全都进来了"。 */
        val warnings: List<AppText> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private const val CHATBOX_FORMAT = "chatbox-backup"

    /** ZIP 的文件头。用它区分"压缩包"和"纯 JSON"，比看扩展名可靠。 */
    private const val ZIP_MAGIC_0 = 0x50.toByte() // 'P'
    private const val ZIP_MAGIC_1 = 0x4B.toByte() // 'K'

    /**
     * 解析入口。
     *
     * @param scratchDir ZIP 形态要落一个临时文件才能读（原因见 [parseZip]）。
     *   传 app 的 `cacheDir`；默认值只方便单测。
     * @param naming 导入途中给图片 / 无名会话起名的文案，见 [ImportNaming]。
     * @throws AppTextException 认不出来时抛出，`text` 是给用户看的那句话（随界面语言）。
     */
    fun parse(
        bytes: ByteArray,
        scratchDir: File = File(System.getProperty("java.io.tmpdir")),
        naming: ImportNaming = ImportNaming(),
    ): Parsed {
        if (bytes.size >= 2 && bytes[0] == ZIP_MAGIC_0 && bytes[1] == ZIP_MAGIC_1) {
            return parseZip(bytes, scratchDir, naming)
        }

        val text = bytes.decodeToString()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw AppTextException(R.string.err_unrecognised_file)

        val format = root["format"]?.jsonPrimitive?.contentOrNull
        if (format == BackupFile.FORMAT) {
            val file = json.decodeFromString<BackupFile>(text)
            return Parsed(Source.SIMPLE_CHAT, file)
        }
        if (format == CHATBOX_FORMAT) {
            // 单文件形态：没有 ZIP 条目可用，图片只能放弃
            return buildParsed(
                sessions = root["sessions"].asObjectList(),
                manifest = root,
                imageOf = { null },
                naming = naming,
            )
        }

        throw AppTextException(R.string.err_unrecognised_format, format ?: "null")
    }

    // ── ZIP 形态的 Chatbox 备份 ───────────────────────────

    /**
     * ZIP 形态必须用 [ZipFile]，**不能用 `ZipInputStream`**。
     *
     * Chatbox 是 JSZip 打的包，每个条目都带**数据描述符**（flag bit 3：
     * 长度写在数据**之后**）。`ZipInputStream` 只允许 DEFLATED 条目这么做，
     * 而这份包里：
     *
     * | 条目 | 压缩方式 | ZipInputStream |
     * |---|---|---|
     * | `manifest.json`、会话 JSON | DEFLATED | 能读 |
     * | 图片资源 | **STORED** | ✗ 抛 `only DEFLATED entries can have EXT descriptor` |
     *
     * 而且 `manifest.json` 恰好是**最后一个**条目 —— 顺序流必须一路跳过前面的
     * STORED 图片才能读到最后那个 manifest，于是必然抛异常。
     * `ZipFile` 走**中央目录**（长度记在那里，与描述符无关），所以稳定。
     *
     * 代价是它要一个 `File`，所以这里落一个临时文件。4MB 落一次盘，
     * 换掉一个"导入直接失败"的 bug，划算。
     */
    private fun parseZip(bytes: ByteArray, scratchDir: File, naming: ImportNaming): Parsed {
        if (!scratchDir.exists()) scratchDir.mkdirs()
        if (!scratchDir.isDirectory) {
            throw AppTextException(R.string.err_scratch_dir, scratchDir.toString())
        }

        val temp = File.createTempFile("chatbox-import-", ".zip", scratchDir)
        try {
            temp.writeBytes(bytes)

            ZipFile(temp).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json")
                    ?: throw AppTextException(R.string.err_no_manifest)

                val manifest = runCatching {
                    json.parseToJsonElement(zip.getInputStream(manifestEntry).readBytes().decodeToString())
                        .jsonObject
                }.getOrNull() ?: throw AppTextException(R.string.err_manifest_parse)

                val format = manifest["format"]?.jsonPrimitive?.contentOrNull
                if (format != CHATBOX_FORMAT) {
                    throw AppTextException(R.string.err_not_chatbox, format ?: "null")
                }

                // 真实路径是 sessions/<id>/session.json（带一层子目录）
                val sessions = zip.entries().asSequence()
                    .filter {
                        !it.isDirectory && it.name.startsWith("sessions/") && it.name.endsWith(".json")
                    }
                    .mapNotNull { entry ->
                        runCatching {
                            json.parseToJsonElement(zip.getInputStream(entry).readBytes().decodeToString())
                                .jsonObject
                        }.getOrNull()
                    }
                    .toList()

                // storageKey → 资源。只收 kind=image，头像（kind=avatar）不是消息附件。
                val resources = indexResources(manifest)

                /*
                 * 图片：消息里只有一个 storageKey，真正的字节在 ZIP 的 resources 里。
                 *
                 * ZipFile 是随机访问，所以"要先知道要哪些图、才能去读"这件事
                 * 在这里不构成问题 —— 不需要再扫一遍包。
                 */
                val needed = sessions.flatMap { imageKeysOf(it) }.toSet()
                val images = needed.mapNotNull { key ->
                    val ref = resources[key] ?: return@mapNotNull null
                    val entry = zip.getEntry(ref.path) ?: return@mapNotNull null
                    key to buildImageAttachment(ref, zip.getInputStream(entry).readBytes(), naming)
                }.toMap()

                return buildParsed(
                    sessions = sessions,
                    manifest = manifest,
                    imageOf = images::get,
                    naming = naming,
                )
            }
        } finally {
            temp.delete()
        }
    }

    /** 一个资源条目在 manifest 里的登记信息。 */
    private data class ResourceRef(val path: String, val mime: String)

    /**
     * 建立 `storageKey` → 资源 的索引。
     *
     * 消息里存的是 `picture:input-box:<uuid>` 这种 key，而 ZIP 里是
     * `resources/resource-000002.jpg` —— 两边唯一的接头是 manifest 里
     * 每个资源的 `originalStorageKeys` 数组。
     *
     * **只收 `kind == "image"`**：`kind == "avatar"` 是会话头像，
     * 不是消息内容，导进来会变成一堆莫名其妙的图片气泡。
     */
    private fun indexResources(manifest: JsonObject): Map<String, ResourceRef> {
        val out = HashMap<String, ResourceRef>()
        for (resource in manifest["resources"].asObjectList()) {
            if (resource["kind"]?.jsonPrimitive?.contentOrNull != "image") continue
            val path = resource["path"]?.jsonPrimitive?.contentOrNull ?: continue
            val mime = resource["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/jpeg"
            val ref = ResourceRef(path, mime)
            for (key in resource["originalStorageKeys"].asStringList()) {
                out[key] = ref
            }
        }
        return out
    }

    /** 一条会话里所有被引用到的图片 key。 */
    private fun imageKeysOf(session: JsonObject): Set<String> {
        val keys = mutableSetOf<String>()
        for (message in session["messages"].asObjectList()) {
            for (part in message["contentParts"].asObjectList()) {
                if (part["type"]?.jsonPrimitive?.contentOrNull != "image") continue
                part["storageKey"]?.jsonPrimitive?.contentOrNull?.let { keys += it }
            }
        }
        return keys
    }

    private fun buildImageAttachment(
        ref: ResourceRef,
        blob: ByteArray,
        naming: ImportNaming,
    ): Attachment {
        val size = imageSizeOf(blob)
        return Attachment(
            kind = Attachment.Kind.IMAGE,
            // 资源本身没有文件名（ZIP 里就是 resource-000002.jpg），
            // 用尺寸当可读标识，比一串 uuid 有用
            name = size?.let { naming.imageName(it.first, it.second) } ?: naming.imageFallbackName(),
            mime = ref.mime,
            data = Base64.getEncoder().encodeToString(blob),
            width = size?.first ?: 0,
            height = size?.second ?: 0,
        )
    }

    // ── 会话与消息 ────────────────────────────────────────

    private fun buildParsed(
        sessions: List<JsonObject>,
        manifest: JsonObject,
        imageOf: (String) -> Attachment?,
        naming: ImportNaming,
    ): Parsed {
        val warnings = mutableListOf<AppText>()
        var droppedImages = 0

        val conversations = sessions.mapNotNull { session ->
            runCatching { parseSession(session, imageOf, naming) { droppedImages++ } }.getOrNull()
        }

        if (conversations.isEmpty()) {
            throw AppTextException(R.string.err_no_sessions)
        }

        if (droppedImages > 0) {
            warnings += AppText.of(R.string.warn_dropped_images, droppedImages)
        }
        if (conversations.any { it.messages.isEmpty() }) {
            warnings += AppText.of(R.string.warn_no_messages)
        }
        warnings += AppText.of(R.string.warn_single_branch)

        return Parsed(
            source = Source.CHATBOX,
            file = BackupFile(
                exportedAt = System.currentTimeMillis(),
                conversations = conversations,
                presets = parseCopilots(manifest),
            ),
            warnings = warnings,
        )
    }

    private fun parseSession(
        session: JsonObject,
        imageOf: (String) -> Attachment?,
        naming: ImportNaming,
        onImageDropped: () -> Unit,
    ): BackupConversation? {
        val rawMessages = session["messages"].asObjectList()
        if (rawMessages.isEmpty()) return null

        val sessionId = session["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: UUID.nameUUIDFromBytes(rawMessages.toString().toByteArray()).toString()

        /*
         * 系统提示词。
         *
         * 真实格式里**没有** `session.systemPrompt` —— 它是 messages 里
         * 开头的 `role: "system"` 消息（有的会话有两条）。
         *
         * 这一条丢不得：对本项目来说系统提示词就是人设与写作要求，
         * 丢了等于把会话掏空。所以 system 消息**不进对话流**，
         * 而是合并成会话的 systemPrompt。
         */
        val systemPrompts = rawMessages.mapNotNull { raw ->
            if (raw.role() != "system") return@mapNotNull null
            textOf(raw).takeIf { it.isNotBlank() }
        }

        var parentId = ""
        val out = mutableListOf<BackupMessage>()
        val timestamps = mutableListOf<Long>()

        rawMessages.forEachIndexed { index, raw ->
            val role = raw.role()
            // tool 角色本期丢弃（本期不实现工具调用）
            if (role != "user" && role != "assistant") return@forEachIndexed

            val content = textOf(raw)
            val reasoning = reasoningOf(raw)

            // 图片：消息里只有一个 storageKey，真正的字节在资源里
            val attachments = raw["contentParts"].asObjectList()
                .filter { it["type"]?.jsonPrimitive?.contentOrNull == "image" }
                .mapNotNull { part ->
                    val key = part["storageKey"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    imageOf(key).also { if (it == null) onImageDropped() }
                }

            // 纯图片的消息没有正文，但**不该丢** —— 正文留空即可
            if (content.isBlank() && reasoning.isNullOrBlank() && attachments.isEmpty()) {
                return@forEachIndexed
            }

            val id = raw["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: UUID.nameUUIDFromBytes("$sessionId#$index".toByteArray()).toString()
            val at = timestampOf(raw)

            timestamps += at
            out += BackupMessage(
                id = id,
                parentId = parentId,
                // Chatbox 的分支结构（messageForksHash）与我们的语义不同，
                // 这里只取主链，全部作为单版本导入
                variantIndex = 0,
                active = true,
                role = role,
                content = content,
                attachments = attachments,
                reasoning = reasoning,
                status = "DONE",
                createdAt = at,
            )
            parentId = id
        }

        if (out.isEmpty()) return null

        val created = timestamps.minOrNull() ?: System.currentTimeMillis()
        val updated = timestamps.maxOrNull() ?: created

        // session.settings 里有 provider / modelId；它们在本项目是"只写不读"的
        // 记录列，但**照着原样存下来**比硬编码一个常量更诚实
        val settings = session["settings"] as? JsonObject

        return BackupConversation(
            id = sessionId,
            title = session["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: naming.conversationFallbackTitle(),
            mode = "CREATIVE",
            provider = settings?.get("provider")?.jsonPrimitive?.contentOrNull
                ?.uppercase()
                ?.takeIf { it.isNotBlank() }
                ?: "DEEPSEEK",
            model = settings?.get("modelId")?.jsonPrimitive?.contentOrNull.orEmpty(),
            systemPrompt = systemPrompts.joinToString("\n\n").takeIf { it.isNotBlank() },
            createdAt = created,
            updatedAt = updated,
            messages = out,
        )
    }

    /**
     * 正文：优先 `content` 字符串，否则把 `contentParts` 里的 text 块拼起来。
     *
     * 真实备份里**永远走第二条路** —— 实测 `content` 字段一次都没出现过。
     * 留第一条是为了兼容更老的导出。
     */
    private fun textOf(message: JsonObject): String {
        message["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }

        return message["contentParts"].asObjectList()
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n\n")
            .trim()
    }

    /** 思考内容：`contentParts` 里的 reasoning 块，多块合并。 */
    private fun reasoningOf(message: JsonObject): String? {
        val merged = message["contentParts"].asObjectList()
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "reasoning" }
            .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("\n\n")
            .trim()
        return merged.ifBlank { null }
    }

    private fun JsonObject.role(): String =
        this["role"]?.jsonPrimitive?.contentOrNull.orEmpty()

    /** 时间戳：Chatbox 有的写数字（毫秒）、有的写 ISO 字符串。 */
    private fun timestampOf(message: JsonObject): Long {
        val element = message["timestamp"] ?: message["createdAt"] ?: return System.currentTimeMillis()
        element.jsonPrimitive.longOrNull?.let { return it }

        val text = element.jsonPrimitive.contentOrNull ?: return System.currentTimeMillis()
        return runCatching { java.time.Instant.parse(text).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }
    }

    /**
     * Chatbox 的「Copilot」映射成我们的预设。
     *
     * Copilot 本质上就是"一段可命名的系统提示词"，和我们的预设是同一个东西。
     * 字段名在不同版本里叫过 `prompt` / `description`，两个都认。
     *
     * 注意：真实备份里 `data` 常常是**空的** —— 导出时可以只勾"会话"，
     * 不勾"预设"（`exportItems: ["conversations"]`）。那时这里就是空列表。
     */
    private fun parseCopilots(manifest: JsonObject): List<BackupPreset> {
        val data = manifest["data"] as? JsonObject ?: return emptyList()
        val copilots: List<JsonObject> = when (val raw = data["copilots"]) {
            is JsonArray -> raw.mapNotNull { it as? JsonObject }
            is JsonObject -> raw.values.mapNotNull { it as? JsonObject }
            else -> return emptyList()
        }

        return copilots.mapIndexedNotNull { index, copilot ->
            val name = copilot["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            val content = copilot["prompt"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: copilot["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            val id = copilot["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "cb-copilot-$index"

            BackupPreset(
                id = "chatbox-$id",
                name = name,
                content = content,
                sortOrder = index,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    /** 容错：不是数组、或元素不是对象时一律当空，不给用户抛解析异常。 */
    private fun kotlinx.serialization.json.JsonElement?.asObjectList(): List<JsonObject> =
        (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun kotlinx.serialization.json.JsonElement?.asStringList(): List<String> =
        (this as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    /** 供 UI 判断"要不要提示这是外部来源"。 */
    fun peekFormat(bytes: ByteArray): String? = runCatching {
        if (bytes.size >= 2 && bytes[0] == ZIP_MAGIC_0 && bytes[1] == ZIP_MAGIC_1) {
            CHATBOX_FORMAT
        } else {
            json.parseToJsonElement(bytes.decodeToString()).jsonObject["format"]
                ?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull()
}
