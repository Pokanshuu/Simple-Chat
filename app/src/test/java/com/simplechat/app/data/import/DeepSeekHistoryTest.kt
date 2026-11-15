package com.simplechat.app.data.import

import com.simplechat.app.data.Attachment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek 官方导出（`deepseek_data-*.zip`，`export_format_version: 3`）的解析。
 *
 * 用真的 JSON 走一遍，钉住三件最容易错的事：
 * 1. **分支线性化** —— 从 `current_message_id` 往上走，兄弟版本不进来；
 * 2. **片段合并** —— 思考进 `reasoning`、模板正文也算正文；
 * 3. **状态映射** —— `FINISHED` / `INCOMPLETE` / `FAILED` / `CONTENT_FILTER`。
 */
class DeepSeekHistoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val naming = ExternalBackup.ImportNaming(
        imageName = { w, h -> "图 $w×$h" },
        imageFallbackName = { "图片" },
        conversationFallbackTitle = { "无题对话" },
    )

    private fun session(raw: String) = json.parseToJsonElement(raw).jsonObject

    // ── 线性化 ──────────────────────────────────────────────

    @Test
    fun `从 current_message_id 往上走出一条分支`() {
        val doc = session(
            """
            {"messages": [
              {"id": 1, "parent_id": null},
              {"id": 2, "parent_id": 1},
              {"id": 3, "parent_id": 2}
            ], "current_message_id": 3}
            """.trimIndent(),
        )
        val ids = DeepSeekHistory.linearize(
            doc.let { (it["messages"] as kotlinx.serialization.json.JsonArray).map { m -> m.jsonObject } },
            3L,
        ).map { (it["id"] as kotlinx.serialization.json.JsonPrimitive).content }

        assertEquals(listOf("1", "2", "3"), ids)
    }

    @Test
    fun `兄弟版本不进当前分支`() {
        val doc = session(
            """
            {"messages": [
              {"id": 1, "parent_id": null},
              {"id": 2, "parent_id": 1},
              {"id": 3, "parent_id": 1},
              {"id": 4, "parent_id": 3}
            ], "current_message_id": 4}
            """.trimIndent(),
        )
        val messages = DeepSeekHistory.linearize(
            (doc["messages"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject },
            4L,
        ).map { (it["id"] as kotlinx.serialization.json.JsonPrimitive).content }

        // 走 4 → 3 → 1；id=2 那条是被放弃的重新生成版本
        assertEquals(listOf("1", "3", "4"), messages)
    }

    @Test
    fun `current 缺失时退回 id 最大的那条`() {
        val doc = session(
            """
            {"messages": [
              {"id": 7, "parent_id": null},
              {"id": 9, "parent_id": 7}
            ]}
            """.trimIndent(),
        )
        val ids = DeepSeekHistory.linearize(
            (doc["messages"] as kotlinx.serialization.json.JsonArray).map { it.jsonObject },
            null,
        ).map { (it["id"] as kotlinx.serialization.json.JsonPrimitive).content }

        assertEquals(listOf("7", "9"), ids)
    }

    // ── 一条完整会话 ────────────────────────────────────────

    @Test
    fun `整棵树都导入，兄弟版本按变体保留`() {
        val doc = session(
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
        )
        val conversation = DeepSeekHistory.mapSession(doc, naming) { null }!!

        // 四条都在 —— 分支是数据里就有的，不是包的限制
        assertEquals(4, conversation.messages.size)

        val byId = conversation.messages.associateBy { it.id }
        val old = byId.getValue("deepseek-s9-2")
        val new = byId.getValue("deepseek-s9-3")
        // 同一父下的两个回答 = 两个版本，当前分支上的那条被选中
        assertEquals(0, old.variantIndex)
        assertEquals(1, new.variantIndex)
        assertEquals(false, old.active)
        assertEquals(true, new.active)
        // 旧分支的下游没有因为"没被选中"而丢掉
        assertEquals("deepseek-s9-3", byId.getValue("deepseek-s9-4").parentId)
        assertEquals(true, byId.getValue("deepseek-s9-4").active)
    }

    @Test
    fun `断链的消息不导入`() {
        val doc = session(
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
        )
        val conversation = DeepSeekHistory.mapSession(doc, naming) { null }!!
        assertEquals(listOf("deepseek-s10-1"), conversation.messages.map { it.id })
    }
    private val full = session(
        """
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
        """.trimIndent(),
    )

    @Test
    fun `会话、消息、思考、时间都映射正确`() {
        val conversation = DeepSeekHistory.mapSession(full, naming) { null }!!

        assertEquals("deepseek-s1", conversation.id)
        assertEquals("周末安排", conversation.title)
        assertTrue(conversation.pinned)
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
        val doc = session(
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
        )
        val conversation = DeepSeekHistory.mapSession(doc, naming) { null }!!

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
            val doc = session(
                """
                {"id": "s", "title": "t", "current_message_id": 1,
                 "inserted_at": "2026-11-11T02:00:00.000Z", "updated_at": "2026-11-11T02:00:01.000Z",
                 "messages": [{"id": 1, "parent_id": null, "role": "ASSISTANT", "status": "$raw",
                   "fragments": [{"type": "RESPONSE", "content": "x"}]}]}
                """.trimIndent(),
            )
            return DeepSeekHistory.mapSession(doc, naming) { null }!!.messages.single().status
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
        val doc = session(
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
        )
        val image = Attachment(
            kind = Attachment.Kind.IMAGE,
            name = "图 10×10",
            mime = "image/jpeg",
            data = "/9j/",
            width = 10,
            height = 10,
        )
        val conversation = DeepSeekHistory.mapSession(doc, naming) { path ->
            if (path.endsWith("f1_a.jpg")) image else null
        }!!

        assertEquals(listOf(image), conversation.messages.single().attachments)
    }

    @Test
    fun `没有消息的会话返回空`() {
        assertNull(DeepSeekHistory.mapSession(session("""{"id": "s", "messages": []}"""), naming) { null })
    }
}
