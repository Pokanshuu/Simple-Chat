package com.simplechat.app.data.import

import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.ReplyBlock
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekShareTest {
    @Test fun identifiesSimpleChatOnPublicShareRequests() {
        val request = DeepSeekShare.contentRequest("Example_123")
        assertEquals(ChatApi.USER_AGENT, request.header("User-Agent"))
        assertEquals("application/json", request.header("Accept"))
        assertEquals("Example_123", request.url.queryParameter("share_id"))
        assertNull(request.header("Authorization"))
    }

    @Test fun acceptsOnlyHttpsDeepSeekShareLinks() {
        assertEquals("Example_123", DeepSeekShare.idFromLink(" https://chat.deepseek.com/share/Example_123/?x=1#foo "))
        listOf(
            "http://chat.deepseek.com/share/id",
            "https://chat.deepseek.com.example.org/share/id",
            "https://chat.deepseek.com:8443/share/id",
            "https://user@chat.deepseek.com/share/id",
            "https://chat.deepseek.com/share/a%2Fb",
            "https://chat.deepseek.com/share/id/extra",
        ).forEach { assertNull(it, DeepSeekShare.idFromLink(it)) }
    }

    @Test fun previewAndImportKeepBranchTreeWithoutBuildingBackupInMemory() = runBlocking {
        val file = File.createTempFile("deepseek-share-test", ".json")
        try {
            file.writeText(payload)
            val preview = DeepSeekShare.preview(file, "example", "Fallback")
            assertEquals(5, preview.messages)
            assertEquals(1, preview.attachments)

            val imported = mutableListOf<com.simplechat.app.data.BackupMessage>()
            preview.stream.forEachSession { session -> session.forEachMessage { imported += it } }
            assertEquals(5, imported.size)
            assertEquals(listOf("", "deepseek-share-example-1", "deepseek-share-example-1", "deepseek-share-example-2", "deepseek-share-example-4"), imported.map { it.parentId })
            assertEquals(listOf(0, 0, 1, 0, 0), imported.map { it.variantIndex })
            assertTrue(imported[1].active)
            assertFalse(imported[2].active)
            assertEquals(1_700_000_000_500, imported.first().createdAt)
            assertEquals("thinking trace", imported[1].reasoning)
            assertTrue(imported[1].timeline.any { it is ReplyBlock.SearchResult })
            assertTrue(imported[1].timeline.filterIsInstance<ReplyBlock.Text>().any { it.citations.isNotEmpty() })
            assertTrue(imported[1].content.contains("Answer"))
        } finally {
            file.delete()
        }
    }

    private val payload = """
        {"code":0,"data":{"biz_code":0,"biz_data":{"title":"A shared branch tree","messages":[
          {"message_id":1,"parent_id":null,"role":"USER","content":"Question","inserted_at":1700000000.5,"fragments":[{"type":"REQUEST","content":"Question"},{"type":"FILE","files":[{"id":"file-1","file_name":"lost.png","is_image":true}]}]},
          {"message_id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","fragments":[
            {"type":"THINK","content":"thinking trace","elapsed_secs":1.25},
            {"type":"TOOL_SEARCH","queries":["topic"],"results":[{"url":"https://example.com","title":"Example","snippet":"Source"}]},
            {"type":"RESPONSE","content":"Answer [reference:0]","references":[{"urls":["https://example.com"]}]}
          ]},
          {"message_id":3,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","fragments":[{"type":"RESPONSE","content":"Alternative"}]},
          {"message_id":4,"parent_id":2,"role":"USER","content":"Follow-up","fragments":[{"type":"REQUEST","content":"Follow-up"}]},
          {"message_id":5,"parent_id":4,"role":"ASSISTANT","status":"FINISHED","fragments":[{"type":"RESPONSE","content":"Last answer"}]}
        ]}}}
    """.trimIndent()
}
