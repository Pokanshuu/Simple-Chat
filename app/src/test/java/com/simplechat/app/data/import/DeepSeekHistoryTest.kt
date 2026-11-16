package com.simplechat.app.data.import

import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.BackupMessage
import com.simplechat.app.data.ImportFailure
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek 官方导出（`deepseek_data-*.zip`，`export_format_version: 3`）的解析。
 *
 * 用真的 JSON / 真的 ZIP 走一遍，钉住最容易错、错了又**看不出来**的事：
 * 1. **两遍流式** —— 第一遍建树、第二遍逐条产出，顺序与内容都不能乱；
 * 2. **分支线性化** —— `current_message_id` 决定哪条是选中的版本；
 * 3. **片段合并** —— 思考进 `reasoning`、模板正文也算正文；
 * 4. **状态映射** —— `FINISHED` / `INCOMPLETE` / `FAILED` / `CONTENT_FILTER`；
 * 5. **对账** —— 归档声明几条、导进来几条、哪条因为什么没进来；
 * 6. **没有大小上限** —— v1.1.3 之前超过 24MB 的会话是静默跳过的。
 */
class DeepSeekHistoryTest {

    private val naming = ExternalBackup.ImportNaming(
        imageName = { w, h -> "图 $w×$h" },
        imageFallbackName = { "图片" },
        conversationFallbackTitle = { "无题对话" },
    )

    private fun scan(raw: String) = DeepSeekHistory.scanSession(StringReader(raw))

    private fun planned(raw: String, sessionId: String = "s1") =
        DeepSeekHistory.planSession(scan(raw), sessionId, naming)

    private fun parsedMessageOf(raw: String): DeepSeekHistory.ParsedMessage =
        DeepSeekHistory.parsedMessage(
            kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject,
        ) ?: error("消息解析失败")

    // ── 第一遍：骨架 ────────────────────────────────────────

    @Test
    fun `第一遍读出标量与每条消息的骨架`() {
        val result = scan(
            """
            {"id": "s1", "title": "周末安排", "pinned": true, "current_message_id": 3,
             "inserted_at": "2026-11-11T02:00:00.000Z",
             "messages": [
               {"id": 1, "parent_id": null, "role": "USER",
                "fragments": [{"type": "REQUEST", "content": "周末去哪"}]},
               {"id": 2, "parent_id": 1, "role": "ASSISTANT",
                "fragments": [{"type": "RESPONSE", "content": "去公园吧"}]}
             ]}
            """.trimIndent(),
        )
        assertEquals(2, result.messages.size)
        assertEquals(listOf(1L, 2L), result.messages.map { it.number })
        assertEquals(listOf(null, 1L), result.messages.map { it.parentNumber })
        assertEquals(listOf("USER", "ASSISTANT"), result.messages.map { it.role })
        // 提问开头只留"标题候选"，不是正文
        assertEquals("周末去哪", result.messages[0].requestHead)
        // 回复不需要标题候选
        assertNull(result.messages[1].requestHead)
        assertNotNull(result.header["title"])
    }

    @Test
    fun `正文里的花括号引号逗号不会被当成 JSON 结构`() {
        // 正文里塞进"像 JSON 的东西"和引号：切错一条，后面的消息就全乱了
        val tricky = "看这段 {\"a\":[1,2],\"b\":\"}\"} 还有 \"引号\" 与逗号, 以及 中"
        val trickyJson = kotlinx.serialization.json.JsonPrimitive(tricky).toString()
        val session = """
            {"id": "s4", "title": "转义", "current_message_id": 2,
             "inserted_at": "2026-11-11T02:00:00.000Z",
             "messages": [
               {"id": 1, "parent_id": null, "role": "USER",
                "fragments": [{"type": "REQUEST", "content": $trickyJson}]},
               {"id": 2, "parent_id": 1, "role": "ASSISTANT",
                "fragments": [{"type": "RESPONSE", "content": "结尾"}]}
             ]}
        """.trimIndent()
        val result = scan(session)
        assertEquals(2, result.messages.size)
        assertEquals(tricky, result.messages[0].requestHead)
    }

    // ── 树：变体 / 选中分支 / 断链 ─────────────────────────

    private val branched = """
        {"id": "s9", "title": "带分支", "current_message_id": 4,
         "inserted_at": "2026-11-11T02:00:00.000Z", "updated_at": "2026-11-11T02:00:01.000Z",
         "messages": [
           {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
            "fragments": [{"type": "REQUEST", "content": "问题"}]},
           {"id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
            "fragments": [{"type": "RESPONSE", "content": "旧回答"}]},
           {"id": 3, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
            "fragments": [{"type": "RESPONSE", "content": "新回答"}]},
           {"id": 4, "parent_id": 3, "role": "USER", "status": "FINISHED",
            "fragments": [{"type": "REQUEST", "content": "接着问"}]}
         ]}
    """.trimIndent()

    @Test
    fun `整棵树都导，兄弟版本按变体保留`() {
        val plan = planned(branched)!!

        // 四条都在 —— 分支是数据里就有的，不是包的限制
        assertEquals(4, plan.kept.size)
        // 同一父下的两个回答 = 两个版本
        assertEquals(0, plan.variantIndex[2L])
        assertEquals(1, plan.variantIndex[3L])
        // 当前分支走 4 → 3 → 1：选中 3，2 是放弃的那一版
        assertFalse(2L in plan.active)
        assertTrue(3L in plan.active)
        // 旧分支的下游没有因为"没被选中"而丢掉
        assertEquals(3L, plan.kept[4L])
        assertTrue(4L in plan.active)
    }

    @Test
    fun `断链的消息保留为独立根并记录修复`() {
        val plan = planned(
            """
            {"id": "s10", "title": "断链", "current_message_id": 1,
             "messages": [
               {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                "fragments": [{"type": "REQUEST", "content": "问题"}]},
               {"id": 7, "parent_id": 999, "role": "ASSISTANT", "status": "FINISHED",
                "fragments": [{"type": "RESPONSE", "content": "孤儿"}]}
             ]}
            """.trimIndent(),
        )!!
        assertEquals(listOf(1L, 7L), plan.kept.keys.toList())
        assertNull(plan.kept[7L])
        assertEquals(1, plan.repairs)
    }

    @Test
    fun `没有消息的会话保留窗口`() {
        val plan = planned("""{"id": "s", "messages": []}""")!!
        assertEquals("deepseek-s1", plan.header.id)
        assertTrue(plan.kept.isEmpty())
    }

    @Test
    fun `从 current_message_id 往上走出一条分支`() {
        val byNumber = scan(
            """
            {"messages": [
              {"id": 1, "parent_id": null},
              {"id": 2, "parent_id": 1},
              {"id": 3, "parent_id": 1},
              {"id": 4, "parent_id": 3}
            ], "current_message_id": 4}
            """.trimIndent(),
        ).messages.associateBy { it.number }
        // 走 4 → 3 → 1；id=2 那条是被放弃的重新生成版本
        assertEquals(listOf(1L, 3L, 4L), DeepSeekHistory.branchOf(byNumber, 4L))
    }

    @Test
    fun `current 缺失时退回 id 最大的那条`() {
        val byNumber = scan(
            """
            {"messages": [
              {"id": 7, "parent_id": null},
              {"id": 9, "parent_id": 7}
            ]}
            """.trimIndent(),
        ).messages.associateBy { it.number }
        assertEquals(listOf(7L, 9L), DeepSeekHistory.branchOf(byNumber, null))
    }

    // ── 一条消息的映射 ─────────────────────────────────────

    @Test
    fun `会话、消息、思考、时间都映射正确`() {
        val raw = """
            {"id": "s1", "title": "周末安排", "pinned": true, "current_message_id": 3,
             "inserted_at": "2026-11-11T02:33:23.270Z", "updated_at": "2026-11-11T02:40:00.000Z",
             "messages": [
               {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                "inserted_at": "2026-11-11T02:33:23.270Z",
                "fragments": [{"type": "REQUEST", "content": "周末去哪"}]},
               {"id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                "inserted_at": "2026-11-11T02:33:30.000Z",
                "fragments": [
                  {"type": "THINK", "content": "先想天气", "elapsed_secs": 5},
                  {"type": "TOOL_SEARCH", "queries": ["天气"], "results": []},
                  {"type": "RESPONSE", "content": "去公园吧"}
                ]},
               {"id": 3, "parent_id": 2, "role": "USER", "status": "FINISHED",
                "inserted_at": "2026-11-11T02:39:00.000Z",
                "fragments": [{"type": "REQUEST", "content": "好"}]}
             ]}
        """.trimIndent()
        val plan = planned(raw)!!

        assertEquals("deepseek-s1", plan.header.id)
        assertEquals("周末安排", plan.header.title)
        assertTrue(plan.header.pinned)
        // 归档里只有"是否置顶"，没有置顶时间 → 用更新时间顶上，
        // 否则导入的置顶会话会排到置顶组最后（看起来像"少了一个"）
        assertEquals(1_794_364_800_000L, plan.header.pinnedAt)

        val user = DeepSeekHistory.toBackupMessage(parsedMessageOf("""{"id": 1, "parent_id": null, "role": "USER",
            "fragments": [{"type": "REQUEST", "content": "周末去哪"}]}"""), plan, "s1", emptyList())!!
        assertEquals("deepseek-s1-1", user.id)
        assertEquals("user", user.role)
        assertEquals("周末去哪", user.content)
        assertEquals("", user.parentId)
        assertEquals(1_794_364_403_270L, user.createdAt)

        val reply = DeepSeekHistory.toBackupMessage(
            parsedMessageOf(
                """{"id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                "fragments": [{"type": "THINK", "content": "先想天气", "elapsed_secs": 5},
                              {"type": "RESPONSE", "content": "去公园吧"}]}""",
            ),
            plan,
            "s1",
            emptyList(),
        )!!
        assertEquals("assistant", reply.role)
        assertEquals("去公园吧", reply.content)
        assertEquals("先想天气", reply.reasoning)
        assertEquals(5_000L, reply.thinkingMs)
        assertEquals("deepseek-s1-1", reply.parentId)
        assertEquals("DONE", reply.status)
    }

    @Test
    fun `模板正文算正文，内容过滤当正常收尾`() {
        val raw = """
            {"id": "s2", "title": null, "current_message_id": 2,
             "inserted_at": "2026-11-11T02:00:00.000Z",
             "messages": [
               {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                "fragments": [{"type": "REQUEST", "content": "这条提问会被拿去当标题"}]},
               {"id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "CONTENT_FILTER",
                "fragments": [{"type": "TEMPLATE_RESPONSE", "content": "换个话题吧"}]}
             ]}
        """.trimIndent()
        val plan = planned(raw)!!

        // 没有标题就取首条提问
        assertEquals("这条提问会被拿去当标题", plan.header.title)
        val reply = DeepSeekHistory.toBackupMessage(
            parsedMessageOf(
                """{"id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "CONTENT_FILTER",
                    "fragments": [{"type": "TEMPLATE_RESPONSE", "content": "换个话题吧"}]}""",
            ),
            plan,
            "s2",
            emptyList(),
        )!!
        assertEquals("换个话题吧", reply.content)
        // 按项目的规矩：拒绝只是"服务端收尾"，不当错误、不丢正文
        assertEquals("DONE", reply.status)
    }

    @Test
    fun `状态映射`() {
        fun status(raw: String): String {
            val plan = planned(
                """
                {"id": "s", "title": "t", "current_message_id": 1,
                 "messages": [{"id": 1, "parent_id": null, "role": "ASSISTANT", "status": "$raw",
                   "fragments": [{"type": "RESPONSE", "content": "x"}]}]}
                """.trimIndent(),
            )!!
            return DeepSeekHistory.toBackupMessage(
                parsedMessageOf(
                    """{"id": 1, "parent_id": null, "role": "ASSISTANT", "status": "$raw",
                        "fragments": [{"type": "RESPONSE", "content": "x"}]}""",
                ),
                plan,
                "s",
                emptyList(),
            )!!.status
        }

        assertEquals("DONE", status("FINISHED"))
        assertEquals("STOPPED", status("INCOMPLETE"))
        assertEquals("STOPPED", status("WIP"))
        assertEquals("ERROR", status("FAILED"))
        assertEquals("ERROR", status("TIMEOUT"))
        assertEquals("ERROR", status("CONTEXT_LENGTH_EXCEEDED"))
    }

    @Test
    fun `图片按 export_path 取到附件，非图片附件不进来`() {
        val image = Attachment(
            kind = Attachment.Kind.IMAGE,
            name = "图 10×10",
            mime = "image/jpeg",
            data = "/9j/",
            width = 10,
            height = 10,
        )
        val raw = """
            {"id": "s3", "title": "带图", "current_message_id": 1,
             "messages": [
               {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                "fragments": [
                  {"type": "REQUEST", "content": "看图"},
                  {"type": "FILE", "files": [
                    {"id": "f1", "is_image": true, "export_path": "chat_sessions/s3_files/f1_a.jpg"},
                    {"id": "f2", "is_image": false, "export_path": null}
                  ]}
                ]}
             ]}
        """.trimIndent()
        val plan = planned(raw, "s3")!!
        val parsed = parsedMessageOf(
            """{"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                "fragments": [
                  {"type": "REQUEST", "content": "看图"},
                  {"type": "FILE", "files": [
                    {"id": "f1", "is_image": true, "export_path": "chat_sessions/s3_files/f1_a.jpg"},
                    {"id": "f2", "is_image": false, "export_path": null}
                  ]}
                ]}""",
        )
        // 非图片附件没有字节，不进附件列表；图片由调用方按 export_path 读好后传进来
        assertEquals(listOf("chat_sessions/s3_files/f1_a.jpg"), parsed.imagePaths)
        val message = DeepSeekHistory.toBackupMessage(parsed, plan, "s3", listOf(image))!!
        assertEquals(listOf(image), message.attachments)
    }

    // ── ZIP 级：流式、对账、长会话 ─────────────────────────

    /** 造一份最小可用的 DeepSeek 归档（manifest + index + 会话 JSON）。 */
    private fun archive(
        sessions: List<Pair<String, String>>,
        index: List<Pair<String, String?>> = sessions.map { it.first to null },
        manifest: String = """{"export_format_version":3}""",
        /** 真实导出里索引是**数组**；早期样本是 `{"sessions":[…]}`。两种都要能读。 */
        indexAsArray: Boolean = false,
    ): File {
        val file = File.createTempFile("deepseek-test-", ".zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            sessions.forEach { (id, body) ->
                zip.putNextEntry(ZipEntry("chat_sessions/$id.json"))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("chat_session_index.json"))
            val entries = index.joinToString(",") { (id, title) ->
                val titleJson = if (title == null) "null" else "\"$title\""
                """{"id":"$id","title":$titleJson,"pinned":false}"""
            }
            val indexJson = if (indexAsArray) "[$entries]" else """{"sessions":[$entries]}"""
            zip.write(indexJson.toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private fun smallSession(id: String, title: String = "会话"): String = """
        {"id": "$id", "title": "$title", "current_message_id": 1,
         "inserted_at": "2026-11-11T02:00:00.000Z", "updated_at": "2026-11-11T02:00:01.000Z",
         "messages": [
           {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
            "fragments": [{"type": "REQUEST", "content": "你好"}]}
         ]}
    """.trimIndent()

    /** 把一个归档完整消费一遍，返回 (会话头列表, 每个会话的消息)。 */
    private fun consume(file: File, failures: MutableList<ImportFailure> = mutableListOf()):
        Pair<List<com.simplechat.app.data.BackupConversation>, List<List<BackupMessage>>> {
        val headers = mutableListOf<com.simplechat.app.data.BackupConversation>()
        val messages = mutableListOf<List<BackupMessage>>()
        java.util.zip.ZipFile(file).use { zip ->
            val opened = DeepSeekHistory.open(zip, naming, onFailure = { failures += it })!!
            runBlocking {
                opened.forEachSession { session ->
                    headers += session.header
                    val list = mutableListOf<BackupMessage>()
                    session.forEachMessage { list += it }
                    messages += list
                }
            }
        }
        return headers to messages
    }

    @Test
    fun `流式打开会给出归档声明的会话数`() {
        val file = archive(
            sessions = listOf("a" to smallSession("a"), "b" to smallSession("b")),
            index = listOf("a" to "甲", "b" to "乙"),
        )
        val (headers, messages) = consume(file)
        assertEquals(listOf("deepseek-a", "deepseek-b"), headers.map { it.id })
        assertEquals(listOf(1, 1), messages.map { it.size })
        java.util.zip.ZipFile(file).use { zip ->
            assertEquals(2, DeepSeekHistory.open(zip, naming)!!.expected)
        }
        file.delete()
    }

    @Test
    fun `索引里有、归档里没有的会话记为失败并带上标题`() {
        val file = archive(
            sessions = listOf("a" to smallSession("a")),
            // 索引声明两条，但只有 a 的文件 —— 用户少了哪条必须能看见
            index = listOf("a" to "甲", "b" to "乙（缺文件）"),
        )
        val failures = mutableListOf<ImportFailure>()
        val (headers, _) = consume(file, failures)
        file.delete()

        assertEquals(1, headers.size)
        assertEquals(1, failures.size)
        assertEquals(ImportFailure.Reason.MISSING_ENTRY, failures.single().reason)
        assertEquals("乙（缺文件）", failures.single().title)
        assertEquals("b", failures.single().reference)
    }

    @Test
    fun `超过旧上限 24MB 的会话也能完整导入`() {
        /*
         * v1.1.2 的会话 JSON 上限是 8MB、v1.1.3 是 24MB，超过就**整条跳过** ——
         * 这正是"我最长的那几个对话导入不了"的来源（长角色扮演带分支时很容易超）。
         *
         * 这里造一份约 26MB 的会话（520 条 50KB 的长回复）。它必须：
         * 1. 不再被跳过；2. 消息一条不少；3. 内存只按"一条消息"算（由流式保证）。
         */
        val chunk = "长".repeat(25_000)
        val messages = buildString {
            append("""{"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",""")
            append(""""fragments": [{"type": "REQUEST", "content": "开始"}]}""")
            for (index in 2..521) {
                append(
                    """,{"id": $index, "parent_id": ${index - 1}, "role": "ASSISTANT", "status": "FINISHED",""" +
                        """"fragments": [{"type": "RESPONSE", "content": "$chunk"}]}""",
                )
            }
        }
        val sessionJson = """
            {"id": "big", "title": "超长会话", "current_message_id": 521,
             "inserted_at": "2026-11-11T02:00:00.000Z", "updated_at": "2026-11-11T02:00:01.000Z",
             "messages": [$messages]}
        """.trimIndent()
        assertTrue("测试数据应当超过旧的 24MB 上限", sessionJson.toByteArray().size > 24 * 1024 * 1024)

        val file = archive(
            sessions = listOf("big" to sessionJson),
            index = listOf("big" to "超长会话"),
        )
        val (headers, contents) = consume(file)
        file.delete()

        assertEquals(1, headers.size)
        assertEquals("超长会话", headers.single().title)
        assertEquals(521, contents.single().size)
        // 最后一条必须是最后那段正文，顺序与内容都不能乱
        assertEquals(chunk, contents.single().last().content)
    }

    @Test
    fun `清单读不出来但目录形状像 DeepSeek 时报包不完整`() {
        // manifest 是一段坏 JSON（下载/解压中断的典型样子）
        val file = archive(
            sessions = listOf("a" to smallSession("a")),
            manifest = "{ this is not json",
        )
        java.util.zip.ZipFile(file).use { zip ->
            assertTrue("目录形状对得上就该认领这份包", DeepSeekHistory.detect(zip))
            val error = runCatching { DeepSeekHistory.open(zip, naming) }.exceptionOrNull()
            assertTrue("应当是一句给用户看的文案，而不是 ZLIB 异常", error is AppTextException)
        }
        file.delete()
    }

    @Test
    fun `清单坏了且没有会话条目时不当成 DeepSeek`() {
        val file = File.createTempFile("deepseek-broken-", ".zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("{ broken".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("resources/1.jpg"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        java.util.zip.ZipFile(file).use { zip ->
            assertFalse(DeepSeekHistory.detect(zip))
            assertNull(DeepSeekHistory.open(zip, naming))
        }
        file.delete()
    }
    // ── 索引形状：真实导出是数组 ─────────────────────────────

    @Test
    fun `索引是数组时按索引顺序导入并给出标题`() {
        // 真实导出 chat_session_index.json 是 [{id,title,…}, …]（不是 {"sessions":[…]}）
        val file = archive(
            sessions = listOf("b" to smallSession("b", "乙"), "a" to smallSession("a", "甲")),
            index = listOf("a" to "甲", "b" to "乙"),
            indexAsArray = true,
        )
        val (headers, _) = consume(file)
        file.delete()
        // 按索引顺序（a 在 b 前），而不是 ZIP 条目顺序
        assertEquals(listOf("deepseek-a", "deepseek-b"), headers.map { it.id })
        assertEquals(listOf("甲", "乙"), headers.map { it.title })
    }

    @Test
    fun `索引是数组时也能对账出缺失条目`() {
        val file = archive(
            sessions = listOf("a" to smallSession("a")),
            index = listOf("a" to "甲", "b" to "乙（缺文件）"),
            indexAsArray = true,
        )
        val failures = mutableListOf<ImportFailure>()
        val (headers, _) = consume(file, failures)
        file.delete()
        assertEquals(1, headers.size)
        assertEquals(1, failures.size)
        assertEquals(ImportFailure.Reason.MISSING_ENTRY, failures.single().reason)
        assertEquals("乙（缺文件）", failures.single().title)
    }
}

