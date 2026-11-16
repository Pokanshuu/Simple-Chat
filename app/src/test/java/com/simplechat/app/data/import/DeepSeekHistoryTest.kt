package com.simplechat.app.data.import

import com.simplechat.app.data.AppTextException
import com.simplechat.app.data.Attachment
import com.simplechat.app.data.ImportFailure
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek 官方导出（`deepseek_data-*.zip`，`export_format_version: 3`）的解析。
 *
 * 用真的 JSON / 真的 ZIP 走一遍，钉住最容易错、错了又**看不出来**的事：
 * 1. **分支线性化** —— 从 `current_message_id` 往上走，兄弟版本不进来；
 * 2. **片段合并** —— 思考进 `reasoning`、模板正文也算正文；
 * 3. **状态映射** —— `FINISHED` / `INCOMPLETE` / `FAILED` / `CONTENT_FILTER`；
 * 4. **对账** —— 归档声明几条、导进来几条、哪条因为什么没进来;
 * 5. **长会话不再被整条丢弃** —— v1.1.3 之前超过 8MB 的会话是静默跳过的。
 */
class DeepSeekHistoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val naming = ExternalBackup.ImportNaming(
        imageName = { w, h -> "图 $w×$h" },
        imageFallbackName = { "图片" },
        conversationFallbackTitle = { "无题对话" },
    )

    private fun parsed(raw: String) = DeepSeekHistory.parseSession(raw)
        ?: error("会话 JSON 解析失败")

    private fun map(raw: String, imageOf: (String) -> Attachment? = { null }) =
        DeepSeekHistory.mapSession(parsed(raw), naming, imageOf)

    // ── 线性化 ──────────────────────────────────────────────

    @Test
    fun `从 current_message_id 往上走出一条分支`() {
        val session = parsed(
            """
            {"messages": [
              {"id": 1, "parent_id": null},
              {"id": 2, "parent_id": 1},
              {"id": 3, "parent_id": 2}
            ], "current_message_id": 3}
            """.trimIndent(),
        )
        assertEquals(listOf(1L, 2L, 3L), DeepSeekHistory.linearize(session.messages, 3L).map { it.number })
    }

    @Test
    fun `兄弟版本不进当前分支`() {
        val session = parsed(
            """
            {"messages": [
              {"id": 1, "parent_id": null},
              {"id": 2, "parent_id": 1},
              {"id": 3, "parent_id": 1},
              {"id": 4, "parent_id": 3}
            ], "current_message_id": 4}
            """.trimIndent(),
        )
        // 走 4 → 3 → 1；id=2 那条是被放弃的重新生成版本
        assertEquals(listOf(1L, 3L, 4L), DeepSeekHistory.linearize(session.messages, 4L).map { it.number })
    }

    @Test
    fun `current 缺失时退回 id 最大的那条`() {
        val session = parsed(
            """
            {"messages": [
              {"id": 7, "parent_id": null},
              {"id": 9, "parent_id": 7}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf(7L, 9L), DeepSeekHistory.linearize(session.messages, null).map { it.number })
    }

    // ── 一条完整会话 ────────────────────────────────────────

    @Test
    fun `整棵树都导入，兄弟版本按变体保留`() {
        val conversation = map(
            """
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
            """.trimIndent(),
        )!!

        // 四条都在 —— 分支是数据里就有的，不是包的限制
        assertEquals(4, conversation.messages.size)

        val byId = conversation.messages.associateBy { it.id }
        val old = byId.getValue("deepseek-s9-2")
        val new = byId.getValue("deepseek-s9-3")
        // 同一父下的两个回答 = 两个版本，当前分支上的那条被选中
        assertEquals(0, old.variantIndex)
        assertEquals(1, new.variantIndex)
        assertFalse(old.active)
        assertTrue(new.active)
        // 旧分支的下游没有因为"没被选中"而丢掉
        assertEquals("deepseek-s9-3", byId.getValue("deepseek-s9-4").parentId)
        assertTrue(byId.getValue("deepseek-s9-4").active)
    }

    @Test
    fun `断链的消息不导入`() {
        val conversation = map(
            """
            {"id": "s10", "title": "断链", "current_message_id": 1,
             "inserted_at": "2026-11-11T02:00:00.000Z", "updated_at": "2026-11-11T02:00:01.000Z",
             "messages": [
               {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                "fragments": [{"type": "REQUEST", "content": "问题"}]},
               {"id": 7, "parent_id": 999, "role": "ASSISTANT", "status": "FINISHED",
                "fragments": [{"type": "RESPONSE", "content": "孤儿"}]}
             ]}
            """.trimIndent(),
        )!!
        assertEquals(listOf("deepseek-s10-1"), conversation.messages.map { it.id })
    }

    private val full = """
        {
          "id": "s1",
          "title": "周末安排",
          "pinned": true,
          "current_message_id": 3,
          "inserted_at": "2026-11-11T02:33:23.270Z",
          "updated_at": "2026-11-11T02:40:00.000Z",
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
          ]
        }
    """.trimIndent()

    @Test
    fun `会话、消息、思考、时间都映射正确`() {
        val conversation = map(full)!!

        assertEquals("deepseek-s1", conversation.id)
        assertEquals("周末安排", conversation.title)
        assertTrue(conversation.pinned)
        // 归档里只有"是否置顶"，没有置顶时间 → 用更新时间顶上，
        // 否则导入的置顶会话会排到置顶组最后（看起来像"少了一个"）
        assertEquals(conversation.pinnedAt, 1_794_364_800_000L)
        assertEquals(3, conversation.messages.size)

        val (user, assistant, followUp) = conversation.messages
        assertEquals("user", user.role)
        assertEquals("周末去哪", user.content)
        assertEquals("", user.parentId)

        assertEquals("assistant", assistant.role)
        assertEquals("去公园吧", assistant.content)
        assertEquals("先想天气", assistant.reasoning)
        assertEquals(5_000L, assistant.thinkingMs)
        assertEquals("deepseek-s1-1", assistant.parentId)
        assertEquals("DONE", assistant.status)

        assertEquals("deepseek-s1-2", followUp.parentId)
        // 2026-11-11T02:33:23.270Z
        assertEquals(1_794_364_403_270L, user.createdAt)
    }

    @Test
    fun `模板正文算正文，内容过滤当正常收尾`() {
        val conversation = map(
            """
            {"id": "s2", "title": null, "current_message_id": 2,
             "inserted_at": "2026-11-11T02:00:00.000Z", "updated_at": "2026-11-11T02:00:01.000Z",
             "messages": [
               {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                "fragments": [{"type": "REQUEST", "content": "这条提问会被拿去当标题"}]},
               {"id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "CONTENT_FILTER",
                "fragments": [{"type": "TEMPLATE_RESPONSE", "content": "换个话题吧"}]}
             ]}
            """.trimIndent(),
        )!!

        // 没有标题就取首条提问
        assertEquals("这条提问会被拿去当标题", conversation.title)
        val reply = conversation.messages[1]
        assertEquals("换个话题吧", reply.content)
        // 按项目的规矩：拒绝只是"服务端收尾"，不当错误、不丢正文
        assertEquals("DONE", reply.status)
    }

    @Test
    fun `状态映射`() {
        fun status(raw: String): String {
            val conversation = map(
                """
                {"id": "s", "title": "t", "current_message_id": 1,
                 "inserted_at": "2026-11-11T02:00:00.000Z", "updated_at": "2026-11-11T02:00:01.000Z",
                 "messages": [{"id": 1, "parent_id": null, "role": "ASSISTANT", "status": "$raw",
                   "fragments": [{"type": "RESPONSE", "content": "x"}]}]}
                """.trimIndent(),
            )!!
            return conversation.messages.single().status
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
        val conversation = map(
            """
            {"id": "s3", "title": "带图", "current_message_id": 1,
             "inserted_at": "2026-11-11T02:00:00.000Z", "updated_at": "2026-11-11T02:00:01.000Z",
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
            """.trimIndent(),
        ) { path -> if (path.endsWith("f1_a.jpg")) image else null }!!

        assertEquals(listOf(image), conversation.messages.single().attachments)
    }

    @Test
    fun `没有消息的会话返回空`() {
        assertNull(map("""{"id": "s", "messages": []}"""))
    }

    // ── 切片器：内容里的括号 / 逗号 / 转义都不能算结构 ─────────

    @Test
    fun `正文里的花括号引号逗号不会被当成 JSON 结构`() {
        // 正文里塞进"像 JSON 的东西"和引号：切错一条，后面的消息就全乱了
        val tricky = "看这段 {\"a\":[1,2],\"b\":\"}\"} 还有 \"引号\" 与逗号, 以及 中"
        val trickyJson = kotlinx.serialization.json.JsonPrimitive(tricky).toString()
        val conversation = map(
            """
            {"id": "s4", "title": "转义", "current_message_id": 2,
             "inserted_at": "2026-11-11T02:00:00.000Z", "updated_at": "2026-11-11T02:00:01.000Z",
             "messages": [
               {"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",
                "fragments": [{"type": "REQUEST", "content": $trickyJson}]},
               {"id": 2, "parent_id": 1, "role": "ASSISTANT", "status": "FINISHED",
                "fragments": [{"type": "RESPONSE", "content": "结尾"}]}
             ]}
            """.trimIndent(),
        )!!

        assertEquals(2, conversation.messages.size)
        assertEquals(tricky, conversation.messages[0].content)
        assertEquals("结尾", conversation.messages[1].content)
    }

    // ── ZIP 级：流式打开、对账、长会话 ─────────────────────

    /** 造一份最小可用的 DeepSeek 归档（manifest + index + 会话 JSON）。 */
    private fun archive(
        sessions: List<Pair<String, String>>,
        index: List<Pair<String, String?>> = sessions.map { it.first to null },
        manifest: String = """{"export_format_version":3}""",
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
            zip.write("""{"sessions":[$entries]}""".toByteArray())
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

    @Test
    fun `流式打开会给出归档声明的会话数`() {
        val file = archive(
            sessions = listOf("a" to smallSession("a"), "b" to smallSession("b")),
            index = listOf("a" to "甲", "b" to "乙"),
        )
        java.util.zip.ZipFile(file).use { zip ->
            val opened = DeepSeekHistory.open(zip, naming)!!
            assertEquals(2, opened.expected)
            assertEquals(listOf("deepseek-a", "deepseek-b"), opened.map { it.id }.toList())
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
        java.util.zip.ZipFile(file).use { zip ->
            val opened = DeepSeekHistory.open(zip, naming, onFailure = { failures += it })!!
            assertEquals(2, opened.expected)
            assertEquals(1, opened.count())
        }
        file.delete()

        assertEquals(1, failures.size)
        assertEquals(ImportFailure.Reason.MISSING_ENTRY, failures.single().reason)
        assertEquals("乙（缺文件）", failures.single().title)
        assertEquals("b", failures.single().reference)
    }

    @Test
    fun `超过旧上限 8MB 的会话也能完整导入`() {
        /*
         * v1.1.2 的会话 JSON 上限是 8MB，超过就**整条静默跳过** ——
         * 这正是"迁移后有部分数据没导入"的来源之一（长角色扮演会话带分支时很容易超）。
         *
         * 这里造一份约 9MB 的会话（200 条 45KB 的长回复），验证：
         * 1. 它不再被跳过；2. 消息一条不少。
         */
        val chunk = "长".repeat(15_000)
        val messages = buildString {
            append("""{"id": 1, "parent_id": null, "role": "USER", "status": "FINISHED",""")
            append(""""fragments": [{"type": "REQUEST", "content": "开始"}]}""")
            for (index in 2..201) {
                append(
                    """,{"id": $index, "parent_id": ${index - 1}, "role": "ASSISTANT", "status": "FINISHED",""" +
                        """"fragments": [{"type": "RESPONSE", "content": "$chunk"}]}""",
                )
            }
        }
        val sessionJson = """
            {"id": "big", "title": "超长会话", "current_message_id": 201,
             "inserted_at": "2026-11-11T02:00:00.000Z", "updated_at": "2026-11-11T02:00:01.000Z",
             "messages": [$messages]}
        """.trimIndent()
        assertTrue("测试数据应当超过旧的 8MB 上限", sessionJson.toByteArray().size > 8 * 1024 * 1024)

        val file = archive(
            sessions = listOf("big" to sessionJson),
            index = listOf("big" to "超长会话"),
        )
        java.util.zip.ZipFile(file).use { zip ->
            val opened = DeepSeekHistory.open(zip, naming)!!
            val conversation = opened.single()
            assertEquals(201, conversation.messages.size)
            assertEquals("超长会话", conversation.title)
        }
        file.delete()
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
}
