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

    /** 这份 ZIP 是不是 DeepSeek 导出（只看 `manifest.json` 的一个字段）。 */
    fun detect(zip: ZipFile): Boolean {
        val manifest = readJson(zip, MANIFEST) ?: return false
        return manifest["export_format_version"]?.jsonPrimitive?.intOrNull != null
    }

    /**
     * 认出来就翻译成我们的备份模型；`manifest.json` 里没有
     * `export_format_version` 说明不是这份格式，返回 null（交给别的分支）。
     *
     * ⚠️ 大包**不要走这条**：它把所有会话连同图片 base64 一次性放进 `BackupFile`，
     * 228MB 的归档解压后能有几百兆，必 OOM。导入路径用 [stream]（逐条产出、逐批写库）。
     */
    fun parse(
        zip: ZipFile,
        naming: ExternalBackup.ImportNaming,
        onProgress: (Float) -> Unit = {},
    ): BackupFile? {
        val streamed = stream(zip, naming, onProgress) ?: return null
        return BackupFile(
            exportedAt = System.currentTimeMillis(),
            conversations = streamed.toList(),
        )
    }

    /**
     * **流式**产出会话：每 `next()` 才读一条会话 JSON 与它用到的图片。
     *
     * 为什么必须这样：整包解析的峰值内存 = 全部会话 + 全部图片 base64 之和。
     * 实测 2106 个会话 / 24535 条消息的归档，在解压之后是几百兆，堆里放不下
     * （报错形如 `Failed to allocate a 893424 byte allocation with 424432 free bytes`）。
     * 流式之后内存只与**单条最大的会话**有关，与归档总量无关。
     *
     * 调用方必须在 `ZipFile` 关闭之前把序列消费完。
     */
    fun stream(
        zip: ZipFile,
        naming: ExternalBackup.ImportNaming,
        onProgress: (Float) -> Unit = {},
    ): Sequence<BackupConversation>? {
        val manifest = readJson(zip, MANIFEST) ?: return null
        val version = manifest["export_format_version"]?.jsonPrimitive?.intOrNull ?: return null
        if (version != FORMAT_VERSION) {
            throw AppTextException(R.string.err_deepseek_version, version, FORMAT_VERSION)
        }

        val indexOrder = readJson(zip, INDEX)
            ?.let { it.arrOrEmpty("sessions").mapNotNull { entry -> entry.str("id") } }
            .orEmpty()

        // 索引里的顺序就是用户在 DeepSeek 里看到的顺序；没有索引就按文件名（≈ 会话 id）
        val rank = indexOrder.withIndex().associate { (i, id) -> "$ID_PREFIX$id" to i }
        val sessionEntries = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(SESSIONS) && it.name.endsWith(".json") }
            // 图片目录 `chat_sessions/{id}_files/` 里的东西不在这里（它们不叫 .json）
            .filter { !it.name.removePrefix(SESSIONS).contains('/') }
            .toList()
            .sortedBy { entry ->
                val id = entry.name.removePrefix(SESSIONS).removeSuffix(".json")
                rank["$ID_PREFIX$id"] ?: Int.MAX_VALUE
            }

        val total = sessionEntries.size.coerceAtLeast(1)
        var done = 0
        /*
         * 图片总量预算。
         *
         * 图片以 base64 存进数据库的**同一行**，一份 228MB 的导出包里绝大多数体积都是图 ——
         * 全导进来会让数据库涨到几百 MB，应用从此又慢又卡。这里按总量封顶，
         * 超出的部分跳过（导入完成时的提示里已说明）。
         */
        var imageBudget = MaxImportedImageBytes

        return sessionEntries.asSequence().mapNotNull { entry ->
            // 进度按"解析完多少条会话"报，含图片读取 —— 大包主要时间都在这
            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            done++
            // 单个会话 JSON 也有上限：异常大的会话宁可跳过，也不要把整个导入拖垮
            val raw = runCatching { zip.readBounded(entry, MaxSessionJsonBytes)?.decodeToString() }
                .getOrNull() ?: return@mapNotNull null
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: return@mapNotNull null
            // 先把这一条会话用到的图片读出来，再解析 —— 图片按 `export_path` 直接定位条目
            val images = imagePathsOf(obj).mapNotNull { path ->
                if (imageBudget <= 0) return@mapNotNull null
                readImage(zip, path, naming)?.let { image ->
                    imageBudget -= image.sizeBytes
                    path to image
                }
            }.toMap()
            mapSession(obj, naming) { images[it] }
        }
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
        val raw = session.arrOrEmpty("messages")
        if (raw.isEmpty()) return null

        /*
         * ★ **整棵树都导，不只当前分支。**
         *
         * 归档里每条消息都带 `parent_id`（官方 README 也写明"按 id / parent_id 还原"），
         * 所以分支本来就在数据里 —— 早先只导当前分支是**我们的取舍**，不是包的限制。
         * 我们的树模型（`parentId` + `variantIndex` + `active`）能原样表达它：
         * 同父下按 id 升序编 `variantIndex`，当前正看着的那条标 `active`；
         * 不在当前路径上的分支，按官方规则"默认选中 id 最大的子消息"。
         */
        val byNumber = LinkedHashMap<Long, JsonObject>()
        for (message in raw) {
            message.long("id")?.let { id -> if (id !in byNumber) byNumber[id] = message }
        }
        if (byNumber.isEmpty()) return null

        // 当前分支：既用来判定"哪一条是选中的版本"，也用来取首条提问当标题
        val branch = linearize(raw, session.long("current_message_id"))
        if (branch.isEmpty()) return null
        val branchIds = branch.mapNotNull { it.long("id") }.toHashSet()

        /*
         * 按父节点分组，顺带把"断链"的行筛掉：`parent_id` 指向不存在的消息时，
         * 那一支永远连不到根 —— 留着它只会在界面里变成一段谁也不认识的孤儿。
         */
        val childrenOf = LinkedHashMap<Long?, MutableList<Long>>()
        for ((id, message) in byNumber) {
            val rawParent = message.long("parent_id")
            val parent = when {
                rawParent == null -> null                       // 根
                byNumber.containsKey(rawParent) -> rawParent     // 正常父子
                else -> continue                                 // 断链：整支丢掉
            }
            childrenOf.getOrPut(parent) { mutableListOf() } += id
        }
        childrenOf.values.forEach { it.sort() }

        // BFS 出全树顺序（父在子前，便于阅读与排错）
        val ordered = ArrayList<Long>(byNumber.size)
        val visited = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        childrenOf[null]?.let { queue.addAll(it) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!visited.add(id)) continue
            ordered += id
            childrenOf[id]?.let { queue.addAll(it) }
        }
        if (ordered.isEmpty()) return null

        // 同父下的序号 = 变体号；选中的那条 = 当前分支上的那条，否则 id 最大的那条
        val variantIndexOf = HashMap<Long, Int>()
        val selectedNumbers = HashSet<Long>()
        for ((_, siblings) in childrenOf) {
            siblings.forEachIndexed { index, id -> variantIndexOf[id] = index }
            selectedNumbers += siblings.firstOrNull { it in branchIds } ?: siblings.last()
        }

        val title = session.str("title")?.takeIf { it.isNotBlank() }
            ?: branch.firstOrNull { it.str("role") == "USER" }
                ?.textOf("REQUEST")
                ?.take(FallbackTitleChars)
                ?.takeIf { it.isNotBlank() }
            ?: naming.conversationFallbackTitle()

        val insertedAt = parseIso(session.str("inserted_at")) ?: System.currentTimeMillis()
        val updatedAt = parseIso(session.str("updated_at")) ?: insertedAt

        val mapped = ordered.map { number ->
            val message = byNumber.getValue(number)
            val role = if (message.str("role") == "USER") "user" else "assistant"
            val content = if (role == "user") {
                message.textOf("REQUEST")
            } else {
                // 模板正文（超时 / 内容过滤那类）也是正文：我们只透传，不做取舍
                message.textOf("RESPONSE") + message.textOf("TEMPLATE_RESPONSE")
            }
            val think = message.fragmentsOf("THINK")
            val elapsedSecs = think.sumOf { it.longOrZero("elapsed_secs") }
            /*
             * 单条消息的附件也要封顶。
             *
             * 附件是**同一行**里的一个 JSON 列，图片 base64 一多，整行就会超过
             * Android `CursorWindow` 的 2MB —— 那条会话从此打不开（`SQLiteBlobTooBigException`）。
             * 实测导入后有单行 7.4MB，必须在这里挡住。
             */
            var perMessage = MaxPerMessageImageBytes
            val images = message.fragmentsOf("FILE").flatMap { file ->
                file.arrOrEmpty("files").mapNotNull { entry ->
                    if (entry.str("is_image") != "true") return@mapNotNull null
                    if (perMessage <= 0) return@mapNotNull null
                    val path = entry.str("export_path") ?: return@mapNotNull null
                    imageOf(path)?.also { image ->
                        perMessage -= image.sizeBytes
                    }
                }
            }

            BackupMessage(
                id = "$ID_PREFIX$sessionId-$number",
                parentId = message.long("parent_id")
                    ?.takeIf { byNumber.containsKey(it) }
                    ?.let { "$ID_PREFIX$sessionId-$it" }
                    ?: "",
                variantIndex = variantIndexOf[number] ?: 0,
                active = number in selectedNumbers,
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
     * 现在**不再用它筛消息**（整棵树都导，见 [mapSession]），只用来判定：
     * 哪一条是用户当时选中的版本、以及拿首条提问当标题。
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
        /*
         * ⚠️ 必须在**读之前**看上限定。
         *
         * 先 `readBytes()` 再判断大小等于没判：一张 43MB 的截图会先分配 43MB，
         * 而那时堆里往往只剩二三十兆 —— 直接 OOM（实测报错
         * "Failed to allocate a 45388952 byte allocation"）。
         */
        val bytes = runCatching { zip.readBounded(entry, MaxSingleImageBytes) }.getOrNull()
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

    /**
     * 有界读取：超过 [limit] 直接放弃（返回 null），不会先把整块分配出来。
     *
     * 这是修 OOM 的关键 —— `readBytes()` 是按条目大小一次性分配的，
     * 而归档里完全可能存在几十兆的图片，堆里根本放不下。
     */
    private fun ZipFile.readBounded(entry: java.util.zip.ZipEntry, limit: Int): ByteArray? {
        // 中央目录里有长度：能提前否掉就不读
        if (entry.size > limit) return null
        val expected = entry.size.coerceAtLeast(0L).toInt()
        val out = java.io.ByteArrayOutputStream(minOf(expected, limit))
        getInputStream(entry).use { input ->
            val buffer = ByteArray(BufferChunk)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > limit) return null
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    /** 读取用的块大小。64KB：够快，又不会自己变成一次大分配。 */
    private const val BufferChunk = 64 * 1024

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

    /** 单张图片上限：再大的塞进数据库只会拖垮列表滚动。 */
    private const val MaxSingleImageBytes = 6 * 1024 * 1024

    /**
     * 单条消息的图片总量上限（原始字节）。
     *
     * 附件与正文在**同一行**，而 Android 的 CursorWindow 只有 2MB：
     * base64 之后不能超过它，否则那条会话一次都读不出来。这里按 800KB 原始字节封顶
     * （base64 约 1.07MB，留足余量）。
     */
    private const val MaxPerMessageImageBytes = 800 * 1024

    /** 一次导入的图片总量上限（base64 之后还会再涨三分之一）。 */
    private const val MaxImportedImageBytes = 24 * 1024 * 1024

    /** 单个会话 JSON 的上限。超过就跳过这一条会话。 */
    private const val MaxSessionJsonBytes = 24 * 1024 * 1024

    /** 没有标题的会话用首条提问的前若干字当标题。 */
    private const val FallbackTitleChars = 24
}
