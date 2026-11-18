package com.simplechat.app

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.simplechat.app.data.*
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.*
import java.io.File
import kotlinx.serialization.json.Json

internal object BackupCompatibilityRegression {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val root = File(context.cacheDir, "backup-compat-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : ContextWrapper(context) { override fun getFilesDir() = root }
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val repository = ChatRepository(db, ctx)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        try {
            val original = BackupFile(exportedAt = 1, conversations = listOf(BackupConversation(
                "native-id", "Compatibility", "CREATIVE", model = "deepseek-flash", systemPrompt = "system",
                createdAt = 1700000000000, updatedAt = 1700000000500, enabledPresetIds = listOf("preset"), messages = listOf(
                    BackupMessage("u", role = "user", content = "Long preserved text ".repeat(4000),
                        attachments = listOf(Attachment(Attachment.Kind.TEXT, "local.txt", data = "local attachment original")), createdAt = 1700000000000),
                    BackupMessage("a", parentId = "u", role = "assistant", content = "Answer", reasoning = "Reasoning", thinkingMs = 2400,
                        model = "deepseek-flash", timeline = listOf(ReplyBlock.SearchResult("s", "web_search", listOf(WebSource("https://example.com", "Source"))),
                            ReplyBlock.Text("Answer", listOf(Citation("https://example.com", "Source")))), createdAt = 1700000000100),
                    BackupMessage("b", parentId = "u", variantIndex = 1, active = false, role = "assistant", content = "Alternative", createdAt = 1700000000200),
                ))), presets = listOf(BackupPreset("preset", "Preset", "Preset text", createdAt = 1)))
            check(repository.importParsed(original, ImportMode.MERGE).messages == 3)
            val baseline = json.decodeFromString<BackupFile>(repository.exportBackup())
            val deepseek = File(root, "deepseek.zip")
            val legacy = File(root, "legacy.zip")
            repository.exportDeepSeekArchive(deepseek)
            repository.exportArchive(legacy)
            repository.deleteAllConversations()
            val restored = repository.importExternal(deepseek, root, ImportMode.MERGE).first
            check(restored.messages == 3 && restored.failed == 0)
            val after = json.decodeFromString<BackupFile>(repository.exportBackup())
            check(after.conversations == baseline.conversations && after.presets == baseline.presets)
            repository.deleteAllConversations()
            check(repository.importExternal(legacy, root, ImportMode.MERGE).first.messages == 3)
            check(repository.loadActivePath("native-id").map { it.id } == listOf("u", "a"))
            status("DeepSeek-compatible ZIP and legacy ZIP restore through production importer: original IDs, branches, full text, file content, citations and presets preserved")
        } finally { db.close(); root.deleteRecursively() }
    }
}
