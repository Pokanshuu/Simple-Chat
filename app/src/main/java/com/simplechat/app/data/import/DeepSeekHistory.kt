package com.simplechat.app.data.import

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.ApiImage
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.BackupMessage
import com.simplechat.app.data.ImportFailure
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
     * 打开一份归档：会话是**懒**产出的，[expected] 是归档声明的会话数。
     *
     * 为什么把"声明数"带出来：导入结果要对账（见 `ImportReport`）。
     * 归档里有几条、进来几条，用户一眼能看出有没有丢 —— 这是导入链路的底线。
     */
    internal class DeepSeekStream(
        private val sessions: Sequence<BackupConversation>,
        /** 归档声明的会话数：文件条目 + 索引里有但文件缺失的那些。 */
        val expected: Int,
    ) : Sequence<BackupConversation> by sessions

    /** 这份 ZIP 是不是 DeepSeek 导出（看 `manifest.json`，读不出来时退一步看目录形状）。 */
    fun detect(zip: ZipFile): Boolean {
        val manifest = readJson(zip, MANIFEST)
        if (manifest != null) return manifest["export_format_version"] != null
        /*
         * manifest 读不出来（包被截断 / 数据区损坏）时退一步看**目录形状**。
         *
         * 不退这一步的后果很糟：包走到 Chatbox 分支，最后抛一句英文的
         * `Unexpected end of ZLIB input stream`（v1.1.3 之前多位用户的真实反馈），
         * 用户既不知道发生了什么，也不知道该重下文件。
         */
        return hasSessionEntries(zip)
    }

    /** 归档里有没有"会话 JSON"这种条目 —— 只用于判断形状，不读内容。 */
    private fun hasSessionEntries(zip: ZipFile): Boolean = runCatching {
        zip.entries().asSequence().any { entry ->
            !entry.isDirectory && entry.name.startsWith(SESSIONS) && entry.name.endsWith(".json") &&
                !entry.name.removePrefix(SESSIONS).contains('/')
        }
    }.getOrDefault(false)


    /**
     * 打开归档，拿到**懒**产出的会话序列。
     *
     * 调用方必须在 `ZipFile` 关闭之前把序列消费完（写入也在同一段 `use` 里）。
     *
     * 为什么是"打开"而不是"解析成 BackupFile"：一份 2106 个会话、24535 条消息的
     * 归档解压后是几百兆，整包建对象必然 OOM（实测 `Failed to allocate 893424 byte…`）。
     * 逐条产出、逐批写库之后，峰值只与"最大的那条会话"有关，与归档总量无关。
     *
     * @param onFailure 单条会话没能导入时回调 —— 结果页据此对账（见 `ImportReport`）。
     * @param onImageDropped 图片原件取不回来时回调（归档里缺条目 / 读取失败）。
     */
    fun open(
        zip: ZipFile,
        naming: ExternalBackup.ImportNaming,
        onProgress: (Float) -> Unit = {},
        onFailure: (ImportFailure) -> Unit = {},
        onImageDropped: () -> Unit = {},
    ): DeepSeekStream? {
        val manifest = readJson(zip, MANIFEST) ?: run {
            /*
             * manifest 读不出来但形状对得上 → 归档**不完整**。
             *
             * 不返回 null：那样会被当成"不是这份格式"，最后给用户一句英文的
             * `Unexpected end of ZLIB input stream`。这里直接给一句人能看懂、
             * 也知道下一步该干什么的话（v1.1.3 之前多位用户的真实反馈）。
             */
            if (hasSessionEntries(zip)) throw AppTextException(R.string.err_archive_damaged)
            return null
        }
        val version = manifest["export_format_version"]?.jsonPrimitive?.intOrNull ?: return null
        if (version != FORMAT_VERSION) {
            throw AppTextException(R.string.err_deepseek_version, version, FORMAT_VERSION)
        }

        /*
         * 索引有两个用途：顺序（用户在 DeepSeek 里看到的顺序）与**标题**。
         *
         * 标题的用处不止排序：某条会话的文件缺失/损坏时，索引里还留着它的名字 ——
         * 结果页就能说"少了《夏萧因重逢对话》"，而不是干巴巴一个"1 个未能导入"。
         */
        val indexEntries = readJson(zip, INDEX)?.arrOrEmpty("sessions").orEmpty()
        val indexIds = indexEntries.mapNotNull { it.str("id") }.distinct()
        val indexTitles = indexEntries
            .mapNotNull { entry -> entry.str("id")?.let { id -> id to entry.str("title") } }
            .toMap()
        val rank = indexIds.withIndex().associate { (position, id) -> id to position }

        val sessionEntries = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(SESSIONS) && it.name.endsWith(".json") }
            // 图片目录 `chat_sessions/{id}_files/` 里的东西不在这里（它们不叫 .json）
            .filter { !it.name.removePrefix(SESSIONS).contains('/') }
            .toList()
            .sortedBy { entry -> rank[sessionIdOf(entry.name)] ?: Int.MAX_VALUE }

        /*
         * 对账的第一半：索引里有、文件却没有的会话。
         *
         * 这不是"猜"——索引是归档自己写的清单，清单上有而磁盘上没有，就是丢了。
         * 报出来，用户才知道该重新导出，而不是以为我们的导入漏了。
         */
        val presentIds = sessionEntries.map { sessionIdOf(it.name) }.toHashSet()
        val missingIds = indexIds.filterNot { it in presentIds }
        missingIds.forEach { id ->
            onFailure(ImportFailure(title = indexTitles[id], reference = id, reason = ImportFailure.Reason.MISSING_ENTRY))
        }
        // 声明数 = 归档里的文件 + 清单上有但文件缺失的（文件多于索引时以文件为准）
        val expected = presentIds.size + missingIds.size

        val total = sessionEntries.size.coerceAtLeast(1)
        var done = 0

        val sessions = sessionEntries.asSequence().mapNotNull { entry ->
            // 进度按"解析完多少条会话"报，含图片读取 —— 大包主要时间都在这
            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            done++
            val sessionId = sessionIdOf(entry.name)
            fun failed(reason: ImportFailure.Reason): BackupConversation? {
                onFailure(ImportFailure(title = indexTitles[sessionId], reference = sessionId, reason = reason))
                return null
            }

            val raw = runCatching { zip.readBounded(entry, MaxSessionJsonBytes)?.decodeToString() }
                .getOrNull()
                ?: return@mapNotNull failed(
                    if (entry.size > MaxSessionJsonBytes) ImportFailure.Reason.TOO_LARGE
                    else ImportFailure.Reason.UNREADABLE,
                )

            /*
             * 单条会话 JSON 也有上限，但**不用整棵树**来解析：
             *
             * 归档里的会话 JSON 是"标题 + 一个消息数组"。整棵建树（`JsonObject`）时，
             * 每条消息的字段名、片段、类型都要各占一个对象，实测比原文膨胀 2~3 倍 ——
             * 8MB 的会话能吃掉一百多兆堆，于是早先把上限压到 8MB，超了就整条跳过。
             * 代价是**长会话（尤其带分支的）直接丢**，结果页只看到"少了一个会话"。
             *
             * 现在按字段切片：会话说到底只有几个标量 + 一个数组，数组里**一次只解析一条消息**，
             * 解析完立刻压成 [ParsedMessage]（只有正文与几个数字）。峰值只与"最大的那条消息"
             * 有关，于是上限可以放宽到 [MaxSessionJsonBytes]。
             */
            val parsed = runCatching { parseSession(raw) }.getOrNull()
                ?: return@mapNotNull failed(ImportFailure.Reason.UNREADABLE)

            /*
             * 图片按 `export_path` **用到才读**，并且同一张图只读一次：
             * 一条消息里引用两次同一张图（或两个分支引用同一张）时，既不该读两遍，
             * 也不该把"没取回"算两次 —— 那会让结果页的图片数虚高。
             */
            val images = HashMap<String, Attachment?>()
            fun imageOf(path: String): Attachment? {
                if (images.containsKey(path)) return images[path]
                val image = readImage(zip, path, naming)
                if (image == null) onImageDropped()
                images[path] = image
                return image
            }

            val conversation = mapSession(parsed, naming, ::imageOf)
                ?: return@mapNotNull failed(
                    if (parsed.messages.isEmpty()) ImportFailure.Reason.EMPTY
                    else ImportFailure.Reason.BROKEN_TREE,
                )
            conversation
        }

        return DeepSeekStream(sessions, expected)
    }

    private fun sessionIdOf(entryName: String): String =
        entryName.removePrefix(SESSIONS).removeSuffix(".json")

    // ══════════════════════════════════════════════════════
    //  解析：把一份会话 JSON 切成"标量 + 消息列表"
    // ══════════════════════════════════════════════════════

    /** 一条会话的**紧凑**形式：没有 JsonObject 树，只有取值后的正文与数字。 */
    internal data class ParsedSession(
        val id: String?,
        val title: String?,
        val pinned: Boolean,
        val currentMessageId: Long?,
        val insertedAt: String?,
        val updatedAt: String?,
        val messages: List<ParsedMessage>,
    )

    /**
     * 一条消息的紧凑形式。
     *
     * 字段刻意只留"进数据库/进界面"需要的那几样：多余的字段留在原文里不发散，
     * 这样几百条消息的会话也不会因为解析而膨胀。
     */
    internal data class ParsedMessage(
        val number: Long,
        val parentNumber: Long?,
        val role: String?,
        val status: String?,
        val insertedAt: String?,
        /** `REQUEST` 片段拼接（用户提问）。 */
        val request: String,
        /** `RESPONSE` 片段拼接（模型正文）。 */
        val response: String,
        /** `TEMPLATE_RESPONSE` 片段拼接（超时 / 内容过滤那类服务端模板）。 */
        val template: String,
        /** 思考片段：正文 + 耗时（秒，可能缺省）。 */
        val thinks: List<Pair<String, Long>>,
        /** 图片原件的 ZIP 条目名（`export_path`），按出现顺序。 */
        val imagePaths: List<String>,
    )

    /**
     * 按字段切片解析一条会话。
     *
     * 只做两件事：标量字段原样切片（最后拼成一个小对象交给 kotlinx 解析，省得自己写反序列化），
     * 消息数组**逐条**切片 → 解析 → 压成 [ParsedMessage] → 丢掉原树。
     */
    internal fun parseSession(raw: String): ParsedSession? {
        val cursor = JsonCursor(raw)
        val scalars = LinkedHashMap<String, String>()
        var messagesRange: IntRange? = null
        cursor.forEachField { name, value ->
            when (name) {
                "messages" -> messagesRange = value
                "id", "title", "pinned", "current_message_id", "inserted_at", "updated_at" ->
                    scalars[name] = raw.substring(value.first, value.last + 1)
                else -> Unit
            }
        }

        // 标量拼成一个小 JSON 再交给 kotlinx：类型容错（数字 / null / 字符串）全由它兜住
        val headText = scalars.entries.joinToString(separator = ",", prefix = "{", postfix = "}") {
            "\"${it.key}\":${it.value}"
        }
        val head = runCatching { json.parseToJsonElement(headText).jsonObject }.getOrNull() ?: return null

        val messages = ArrayList<ParsedMessage>()
        val range = messagesRange
        if (range != null) {
            JsonCursor(raw).at(range.first).forEachElement { element ->
                // 一次只建一条消息的树：这是整个导入链路里唯一还"整条"解析的对象
                val obj = runCatching {
                    json.parseToJsonElement(raw.substring(element.first, element.last + 1)).jsonObject
                }.getOrNull() ?: return@forEachElement
                parsedMessage(obj)?.let { messages += it }
            }
        }

        return ParsedSession(
            id = head.str("id"),
            title = head.str("title"),
            pinned = head.str("pinned") == "true",
            currentMessageId = head.long("current_message_id"),
            insertedAt = head.str("inserted_at"),
            updatedAt = head.str("updated_at"),
            messages = messages,
        )
    }

    private fun parsedMessage(message: JsonObject): ParsedMessage? {
        val number = message.long("id") ?: return null
        val request = StringBuilder()
        val response = StringBuilder()
        val template = StringBuilder()
        val thinks = ArrayList<Pair<String, Long>>()
        val images = ArrayList<String>()
        for (fragment in message.arrOrEmpty("fragments")) {
            when (fragment.str("type")) {
                "REQUEST" -> request.append(fragment.str("content").orEmpty())
                "RESPONSE" -> response.append(fragment.str("content").orEmpty())
                "TEMPLATE_RESPONSE" -> template.append(fragment.str("content").orEmpty())
                "THINK" -> thinks += fragment.str("content").orEmpty() to fragment.longOrZero("elapsed_secs")
                "FILE" -> fragment.arrOrEmpty("files").forEach { entry ->
                    if (entry.str("is_image") != "true") return@forEach
                    entry.str("export_path")?.let { images += it }
                }
            }
        }
        return ParsedMessage(
            number = number,
            parentNumber = message.long("parent_id"),
            role = message.str("role"),
            status = message.str("status"),
            insertedAt = message.str("inserted_at"),
            request = request.toString(),
            response = response.toString(),
            template = template.toString(),
            thinks = thinks,
            imagePaths = images,
        )
    }

    // ══════════════════════════════════════════════════════
    //  映射：会话 → 我们的备份模型
    // ══════════════════════════════════════════════════════

    /**
     * 一条会话 → [BackupConversation]。纯函数（图片由 [imageOf] 提供），有单测。
     *
     * @param imageOf 给出 ZIP 条目名（`export_path`）取图片附件；拿不到返回 null，
     *   该图片会记进 `droppedImages`。
     */
    internal fun mapSession(
        session: ParsedSession,
        naming: ExternalBackup.ImportNaming,
        imageOf: (String) -> Attachment?,
    ): BackupConversation? {
        val sessionId = session.id ?: return null
        val raw = session.messages
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
        val byNumber = LinkedHashMap<Long, ParsedMessage>()
        for (message in raw) {
            if (message.number !in byNumber) byNumber[message.number] = message
        }
        if (byNumber.isEmpty()) return null

        // 当前分支：既用来判定"哪一条是选中的版本"，也用来取首条提问当标题
        val branch = linearize(raw, session.currentMessageId)
        if (branch.isEmpty()) return null
        val branchIds = branch.map { it.number }.toHashSet()

        /*
         * 按父节点分组，顺带把"断链"的行筛掉：`parent_id` 指向不存在的消息时，
         * 那一支永远连不到根 —— 留着它只会在界面里变成一段谁也不认识的孤儿。
         */
        val childrenOf = LinkedHashMap<Long?, MutableList<Long>>()
        for ((id, message) in byNumber) {
            val rawParent = message.parentNumber
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

        val title = session.title?.takeIf { it.isNotBlank() }
            ?: branch.firstOrNull { it.role == "USER" }
                ?.request
                ?.take(FallbackTitleChars)
                ?.takeIf { it.isNotBlank() }
            ?: naming.conversationFallbackTitle()

        val insertedAt = parseIso(session.insertedAt) ?: System.currentTimeMillis()
        val updatedAt = parseIso(session.updatedAt) ?: insertedAt

        val mapped = ordered.map { number ->
            val message = byNumber.getValue(number)
            val role = if (message.role == "USER") "user" else "assistant"
            val content = if (role == "user") {
                message.request
            } else {
                // 模板正文（超时 / 内容过滤那类）也是正文：我们只透传，不做取舍
                message.response + message.template
            }
            val elapsedSecs = message.thinks.sumOf { it.second }
            // 字节落盘，单条消息的附件也不再需要配额（行里只有文件名）
            val images = message.imagePaths.mapNotNull(imageOf)

            BackupMessage(
                id = "$ID_PREFIX$sessionId-$number",
                parentId = message.parentNumber
                    ?.takeIf { byNumber.containsKey(it) }
                    ?.let { "$ID_PREFIX$sessionId-$it" }
                    ?: "",
                variantIndex = variantIndexOf[number] ?: 0,
                active = number in selectedNumbers,
                role = role,
                content = content,
                attachments = images,
                reasoning = message.thinks.joinToString("\n") { it.first }.takeIf { it.isNotBlank() },
                status = statusOf(message.status),
                model = null,
                thinkingMs = elapsedSecs.takeIf { it > 0L }?.times(1000L),
                // 导出的历史不参与续写：没有服务端需要的签名与工具块
                stopReason = null,
                createdAt = parseIso(message.insertedAt) ?: insertedAt,
            )
        }

        return BackupConversation(
            id = "$ID_PREFIX$sessionId",
            title = title,
            mode = "CREATIVE",
            provider = "DEEPSEEK",
            model = "",
            pinned = session.pinned,
            /*
             * 归档里只有"是否置顶"，没有置顶时间。我们的列表按 `pinnedAt` 排序，
             * 留 null 会让导入的置顶会话排到所有置顶项的**最后** ——
             * 用户看到的就是"置顶那一组里少了一个、顺序也乱了"。
             * 拿更新时间顶上：至少与 DeepSeek 里"最近聊的在前"的观感一致。
             */
            pinnedAt = if (session.pinned) updatedAt else null,
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
    internal fun linearize(messages: List<ParsedMessage>, currentId: Long?): List<ParsedMessage> {
        val byId = HashMap<Long, ParsedMessage>()
        for (message in messages) byId[message.number] = message
        if (byId.isEmpty()) return emptyList()

        var cursor = currentId?.takeIf { byId.containsKey(it) } ?: byId.keys.maxOrNull()
        val chain = mutableListOf<ParsedMessage>()
        val guard = HashSet<Long>()
        while (cursor != null) {
            val message = byId[cursor] ?: break
            // 环保护：坏数据不该把导入卡死
            if (!guard.add(cursor)) break
            chain += message
            cursor = message.parentNumber
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

    /**
     * 读一张图片并转成我们的附件。
     *
     * 宽高从字节里现算（[imageSizeOf]），不信任导出的元数据：归档里 `width` / `height`
     * 允许为 null，而且同一个文件在不同版本里写没写也不一定。
     * MIME 按**真实内容**取（[ApiImage]）：服务端只看内容，写错了不会报错，
     * 只会在发送时被拒。
     */
    private fun readImage(
        zip: ZipFile,
        path: String,
        naming: ExternalBackup.ImportNaming,
    ): Attachment? {
        val entry = zip.getEntry(path) ?: return null
        /*
         * 有界读取，但只挡"异常的单张"（[MaxSingleImageBytes]，64MB）。
         *
         * 早先这里有两个配额（单张 6MB、单次导入 24MB），那是**内联存库**时代的
         * 妥协：附件与正文在同一行，而 CursorWindow 只有 2MB。字节落盘之后
         * 行里只剩文件名，这两个配额就没有存在理由了 —— 现在只留一个防呆上限，
         * 挡住"条目本身坏掉、size 是天文数字"的情况。
         */
        val bytes = runCatching { zip.readBounded(entry, MaxSingleImageBytes) }.getOrNull()
            ?: return null
        if (bytes.isEmpty()) return null
        val size = imageSizeOf(bytes)
        val mime = ApiImage.mediaTypeOf(bytes, declared = null)
        // 落盘：字节进 filesDir，行里只留文件名（见 AttachmentFiles）
        val stored = naming.storeImage?.invoke(bytes, mime)
        return Attachment(
            kind = Attachment.Kind.IMAGE,
            name = size?.let { naming.imageName(it.first, it.second) } ?: naming.imageFallbackName(),
            mime = mime,
            data = if (stored != null) "" else Base64.getEncoder().encodeToString(bytes),
            width = size?.first ?: 0,
            height = size?.second ?: 0,
            path = stored,
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

    private fun JsonObject.arrOrEmpty(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

    private fun JsonObject.longOrZero(key: String): Long = long(key) ?: 0L

    /** 单张图片上限：再大的塞进数据库只会拖垮列表滚动。 */
    private const val MaxSingleImageBytes = 64 * 1024 * 1024

    /**
     * 单个会话 JSON 的上限（24 MB）。超过就跳过这一条会话，并在导入结果里说清楚。
     *
     * 为什么可以从 8MB 放宽回 24MB：解析方式换成了"逐条消息切片"（见 [parseSession]），
     * 峰值只与最大的单条消息有关，不再随会话条数成倍膨胀。
     *
     * 为什么还要有上限：归档是用户从别处拿来的文件，一个坏条目就能是几百兆。
     */
    private const val MaxSessionJsonBytes = 24 * 1024 * 1024

    /** 没有标题的会话用首条提问的前若干字当标题。 */
    private const val FallbackTitleChars = 24
}

/**
 * 极简 JSON 切片器：**只切原文，不建树**。
 *
 * 与 kotlinx 的分工很清楚：
 * - 这里负责"跳过 / 切出一段原文"（对象字段、数组元素），代价是零对象分配；
 * - 真正解析仍交给 kotlinx —— 只交给它**一条消息**或**几个标量**那么大的一块。
 *
 * 为什么需要它见 [DeepSeekHistory.parseSession]：整棵树建出来的内存是原文的 2~3 倍，
 * 长会话因此没法导入。这个类只认 JSON 文法的骨架（字符串、括号、逗号），
 * 足够切分，不会误解内容。
 */
internal class JsonCursor(private val text: String) {

    private var index = 0

    fun at(position: Int): JsonCursor {
        index = position
        return this
    }

    /** 遍历一个对象的字段，回调拿到字段名与值的原文切片（`[first, last]` 闭区间）。 */
    fun forEachField(block: (name: String, value: IntRange) -> Unit) {
        expect('{')
        skipWhitespace()
        if (text.getOrNull(index) == '}') {
            index++
            return
        }
        while (true) {
            val name = rawString()
            expect(':')
            val value = sliceValue()
            block(name, value)
            skipWhitespace()
            when (text.getOrNull(index)) {
                ',' -> index++
                '}' -> {
                    index++
                    return
                }

                else -> fail("对象字段之间缺少逗号")
            }
        }
    }

    /** 遍历一个数组的元素，回调拿到每个元素的原文切片（闭区间）。 */
    fun forEachElement(block: (IntRange) -> Unit) {
        expect('[')
        skipWhitespace()
        if (text.getOrNull(index) == ']') {
            index++
            return
        }
        while (true) {
            block(sliceValue())
            skipWhitespace()
            when (text.getOrNull(index)) {
                ',' -> index++
                ']' -> {
                    index++
                    return
                }

                else -> fail("数组元素之间缺少逗号")
            }
        }
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        if (text.getOrNull(index) != expected) fail("期望 '$expected'")
        index++
    }

    /** 读一个 JSON 字符串，返回**未转义**之前的原文（不含引号）。字段名都是 ASCII，够用。 */
    private fun rawString(): String {
        skipWhitespace()
        if (text.getOrNull(index) != '"') fail("期望字符串")
        val start = ++index
        var escaped = false
        while (index < text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> {
                    val raw = text.substring(start, index)
                    index++
                    return raw
                }
            }
            index++
        }
        fail("字符串没有闭合")
    }

    private fun sliceValue(): IntRange {
        skipWhitespace()
        val start = index
        when (text.getOrNull(index)) {
            null -> fail("意外结束")
            '"' -> rawString()
            '{', '[' -> skipContainer()
            else -> skipLiteral()
        }
        return start..(index - 1)
    }

    /** 跳过一个完整的对象/数组（含嵌套，字符串里的括号不算）。 */
    private fun skipContainer() {
        var depth = 0
        var inString = false
        var escaped = false
        while (index < text.length) {
            val char = text[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }

                char == '"' -> inString = true
                char == '{' || char == '[' -> depth++
                char == '}' || char == ']' -> {
                    depth--
                    if (depth == 0) {
                        index++
                        return
                    }
                }
            }
            index++
        }
        fail("括号没有闭合")
    }

    /** 数字 / true / false / null：一直吃到分隔符为止。 */
    private fun skipLiteral() {
        while (index < text.length) {
            when (text[index]) {
                ',', '}', ']', ' ', '\n', '\r', '\t' -> return
                else -> index++
            }
        }
    }

    private fun skipWhitespace() {
        while (index < text.length) {
            when (text[index]) {
                ' ', '\n', '\r', '\t' -> index++
                else -> return
            }
        }
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException("JSON 切片失败：$message（位置 $index）")
}
