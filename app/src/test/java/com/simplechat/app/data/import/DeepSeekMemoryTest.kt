package com.simplechat.app.data.import

import com.simplechat.app.data.ImportFailure
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekMemoryTest {
    private val naming = ExternalBackup.ImportNaming(
        conversationFallbackTitle = { "Untitled" }, imageFallbackName = { "Image" }, imageName = { w, h -> "$w x $h" },
    )

    @Test
    fun `direct field reader matches previous mapping including field order escapes and fragments`() {
        val raw = """{"id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","inserted_at":"2024-01-01T00:00:00Z","unknown":{"deep":[1,2]},"fragments":[{"content":"a\nb\"c 😀","type":"RESPONSE"},{"type":"RESPONSE","content":"tail"},{"elapsed_secs":2,"content":"thinking","type":"THINK"},{"type":"TEMPLATE_RESPONSE","content":"template"},{"files":[{"export_path":"photo.png","is_image":true},{"is_image":false,"export_path":"file.txt"}],"type":"FILE"}]}"""
        assertEquals(DeepSeekHistory.parsedMessage(Json.parseToJsonElement(raw).jsonObject), readDeepSeekMessage(JsonStream(StringReader(raw))))
    }

    @Test
    fun `first pass skips a very large field without capturing the message`() {
        val source = object : java.io.Reader() {
            val prefix = """{"id":"large","messages":[{"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":""" + '"'
            val suffix = """"}]}]}"""
            var position = 0L
            val textLength = 80L * 1024 * 1024
            override fun read(chars: CharArray, offset: Int, length: Int): Int {
                var used = 0
                while (used < length) {
                    val value = when {
                        position < prefix.length -> prefix[position.toInt()]
                        position < prefix.length + textLength -> 'x'
                        position < prefix.length + textLength + suffix.length -> suffix[(position - prefix.length - textLength).toInt()]
                        else -> return if (used == 0) -1 else used
                    }
                    chars[offset + used++] = value
                    position++
                }
                return used
            }
            override fun close() = Unit
        }
        val scan = DeepSeekHistory.scanSession(source)
        assertEquals(1, scan.messages.size)
        assertEquals(64, scan.messages.single().requestHead!!.length)
    }

    @Test
    fun `hundred megabyte image streams to disk without base64 or image byte array`() = runBlocking {
        val file = File.createTempFile("large-deepseek-image-", ".zip")
        var copied = 0L
        try {
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                fun text(name: String, value: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
                }
                text("manifest.json", """{"export_format_version":3}""")
                text("chat_session_index.json", """[{"id":"image","title":"Image"}]""")
                text("chat_sessions/image.json", """{"id":"image","title":"Image","messages":[{"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"Hi"},{"type":"FILE","files":[{"is_image":true,"export_path":"large.png"}]}]}]}""")
                zip.putNextEntry(ZipEntry("large.png"))
                val buffer = ByteArray(64 * 1024) { 42 }
                repeat(1600) { zip.write(buffer) }
                zip.closeEntry()
            }
            val streamNaming = ExternalBackup.ImportNaming(
                imageFallbackName = { "Image" }, imageName = { _, _ -> "Image" },
                conversationFallbackTitle = { "Untitled" },
                storeImageStream = { input, _ ->
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; copied += count }
                    "stored-image"
                },
                storeImage = { _, _ -> error("Whole image must not be loaded") },
            )
            ZipFile(file).use { zip ->
                DeepSeekHistory.open(zip, streamNaming)!!.forEachSession { session ->
                    session.forEachMessage { message ->
                        assertEquals("stored-image", message.attachments.single().path)
                        assertEquals("", message.attachments.single().data)
                    }
                }
            }
            assertEquals(100L * 1024 * 1024, copied)
        } finally { file.delete() }
    }

    @Test
    fun `empty sessions orphans cycles and old timestamps preserve all source IDs`() = runBlocking {
        val scan = DeepSeekHistory.scanSession(StringReader("""{"id":"old","title":"Old","inserted_at":"2023-01-01T00:00:00Z","messages":[{"id":1,"parent_id":99},{"id":2,"parent_id":3},{"id":3,"parent_id":2}]}"""))
        val plan = DeepSeekHistory.planSession(scan, "old", naming)!!
        assertEquals(setOf(1L, 2L, 3L), plan.kept.keys)
        assertEquals(2, plan.repairs)
        assertEquals(1672531200000L, plan.header.createdAt)
        for (id in plan.kept.keys) {
            val seen = mutableSetOf<Long>()
            var cursor: Long? = id
            while (cursor != null) { assertTrue(seen.add(cursor)); cursor = plan.kept[cursor] }
        }
        val empty = DeepSeekHistory.planSession(DeepSeekHistory.scanSession(StringReader("""{"messages":[]}""")), "empty", naming)!!
        assertTrue(empty.kept.isEmpty())
    }

    @Test
    fun `unreadable numeric IDs fail explicitly rather than becoming an empty successful session`() {
        assertTrue(runCatching {
            DeepSeekHistory.scanSession(StringReader("""{"messages":[{"id":"not-numeric","parent_id":null}]}"""))
        }.isFailure)
    }

    @Test
    fun `duplicate source IDs cannot be silently deduplicated`() {
        val scan = DeepSeekHistory.scanSession(StringReader("""{"messages":[{"id":1},{"id":1}]}"""))
        assertTrue(runCatching { DeepSeekHistory.planSession(scan, "duplicate", naming) }.isFailure)
    }
}
