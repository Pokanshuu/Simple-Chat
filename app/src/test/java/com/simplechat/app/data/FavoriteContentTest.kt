package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteContentTest {
    @Test fun previewCollapsesWhitespaceAndTruncates() {
        val text = "  第一段\n\n第二段\t" + "x".repeat(400)
        val preview = FavoriteContent.preview(text)
        assertEquals(FavoriteContent.PreviewChars, preview.length)
        assertTrue(preview.startsWith("第一段 第二段"))
        assertFalse(preview.contains('\n'))
        assertFalse(preview.contains('\t'))
    }

    @Test fun imagesKeepOnlyImageAttachmentsAndDropBase64() {
        val list = listOf(
            Attachment(Attachment.Kind.IMAGE, "p.jpg", "image/jpeg", "QUJD", 10, 20, "p.jpg"),
            Attachment(Attachment.Kind.TEXT, "设定.md", "text/markdown", "正文"),
        )
        val images = FavoriteContent.images(list)
        assertEquals(1, images.size)
        assertEquals("", images.single().data)
        assertEquals("p.jpg", images.single().path)
    }

    @Test fun namesKeepOnlyTextAttachments() {
        val list = listOf(
            Attachment(Attachment.Kind.IMAGE, "p.jpg", "image/jpeg", ""),
            Attachment(Attachment.Kind.TEXT, "设定.md", "text/markdown", "正文"),
        )
        assertEquals(listOf("设定.md"), FavoriteContent.names(list))
    }

    @Test fun folderNameValidation() {
        assertEquals("人物设定", FavoriteFolders.normalizeName("  人物设定  "))
        assertNull(FavoriteFolders.normalizeName("   "))
        assertNull(FavoriteFolders.normalizeName("x".repeat(FavoriteFolders.MaxNameChars + 1)))
    }

    @Test fun attachmentNamesRoundTrip() {
        val raw = FavoriteContent.encodeNames(listOf("设定.md", "大纲.txt"))
        assertEquals(listOf("设定.md", "大纲.txt"), FavoriteContent.decodeNames(raw))
        assertEquals(emptyList<String>(), FavoriteContent.decodeNames(null))
    }
}
