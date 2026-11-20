package com.simplechat.app.data.import

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.ApiImage
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.BackupConversation
import com.simplechat.app.data.BackupMessage
import com.simplechat.app.data.ImportFailure
import com.simplechat.app.net.ReplyBlock
import java.io.Reader
import java.io.StringReader
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.io.FilterReader
import java.io.FilterInputStream
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
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
 * ### 解析方式：两遍流式，**没有大小上限**
 *
 * 一个会话 JSON 里，正文占了绝大部分，而"怎么摆"（父子、变体、当前分支、标题）
 * 只取决于每条消息的几个标量。所以：
 *
 * 1. **第一遍**只读标量与每条消息的 `id / parent_id / role`（外加极少数几条标题候选
 *    的开头几个字），建树、算变体与选中分支；
 * 2. **第二遍**重新流式读同一个条目，逐条解析正文 → 产出 [BackupMessage]，
 *    由调用方**边读边写库**（见 [ImportSession]）。
 *
 * 两遍都走 [JsonStream]，峰值只有"当前这一条消息"，与会话总大小无关。
 * v1.1.3 之前是"整份读成 String + 24MB 上限"，超上限的长会话只能整条丢掉 ——
 * 那正是"我最长的那几个对话导入不了"的来源。
 *
 * ### 刻意不导的内容
 *
 * - `TIP` / `CONTENT_RISK` / `PARTIAL_FILE_READ`：展示提示，本地没有对应的消息片段。
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

    /** 顶层只认这几个标量，其余字段（将来新增的）跳过 —— 不猜格式。 */
    private val HeaderFields = setOf(
        "id", "title", "pinned", "current_message_id", "inserted_at", "updated_at",
    )

    /**
     * 第一遍最多记几条"提问开头"。
     *
     * 只为一件事：会话没有标题时，拿**当前分支的第一条提问**当标题。
     * 分支要等树建完才知道，所以不能只记第一条 —— 但也不必全记：
     * 几十条提问的开头（每条几百字节）足够覆盖正常数据，再多的会话
     * 本来就有官方标题（`title` 字段）。
     */
    private const val TitleCandidateBudget = 32

    /** 标题候选保留的字符数 —— 比最终标题长一点，够 trim/截断用。 */
    private const val TitleCandidateChars = 64

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 打开一份归档：会话是**懒**产出的，[expected] 是归档声明的会话数。
     *
     * 为什么把"声明数"带出来：导入结果要对账（见 `ImportReport`）。
     * 归档里有几条、进来几条，用户一眼能看出有没有丢 —— 这是导入链路的底线。
     */
    internal class DeepSeekStream internal constructor(
        private val zip: ZipFile,
        private val entries: List<ZipEntry>,
        private val indexTitles: Map<String, String?>,
        private val naming: ExternalBackup.ImportNaming,
        /** 归档声明的会话数：文件条目 + 索引里有但文件缺失的那些。 */
        override val expected: Int,
        override val indexVerified: Boolean,
        /** 所有会话条目的（未压缩）字节总数，只用来算进度。 */
        private val totalBytes: Long,
        private val onProgress: (Float) -> Unit,
        private val onFailure: (ImportFailure) -> Unit,
        private val onImageDropped: () -> Unit,
    ) : ImportSessionSource {
        /**
         * 逐条消费会话。**必须在 `ZipFile` 关掉之前跑完**（正文是第二遍现读的）。
         *
         * 每条会话的 [ImportSession.forEachMessage] 也要在 [block] 返回前消费完 ——
         * 同一个 ZIP 条目只在这一小段时间里被读第二遍。
         */
        override suspend fun forEachSession(block: suspend (ImportSession) -> Unit) {
            var doneBytes = 0L
            entries.forEach { entry ->
                val size = entry.size.coerceAtLeast(1L)
                var lastFraction = 0f
                /*
                 * 进度按**字节**算，而不是按"第几个会话"。
                 *
                 * 按会话数算的话，一份"1 个 60MB 的超长会话 + 9 个几十 KB 的小会话"
                 * 的归档，进度条会在第一个会话上停 60% 不动 —— 用户以为卡死了。
                 * 按字节算，第一个会话期间进度也在爬，只是慢。
                 */
                fun report(fractionInSession: Float) {
                    lastFraction = maxOf(lastFraction, fractionInSession.coerceIn(0f, 1f))
                    val done = doneBytes + (size * lastFraction).toLong()
                    onProgress((done.toFloat() / totalBytes).coerceIn(0f, 1f))
                }
                report(0f)
                val sessionId = sessionIdOf(entry.name)
                val outcome = try {
                    openSession(entry, sessionId, ::report)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    android.util.Log.w(ImportLogTag, "会话 $sessionId 读取失败", error)
                    SessionOutcome.Failed(if (error is OutOfMemoryError) ImportFailure.Reason.INSUFFICIENT_MEMORY else ImportFailure.Reason.UNREADABLE)
                }
                when (outcome) {
                    is SessionOutcome.Ready -> block(outcome.session)
                    is SessionOutcome.Failed -> onFailure(
                        ImportFailure(
                            title = indexTitles[sessionId],
                            reference = sessionId,
                            reason = outcome.reason,
                        ),
                    )
                }
                report(1f)
                doneBytes += size
            }
        }

        private sealed interface SessionOutcome {
            class Ready(val session: ImportSession) : SessionOutcome
            class Failed(val reason: ImportFailure.Reason) : SessionOutcome
        }

        private suspend fun openSession(
            entry: ZipEntry,
            sessionId: String,
            onFraction: (Float) -> Unit,
        ): SessionOutcome {
            // 第一遍：只建树，不留正文。两遍里它大约占前 1/4 的工作量
            val scan = entry.reader(zip) { bytes -> onFraction(Pass1Share * bytes / entry.size.coerceAtLeast(1).toFloat()) }
                .use { reader -> scanSession(reader) }
            onFraction(Pass1Share)
            val planned = planSession(scan, sessionId, naming)
                ?: return SessionOutcome.Failed(ImportFailure.Reason.BROKEN_TREE)
            val createdImages = linkedSetOf<String>()
            return SessionOutcome.Ready(
                ImportSession(header = planned.header, expectedMessages = planned.kept.size, repairedMessages = planned.repairs,
                    discard = { createdImages.forEach { naming.discardImage?.invoke(it) } }) { sink ->
                    // 第二遍：读一条、写一条（sink 里是带事务的数据库写入）
                    emitMessages(entry, planned, sink, onFraction, createdImages)
                },
            )
        }

        /**
         * 第二遍流式产出消息。**读一条、写一条**：`sink` 是 `suspend` 的，
         * 调用方在它里面写库，所以内存里永远只有一条消息。
         */
        private suspend fun emitMessages(
            entry: ZipEntry,
            planned: SessionPlan,
            sink: suspend (BackupMessage) -> Unit,
            onFraction: (Float) -> Unit,
            createdImages: MutableSet<String>,
        ) {
            val sessionId = planned.header.id.removePrefix(ID_PREFIX)
            val emitted = HashSet<Long>()
            val total = planned.kept.size.coerceAtLeast(1)

            /*
             * 图片按 `export_path` **用到才读**，并且同一张图只读一次：
             * 一条消息里引用两次同一张图（或两个分支引用同一张）时，既不该读两遍，
             * 也不该把"没取回"算两次 —— 那会让结果页的图片数虚高。
             */
            val images = HashMap<String, Attachment?>()
            suspend fun imageOf(ref: ImageRef): Attachment? {
                val path = ref.path
                if (images.containsKey(path)) return images[path]
                val image = readImage(path, ref.name)
                image?.path?.let { createdImages += it }
                if (image == null) onImageDropped()
                images[path] = image
                return image
            }

            entry.reader(zip) { bytes -> onFraction(Pass1Share + (1 - Pass1Share) * bytes / entry.size.coerceAtLeast(1).toFloat()) }.use { reader ->
                val stream = JsonStream(reader)
                stream.beginObject()
                while (stream.hasNextField()) {
                    if (stream.fieldName() != "messages") {
                        stream.skipValue()
                        continue
                    }
                    if (stream.valueKind() == 'n') { stream.skipValue(); continue }
                    stream.beginArray()
                    while (stream.hasNextArrayElement()) {
                        val parsed = readDeepSeekMessage(stream)
                        check(parsed.number in planned.kept) { "Unplanned message ${parsed.number}" }
                        check(emitted.add(parsed.number)) { "Duplicate message ${parsed.number}" }
                        // 图片在这里**先读完**：toBackupMessage 保持纯函数，
                        // 读 ZIP 条目（阻塞 IO）只发生在这个挂起上下文里
                        val attachments = parsed.images.mapNotNull { imageOf(it) }
                        val message = checkNotNull(toBackupMessage(parsed, planned, sessionId, attachments))
                        sink(message)
                        if (emitted.size % ProgressEveryMessages == 0) {
                            onFraction(Pass1Share + (1f - Pass1Share) * emitted.size / total)
                        }
                    }
                }
            }
            check(emitted.size == planned.kept.size) { "Message reconciliation failed: ${emitted.size}/${planned.kept.size}" }
            onFraction(1f)
        }

        /**
         * 读一张图片并转成我们的附件。
         *
         * 宽高从字节里现算（[imageSizeOf]），不信任导出的元数据：归档里 `width` / `height`
         * 允许为 null，而且同一个文件在不同版本里写没写也不一定。
         * MIME 按**真实内容**取（[ApiImage]）：服务端只看内容，写错了不会报错，
         * 只会在发送时被拒。
         */
        private suspend fun readImage(path: String, name: String?): Attachment? {
            val entry = zip.getEntry(path) ?: return null
            naming.storeImageStream?.let { store ->
                val activeContext = currentCoroutineContext()
                zip.getInputStream(entry).buffered(BufferChunk).use { input ->
                    val prefixBuffer = ByteArray(BufferChunk)
                    var used = 0
                    while (used < prefixBuffer.size) {
                        activeContext.ensureActive()
                        val count = input.read(prefixBuffer, used, prefixBuffer.size - used)
                        if (count < 0) break
                        used += count
                    }
                    if (used == 0) return null
                    val prefix = if (used == prefixBuffer.size) prefixBuffer else prefixBuffer.copyOf(used)
                    val size = imageSizeOf(prefix)
                    val mime = ApiImage.mediaTypeOf(prefix, declared = null)
                    val combined = SequenceInputStream(ByteArrayInputStream(prefix), input)
                    val cancellable = object : FilterInputStream(combined) {
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            activeContext.ensureActive()
                            return super.read(bytes, offset, length)
                        }
                    }
                    val stored = store(cancellable, mime)
                    return Attachment(
                        kind = Attachment.Kind.IMAGE,
                        name = name ?: size?.let { naming.imageName(it.first, it.second) } ?: naming.imageFallbackName(),
                        mime = mime, data = "", width = size?.first ?: 0, height = size?.second ?: 0, path = stored,
                    )
                }
            }
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
                name = name ?: size?.let { naming.imageName(it.first, it.second) } ?: naming.imageFallbackName(),
                mime = mime,
                data = if (stored != null) "" else Base64.getEncoder().encodeToString(bytes),
                width = size?.first ?: 0,
                height = size?.second ?: 0,
                path = stored,
            )
        }
    }

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
        sessionEntriesOf(zip).isNotEmpty()
    }.getOrDefault(false)

    private fun sessionEntriesOf(zip: ZipFile): List<ZipEntry> = zip.entries().asSequence()
        .filter { !it.isDirectory && it.name.startsWith(SESSIONS) && it.name.endsWith(".json") }
        // 图片目录 `chat_sessions/{id}_files/` 里的东西不在这里（它们不叫 .json）
        .filter { !it.name.removePrefix(SESSIONS).contains('/') }
        .toList()

    /**
     * 打开归档，拿到**懒**产出的会话序列。
     *
     * 调用方必须在 `ZipFile` 关闭之前把会话消费完（第二遍读正文还在用这个 ZIP）。
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
        parsedManifest: JsonObject? = null,
    ): DeepSeekStream? {
        val manifest = parsedManifest ?: readJson(zip, MANIFEST) ?: run {
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
        val rawVersion = manifest["export_format_version"] ?: return null
        val version = (rawVersion as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
            ?: throw AppTextException(R.string.err_unrecognised_file)
        if (version != FORMAT_VERSION) {
            throw AppTextException(R.string.err_deepseek_version, version, FORMAT_VERSION)
        }

        /*
         * 索引有两个用途：顺序（用户在 DeepSeek 里看到的顺序）与**标题**。
         *
         * 标题的用处不止排序：某条会话的文件缺失/损坏时，索引里还留着它的名字 ——
         * 结果页就能说"少了《示例会话》"，而不是干巴巴一个"1 个未能导入"。
         *
         * ⚠️ 索引文件在**真实导出**里是一个 JSON **数组**（`[{id,title,pinned,
         * message_count,inserted_at,updated_at}, …]`），而我们最早的测试样本
         * 用的是 `{"sessions":[…]}`。只认后者时，真实归档的索引从来没被读到：
         * 顺序退化成 ZIP 条目顺序、缺失条目的对账也整个失效（两件事都不报错，
         * 只是"看起来没问题"）。两种形状都认。
         */
        val indexRead = readIndex(zip)
        val indexEntries = indexRead.entries
        val indexIds = indexEntries.mapNotNull { it.str("id") }.distinct()
        val indexTitles = indexEntries
            .mapNotNull { entry -> entry.str("id")?.let { id -> id to entry.str("title") } }
            .toMap()
        val rank = indexIds.withIndex().associate { (position, id) -> id to position }

        val sessionEntries = sessionEntriesOf(zip)
            .sortedBy { entry -> rank[sessionIdOf(entry.name)] ?: Int.MAX_VALUE }
        // 总字节数只用于进度；给 0 的话下面会退化成"按会话数"，这里至少保底 1
        val totalBytes = sessionEntries.sumOf { it.size.coerceAtLeast(1L) }.coerceAtLeast(1L)

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

        return DeepSeekStream(
            zip = zip,
            entries = sessionEntries,
            indexTitles = indexTitles,
            naming = naming,
            expected = expected,
            indexVerified = indexRead.verified,
            totalBytes = totalBytes,
            onProgress = onProgress,
            onFailure = onFailure,
            onImageDropped = onImageDropped,
        )
    }

    private fun sessionIdOf(entryName: String): String =
        entryName.removePrefix(SESSIONS).removeSuffix(".json")

    private suspend fun ZipEntry.reader(zip: ZipFile, onBytes: (Long) -> Unit = {}): Reader {
        val activeContext = currentCoroutineContext()
        val input = object : FilterInputStream(zip.getInputStream(this)) {
            var consumed = 0L
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                activeContext.ensureActive()
                val count = super.read(bytes, offset, length)
                if (count > 0) { consumed += count; onBytes(consumed) }
                return count
            }
        }
        return object : FilterReader(input.reader(Charsets.UTF_8)) {
            override fun read(chars: CharArray, offset: Int, length: Int): Int {
                activeContext.ensureActive()
                return super.read(chars, offset, length)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  第一遍：只读标量与树骨架
    // ══════════════════════════════════════════════════════

    /** 一条会话第一遍读出来的东西：顶层标量原文 + 每条消息的骨架。 */
    internal class SessionScan(
        val header: Map<String, String>,
        val messages: List<LightMessage>,
    )

    /**
     * 一条消息的骨架。
     *
     * [requestHead] 只在 [TitleCandidateBudget] 条以内的消息上出现，且已截断 ——
     * 它只为"没有标题时拿首条提问当标题"存在，不是正文。
     */
    internal class LightMessage(
        val number: Long,
        val parentNumber: Long?,
        val role: String?,
        val requestHead: String?,
    )

    /** 第一遍：流式扫一遍会话，只留下"建树/算标题"需要的东西。 */
    internal fun scanSession(reader: Reader): SessionScan {
        val header = LinkedHashMap<String, String>()
        val messages = ArrayList<LightMessage>()
        val stream = JsonStream(reader)
        var headBudget = TitleCandidateBudget
        stream.beginObject()
        while (stream.hasNextField()) {
            when (val name = stream.fieldName()) {
                "messages" -> {
                    if (stream.valueKind() == 'n') { stream.skipValue(); continue }
                    stream.beginArray()
                    while (stream.hasNextArrayElement()) {
                        // 一次只切一条消息的原文，解析完立刻丢
                        val light = readLightMessage(stream, captureHead = headBudget > 0)
                            ?: error("Message has no supported numeric id")
                        messages += light
                        if (light.requestHead != null) headBudget--
                    }
                }

                in HeaderFields -> header[name] = stream.captureValue()
                else -> stream.skipValue()
            }
        }
        return SessionScan(header, messages)
    }

    private fun readLightMessage(stream: JsonStream, captureHead: Boolean): LightMessage? {
        var number: Long? = null
        var parent: Long? = null
        var role: String? = null
        var head: String? = null
        stream.beginObject()
        while (stream.hasNextField()) {
            when (val name = stream.fieldName()) {
                "id" -> number = stream.textValue()?.toLongOrNull()
                "parent_id" -> parent = stream.textValue()?.toLongOrNull()
                "role" -> role = stream.textValue()
                "fragments" -> if (captureHead && head == null) {
                    head = readRequestHead(stream)
                } else {
                    stream.skipValue()
                }

                else -> stream.skipValue()
            }
        }
        val id = number ?: return null
        return LightMessage(id, parent, role, head)
    }

    /** 在 `fragments` 数组里找第一条 `REQUEST` 片段，取它正文的开头几个字。 */
    private fun readRequestHead(stream: JsonStream): String? {
        var head: String? = null
        if (stream.valueKind() == 'n') { stream.skipValue(); return null }
        stream.beginArray()
        while (stream.hasNextArrayElement()) {
            if (head != null) {
                // 已经拿到第一条提问了，剩下的片段只需要走一遍结构
                stream.skipValue()
                continue
            }
            var type: String? = null
            var content: String? = null
            stream.beginObject()
            while (stream.hasNextField()) {
                when (stream.fieldName()) {
                    "type" -> type = stream.textValue()
                    "content" -> content = stream.textValue(TitleCandidateChars)
                    else -> stream.skipValue()
                }
            }
            if (type == "REQUEST") head = content?.takeIf { it.isNotBlank() }
        }
        return head
    }

    // ══════════════════════════════════════════════════════
    //  树：变体号 / 当前分支 / 标题
    // ══════════════════════════════════════════════════════

    /**
     * 一条会话的**计划**：哪些消息留下、各自的变体号、哪条是选中的版本、标题是什么。
     *
     * 这一层是纯函数（有单测）：导入最容易错、错了又最难看出来的就是这些关系 ——
     * 变体号错一条，界面上 `‹ 2/3 ›` 就指错人；选中分支错一条，打开会话看到的
     * 就不是用户当时那一版。
     */
    internal class SessionPlan(
        val header: BackupConversation,
        /** 保留下来的消息 → 父亲号码（null = 根）。 */
        val kept: Map<Long, Long?>,
        val variantIndex: Map<Long, Int>,
        /** 被选中的版本（每个父亲下一条）。 */
        val active: Set<Long>,
        /** 会话自身的创建时间（消息缺时间时兜底）。 */
        val createdAt: Long,
        val repairs: Int = 0,
    )

    internal fun planSession(
        scan: SessionScan,
        sessionId: String,
        naming: ExternalBackup.ImportNaming,
    ): SessionPlan? {
        val head = headerOf(scan.header) ?: JsonObject(emptyMap())

        val byNumber = LinkedHashMap<Long, LightMessage>()
        for (message in scan.messages) {
            require(message.number !in byNumber) { "Duplicate message id: ${message.number}" }
            byNumber[message.number] = message
        }
        var repairs = 0
        val parents = LinkedHashMap<Long, Long?>()
        for ((id, message) in byNumber) {
            parents[id] = message.parentNumber?.takeIf { it in byNumber }
            if (message.parentNumber != null && parents[id] == null) repairs++
        }
        // Preserve orphaned and cyclic branches. Break only the edge that prevents reaching a root.
        val resolved = HashSet<Long>()
        for (id in byNumber.keys) {
            val chain = LinkedHashSet<Long>()
            var cursor: Long? = id
            while (cursor != null && cursor !in resolved) {
                if (!chain.add(cursor)) { parents[cursor] = null; repairs++; break }
                cursor = parents[cursor]
            }
            resolved += chain
        }

        /*
         * ★ **整棵树都导，不只当前分支。**
         *
         * 归档里每条消息都带 `parent_id`（官方 README 也写明"按 id / parent_id 还原"），
         * 所以分支本来就在数据里 —— 早先只导当前分支是**我们的取舍**，不是包的限制。
         * 我们的树模型（`parentId` + `variantIndex` + `active`）能原样表达它：
         * 同父下按 id 升序编 `variantIndex`，当前正看着的那条标 `active`；
         * 不在当前路径上的分支，按官方规则"默认选中 id 最大的子消息"。
         */
        val childrenOf = LinkedHashMap<Long?, MutableList<Long>>()
        for ((id, message) in byNumber) {
            val parent = parents[id]
            childrenOf.getOrPut(parent) { mutableListOf() } += id
        }
        childrenOf.values.forEach { it.sort() }

        // BFS 出全树顺序（父在子前；同时把"连不到根"的孤儿筛掉，与被跳过的断链一致）
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
        check(ordered.size == byNumber.size) { "Not all source messages are reachable" }

        // 当前分支：既用来判定"哪一条是选中的版本"，也用来取首条提问当标题
        val branchIds = branchOf(byNumber, head.long("current_message_id")).toHashSet()

        // 同父下的序号 = 变体号；选中的那条 = 当前分支上的那条，否则 id 最大的那条
        val variantIndexOf = HashMap<Long, Int>()
        val selectedNumbers = HashSet<Long>()
        for ((_, siblings) in childrenOf) {
            siblings.forEachIndexed { index, id -> variantIndexOf[id] = index }
            selectedNumbers += siblings.firstOrNull { it in branchIds } ?: siblings.last()
        }

        val title = head.str("title")?.takeIf { it.isNotBlank() }
            ?: scan.messages.asSequence()
                .filter { it.role == "USER" && it.number in branchIds && !it.requestHead.isNullOrBlank() }
                .map { it.requestHead!!.trim().take(FallbackTitleChars) }
                .firstOrNull { it.isNotEmpty() }
            ?: naming.conversationFallbackTitle()

        val insertedAt = parseIso(head.str("inserted_at")) ?: System.currentTimeMillis()
        val updatedAt = parseIso(head.str("updated_at")) ?: insertedAt

        val kept = LinkedHashMap<Long, Long?>(ordered.size)
        for (number in ordered) kept[number] = parents[number]

        val pinned = head.str("pinned") == "true"
        val conversation = BackupConversation(
            id = "$ID_PREFIX$sessionId",
            title = title,
            mode = "CREATIVE",
            provider = "DEEPSEEK",
            model = "",
            pinned = pinned,
            /*
             * 归档里只有"是否置顶"，没有置顶时间。我们的列表按 `pinnedAt` 排序，
             * 留 null 会让导入的置顶会话排到所有置顶项的**最后** ——
             * 用户看到的就是"置顶那一组里少了一个、顺序也乱了"。
             * 拿更新时间顶上：至少与 DeepSeek 里"最近聊的在前"的观感一致。
             */
            pinnedAt = if (pinned) updatedAt else null,
            createdAt = insertedAt,
            updatedAt = updatedAt,
            messages = emptyList(),
        )

        return SessionPlan(
            header = conversation,
            kept = kept,
            variantIndex = variantIndexOf,
            active = selectedNumbers,
            createdAt = insertedAt,
            repairs = repairs,
        )
    }

    /**
     * 把消息树线性化成"当前这一条分支"（只用来判选中版本与标题）。
     *
     * 从 `current_message_id` 沿 `parent_id` 往上走，再反转 —— 用户当时看到的就是这条链。
     * `current_message_id` 缺失或不认识时退回"id 最大的那条"（导出工具都会写它，
     * 这里只是不让数据异常导致整条会话丢失）。
     */
    internal fun branchOf(byNumber: Map<Long, LightMessage>, currentId: Long?): List<Long> {
        if (byNumber.isEmpty()) return emptyList()
        var cursor = currentId?.takeIf { byNumber.containsKey(it) } ?: byNumber.keys.maxOrNull()
        val chain = ArrayList<Long>()
        val guard = HashSet<Long>()
        while (cursor != null) {
            val message = byNumber[cursor] ?: break
            // 环保护：坏数据不该把导入卡死
            if (!guard.add(cursor)) break
            chain += cursor
            cursor = message.parentNumber
        }
        chain.reverse()
        return chain
    }

    // ══════════════════════════════════════════════════════
    //  第二遍：一条消息 → BackupMessage（纯函数）
    // ══════════════════════════════════════════════════════

    /**
     * 一条消息 → 我们能存/能显示的 [BackupMessage]。
     *
     * 纯函数（图片已由调用方读好、按序传进来），有单测 —— 正文/思考/状态/父子的映射
     * 错一处，表现是"导入后这条回复内容不对、或者版本数不对"，从界面上极难反推。
     */
    internal fun toBackupMessage(
        message: ParsedMessage,
        planned: SessionPlan,
        sessionId: String,
        attachments: List<Attachment>,
    ): BackupMessage? {
        if (message.number !in planned.kept) return null
        val role = if (message.role == "USER") "user" else "assistant"
        val content = if (role == "user") {
            message.request
        } else {
            // 模板正文（超时 / 内容过滤那类）也是正文：我们只透传，不做取舍
            if (message.template.isEmpty()) message.response else message.response + message.template
        }
        val elapsedMs = message.thinks.sumOf { it.second }
        return BackupMessage(
            id = "$ID_PREFIX$sessionId-${message.number}",
            parentId = planned.kept[message.number]
                ?.let { "$ID_PREFIX$sessionId-$it" }
                ?: "",
            variantIndex = planned.variantIndex[message.number] ?: 0,
            active = message.number in planned.active,
            role = role,
            content = content,
            // 字节落盘，单条消息的附件也不再需要配额（行里只有文件名）
            attachments = attachments,
            reasoning = (if (message.thinks.size == 1) message.thinks[0].first
                else message.thinks.joinToString("\n") { it.first }).takeIf { it.isNotBlank() },
            status = statusOf(message.status),
            model = null,
            thinkingMs = elapsedMs.takeIf { it > 0L },
            timeline = message.timeline,
            stopReason = when (message.status) {
                "CONTENT_FILTER" -> "refusal"
                "TIMEOUT" -> "timeout"
                "CONTEXT_LENGTH_EXCEEDED" -> "context_length_exceeded"
                else -> null
            },
            createdAt = parseIso(message.insertedAt) ?: planned.createdAt,
        )
    }

    /**
     * 一条消息的**紧凑**形式（第二遍解析出来、立刻映射，不留 `JsonObject` 树）。
     *
     * 字段刻意只留"进数据库/进界面"需要的那几样：多余的字段留在原文里不发散，
     * 这样几十万条消息的会话也不会因为解析而膨胀。
     */
    internal data class ImageRef(val path: String, val name: String?)

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
        /** 思考片段：正文 + 耗时（毫秒）。 */
        val thinks: List<Pair<String, Long>>,
        /** 图片原件的 ZIP 条目名（`export_path`），按出现顺序。 */
        val images: List<ImageRef>,
        val timeline: List<ReplyBlock>,
    )

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

    // ══════════════════════════════════════════════════════
    //  读取工具
    // ══════════════════════════════════════════════════════

    /**
     * 有界读取：超过 [limit] 直接放弃（返回 null），不会先把整块分配出来。
     *
     * 这是修 OOM 的关键 —— `readBytes()` 是按条目大小一次性分配的，
     * 而归档里完全可能存在几十兆的图片，堆里根本放不下。
     */
    private fun ZipFile.readBounded(entry: ZipEntry, limit: Int): ByteArray? {
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
        val text = runCatching { zip.readBounded(entry, 64 * 1024)?.decodeToString() }.getOrNull()
            ?: return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    /**
     * 读会话索引。**数组**（真实导出）与 `{"sessions":[…]}`（早期样本）两种形状都认。
     *
     * 读不出来就返回空表：索引只用于"顺序 + 对账"，缺了不影响导入本身 ——
     * 不能因为一个可选文件把整份归档判死。
     */
    private class IndexRead(val entries: List<JsonObject>, val verified: Boolean)

    private fun readIndex(zip: ZipFile): IndexRead {
        val entry = zip.getEntry(INDEX) ?: return IndexRead(emptyList(), false)
        return try {
            val entries = ArrayList<JsonObject>()
            zip.getInputStream(entry).reader(Charsets.UTF_8).use { reader ->
                val stream = JsonStream(reader)
                fun readEntries() {
                    stream.beginArray()
                    while (stream.hasNextArrayElement()) {
                        val fields = linkedMapOf<String, String>()
                        stream.beginObject()
                        while (stream.hasNextField()) {
                            val name = stream.fieldName()
                            if (name == "id" || name == "title") fields[name] = stream.captureValue()
                            else stream.skipValue()
                        }
                        entries += checkNotNull(headerOf(fields))
                    }
                }
                when (stream.valueKind()) {
                    '[' -> readEntries()
                    '{' -> {
                        var found = false
                        stream.beginObject()
                        while (stream.hasNextField()) {
                            if (stream.fieldName() == "sessions") { readEntries(); found = true }
                            else stream.skipValue()
                        }
                        check(found) { "Missing sessions array" }
                    }
                    else -> error("Unsupported index")
                }
            }
            IndexRead(entries, true)
        } catch (error: Exception) {
            IndexRead(emptyList(), false)
        }
    }

    private fun headerOf(raw: Map<String, String>): JsonObject? {
        if (raw.isEmpty()) return null
        val text = raw.entries.joinToString(separator = ",", prefix = "{", postfix = "}") {
            "\"${it.key}\":${it.value}"
        }
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.long(key: String): Long? =
        runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

    /** 单张图片上限：再大的塞进数据库只会拖垮列表滚动。 */
    private const val MaxSingleImageBytes = 64 * 1024 * 1024

    /**
     * 进度里"第一遍（建树）"占一条会话工作量的比例。
     *
     * 两遍都要把整个条目解压一遍，但第二遍还要逐条解析正文 + 写库，所以它更慢。
     * 1/4 是个经验值：只影响进度条爬升的匀称度，不影响任何正确性。
     */
    private const val Pass1Share = 0.25f

    /** 第二遍每产出这么多条消息报一次进度 —— 别让进度条一步到位，也别为每条都发一次事件。 */
    private const val ProgressEveryMessages = 50

    /** 没有标题的会话用首条提问的前若干字当标题。 */
    private const val FallbackTitleChars = 24

    private const val ImportLogTag = "SimpleChat/Import"
}
