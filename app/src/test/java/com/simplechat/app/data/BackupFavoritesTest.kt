package com.simplechat.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFavoritesTest {
    @Test
    fun favorites_round_trip_through_backup_json() {
        val file = BackupFile(
            exportedAt = 42L,
            favoriteFolders = listOf(
                BackupFavoriteFolder("default", "收藏", sortOrder = 0, createdAt = 1L, updatedAt = 2L),
                BackupFavoriteFolder("f2", "人物", sortOrder = 1, createdAt = 3L, updatedAt = 4L),
            ),
            favorites = listOf(
                BackupFavorite(
                    id = "fav-1",
                    messageId = "m-1",
                    conversationId = "c-1",
                    conversationTitle = "会话",
                    role = "assistant",
                    content = "正文",
                    preview = "预览",
                    note = "备注",
                    attachmentNames = listOf("设定.txt"),
                    messageCreatedAt = 5L,
                    createdAt = 6L,
                    folderIds = listOf("default", "f2"),
                ),
            ),
        )

        val encoded = backupJson.encodeToString(BackupFile.serializer(), file)
        val decoded = backupJson.decodeFromString(BackupFile.serializer(), encoded)

        assertEquals(file.favoriteFolders, decoded.favoriteFolders)
        assertEquals(file.favorites, decoded.favorites)
        assertEquals(BackupFile.VERSION, decoded.version)
    }

    @Test
    fun old_backup_without_favorites_still_decodes() {
        val decoded = backupJson.decodeFromString(
            BackupFile.serializer(),
            """{"format":"simplechat-backup","version":2,"exportedAt":1,"conversations":[],"presets":[]}""",
        )
        assertTrue(decoded.favoriteFolders.isEmpty())
        assertTrue(decoded.favorites.isEmpty())
    }
}
