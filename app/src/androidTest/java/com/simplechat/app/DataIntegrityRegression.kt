package com.simplechat.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.simplechat.app.data.*
import com.simplechat.app.db.*
import com.simplechat.app.ui.chat.*
import java.io.File
import org.json.JSONObject

internal object DataIntegrityRegression {
    suspend fun run(ctx: Context, assets: android.content.res.AssetManager, repo: ChatRepository, db: AppDatabase) {
        val backup = BackupFile(exportedAt = 1, conversations = listOf(
            BackupConversation("review-a", "Same title", "CREATIVE", createdAt = 1, updatedAt = 1,
                messages = listOf(BackupMessage("review-a-msg", role = "user", content = "original", createdAt = 1))),
            BackupConversation("review-b", "Same title", "CREATIVE", createdAt = 1, updatedAt = 1,
                messages = listOf(BackupMessage("review-b-msg", role = "user", content = "second", createdAt = 1)))
        ))
        val imported = repo.importParsed(backup, ImportMode.REPLACE_SAME)
        check(imported.conversations == 2 && imported.failed == 0)
        check(db.messageDao().countFor("review-a") == 1 && db.messageDao().countFor("review-b") == 1)
        val collision = backup.copy(conversations = listOf(backup.conversations.first().copy(id = "review-c", title = "Collision")))
        check(repo.importParsed(collision, ImportMode.MERGE).failed == 1)
        check(db.conversationDao().findById("review-c") == null)
        check(db.messageDao().findById("review-a-msg")!!.content == "original")
        val preset = BackupPreset("review-p", "Preset", "instructions", createdAt = 1)
        val presetBackup = backup.copy(presets = listOf(preset), conversations = listOf(
            BackupConversation("review-preset", "Preset chat", "CREATIVE", createdAt = 1, updatedAt = 1, enabledPresetIds = listOf(preset.id))
        ))
        check(repo.importParsed(presetBackup, ImportMode.MERGE).failed == 0)
        check(repo.getEnabledPresetIds("review-preset") == listOf(preset.id))
        val single = File(ctx.cacheDir, "single-preset.json")
        try {
            repo.exportBackup(single, onlyConversation = "review-preset")
            val result = backupJson.decodeFromString(BackupFile.serializer(), single.readText())
            check(result.presets.map { it.id } == listOf(preset.id))
        } finally { single.delete() }

        val bytes = byteArrayOf(1, 2, 3, 4)
        val stored = AttachmentFiles.save(ctx, bytes.inputStream(), "image/png")
        val image = Attachment(Attachment.Kind.IMAGE, "shared", "image/png", "", path = stored)
        val original = repo.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "m")
        val prompt = repo.appendUserMessage(original, "fork", listOf(image))
        val fork = checkNotNull(repo.forkConversation(prompt.id))
        check(db.attachmentRefDao().pathsOf(fork) == listOf(stored))
        repo.deleteConversations(listOf(original))
        check(AttachmentFiles.read(ctx, stored)!!.contentEquals(bytes))
        check(repo.loadActivePath(fork).single().attachments.single().path == stored)
        repo.deleteConversations(listOf(fork))
        check(AttachmentFiles.read(ctx, stored) == null)

        // Edits must not immediately re-read a >CursorWindow row.
        val huge = "edit正文😀".repeat(300_000)
        check(repo.addActiveVariant("review-a", "", MessageRole.USER, huge).content == huge)

        val databaseName = "migration-review-${java.util.UUID.randomUUID()}.db"
        val path = ctx.getDatabasePath(databaseName)
        path.parentFile!!.mkdirs()
        val schema = assets.open("schema-v8.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO attachment_refs VALUES ('old.png','old-chat')")
                old.version = 8
            }
            val migrated = AppDatabase.build(ctx, databaseName)
            try {
                check(migrated.attachmentRefDao().pathsOf("old-chat") == listOf("old.png"))
                migrated.attachmentRefDao().upsertAll(listOf(AttachmentRefEntity("old.png", "fork-chat")))
                check(migrated.attachmentRefDao().pathsOf("old-chat") == listOf("old.png"))
                check(migrated.attachmentRefDao().pathsOf("fork-chat") == listOf("old.png"))
            } finally { migrated.close() }
        } finally { ctx.deleteDatabase(databaseName) }
    }
}
