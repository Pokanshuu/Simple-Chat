package com.simplechat.app.data.import

import com.simplechat.app.R
import com.simplechat.app.data.AppTextException
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chatbox 备份的解析。
 *
 * 这份格式是**从外部源码反推**出来的，字段名对不上、类型猜错都不会报错 ——
 * 只会安静地导入出"一堆空对话"。所以每条映射都钉死。
 *
 * 用例里的 JSON 都是**手写的**，刻意不依赖任何真实备份文件：
 * 真实文件会随时间变化，而这里要锁的是"我们认哪些字段"。
 */
class ExternalBackupTest {

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(format: String = "chatbox-backup", extra: String = ""): String = """
        {
          "format": "$format",
          "formatVersion": 2,
          "exportedAt": "2026-09-21T12:00:00.000Z",
          "data": { "copilots": { "c1": { "id": "c1", "name": "慢节奏", "prompt": "放慢叙事节奏" } } },
          "sessions": [ { "path": "sessions/s1.json" } ]
          $extra
        }
    """.trimIndent()

    private fun session(body: String = "") = """
        {
          "id": "s1",
          "name": "灯塔与守夜人",
          "messages": [
            { "id": "m1", "role": "user", "content": "写一个关于灯塔的故事", "timestamp": 1700000000000 },
            { "id": "m2", "role": "system", "content": "你是小说助手" },
            { "id": "m3", "role": "assistant", "content": "海雾漫上来了。",
              "contentParts": [ { "type": "reasoning", "text": "先铺垫氛围" } ],
              "timestamp": 1700000005000 }
            $body
          ]
        }
    """.trimIndent()

    // ── ZIP 形态 ──────────────────────────────────────────

    @Test
    fun `reads a zipped chatbox backup`() {
        val parsed = ExternalBackup.parse(
            zipOf("manifest.json" to manifest(), "sessions/s1.json" to session()),
        )

        assertEquals(ExternalBackup.Source.CHATBOX, parsed.source)
        val conversation = parsed.file.conversations.single()
        assertEquals("灯塔与守夜人", conversation.title)
        assertEquals("s1", conversation.id)
    }

    @Test
    fun `maps roles and drops system`() {
        val parsed = ExternalBackup.parse(
            zipOf("manifest.json" to manifest(), "sessions/s1.json" to session()),
        )

        val messages = parsed.file.conversations.single().messages
        // system 不导入 —— 它在 Chatbox 里是会话级提示词，不该混进对话流
        assertEquals(listOf("user", "assistant"), messages.map { it.role })
    }

    @Test
    fun `rebuilds the parent chain`() {
        val parsed = ExternalBackup.parse(
            zipOf("manifest.json" to manifest(), "sessions/s1.json" to session()),
        )

        val messages = parsed.file.conversations.single().messages
        // 首条挂根，后面每条挂在前一条上 —— 我们的模型是树
        assertEquals("", messages[0].parentId)
        assertEquals("m1", messages[1].parentId)
        assertTrue(messages.all { it.active && it.variantIndex == 0 })
    }

    @Test
    fun `pulls reasoning out of contentParts`() {
        val parsed = ExternalBackup.parse(
            zipOf("manifest.json" to manifest(), "sessions/s1.json" to session()),
        )

        val assistant = parsed.file.conversations.single().messages[1]
        assertEquals("先铺垫氛围", assistant.reasoning)
        assertEquals("海雾漫上来了。", assistant.content)
    }

    @Test
    fun `keeps timestamps for ordering`() {
        val parsed = ExternalBackup.parse(
            zipOf("manifest.json" to manifest(), "sessions/s1.json" to session()),
        )

        val conversation = parsed.file.conversations.single()
        assertEquals(1_700_000_000_000, conversation.createdAt)
        assertEquals(1_700_000_005_000, conversation.updatedAt)
    }

    @Test
    fun `maps copilots to presets`() {
        val parsed = ExternalBackup.parse(
            zipOf("manifest.json" to manifest(), "sessions/s1.json" to session()),
        )

        val preset = parsed.file.presets.single()
        assertEquals("慢节奏", preset.name)
        assertEquals("放慢叙事节奏", preset.content)
        // 加前缀：导入的预设要和本地的区分开，方便用户认出"哪些是搬来的"
        assertTrue(preset.id.startsWith("chatbox-"))
    }

    @Test
    fun `joins multiple text parts when content is missing`() {
        val body = """
            , { "id": "m4", "role": "assistant",
                "contentParts": [
                  { "type": "text", "text": "第一段" },
                  { "type": "text", "text": "第二段" },
                  { "type": "image", "storageKey": "res-1" }
                ] }
        """.trimIndent()

        val parsed = ExternalBackup.parse(
            zipOf("manifest.json" to manifest(), "sessions/s1.json" to session(body)),
        )

        val last = parsed.file.conversations.single().messages.last()
        assertEquals("第一段\n\n第二段", last.content)
    }

    @Test
    fun `skips tool calls and tool role`() {
        val body = """
            , { "id": "m5", "role": "tool", "content": "工具结果" }
            , { "id": "m6", "role": "assistant",
                "contentParts": [ { "type": "tool-call", "toolName": "search", "result": "x" } ] }
        """.trimIndent()

        val parsed = ExternalBackup.parse(
            zipOf("manifest.json" to manifest(), "sessions/s1.json" to session(body)),
        )

        // 工具调用块没有正文，整条丢弃；tool 角色同理
        assertEquals(listOf("user", "assistant"), parsed.file.conversations.single().messages.map { it.role })
    }

    // ── 单文件 JSON 形态 ──────────────────────────────────

    @Test
    fun `reads a plain json chatbox backup`() {
        val text = """
            {
              "format": "chatbox-backup",
              "sessions": [
                {
                  "id": "s9",
                  "name": "单文件会话",
                  "messages": [ { "id": "a", "role": "user", "content": "你好" } ]
                }
              ]
            }
        """.trimIndent()

        val parsed = ExternalBackup.parse(text.toByteArray())

        assertEquals(ExternalBackup.Source.CHATBOX, parsed.source)
        assertEquals("单文件会话", parsed.file.conversations.single().title)
    }

    // ── 错误路径 ──────────────────────────────────────────

    @Test
    fun `rejects a zip without a chatbox manifest`() {
        val bytes = zipOf("readme.txt" to "hello")

        // 报错文案断言的是**资源 ID**，不是句子本身 —— 句子随界面语言变，
        // 该钉住的是"这一条走哪句话"
        val error = assertThrows(AppTextException::class.java) {
            ExternalBackup.parse(bytes)
        }
        assertEquals(R.string.err_no_manifest, error.text.resId)
    }

    @Test
    fun `rejects a foreign json`() {
        val error = assertThrows(AppTextException::class.java) {
            ExternalBackup.parse("""{"format":"something-else"}""".toByteArray())
        }
        assertEquals(R.string.err_unrecognised_format, error.text.resId)
        assertEquals(listOf<Any>("something-else"), error.text.args)
    }

    @Test
    fun `rejects a backup without any usable session`() {
        val empty = """{ "id": "s1", "name": "空会话", "messages": [] }"""
        val bytes = zipOf("manifest.json" to manifest(), "sessions/s1.json" to empty)

        val error = assertThrows(AppTextException::class.java) {
            ExternalBackup.parse(bytes)
        }
        assertEquals(R.string.err_no_sessions, error.text.resId)
    }

    // ── 自己的备份仍然认得 ────────────────────────────────

    @Test
    fun `recognises our own backup and passes it through`() {
        val ours = """
            { "format": "simplechat-backup", "version": 2, "exportedAt": 1, "conversations": [] }
        """.trimIndent()

        val parsed = ExternalBackup.parse(ours.toByteArray())

        assertEquals(ExternalBackup.Source.SIMPLE_CHAT, parsed.source)
        assertTrue(parsed.file.conversations.isEmpty())
    }
}
