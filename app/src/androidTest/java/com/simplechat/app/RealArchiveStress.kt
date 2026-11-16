package com.simplechat.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.room.Room
import com.simplechat.app.data.*
import com.simplechat.app.db.AppDatabase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray

/** Real, user-authorized archive stress test. Emits counts only; hashes stay in local files. */
internal object RealArchiveStress {
    suspend fun run(ctx: android.content.Context, source: String, status: (String) -> Unit) {
        val restoring = source.endsWith("real-stress-export.zip")
        val db = AppDatabase.build(ctx, if (restoring) "real-restored-v2.db" else "real-stress.db")
        val repo = ChatRepository(db, ctx)
        val zip = File(source)
        check(zip.isFile)
        var last = -1
        try {
            status("Device max Java heap ${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB; free disk ${ctx.filesDir.usableSpace / 1024 / 1024}MB")
            val report = repo.importExternal(zip, ctx.cacheDir, ImportMode.MERGE) { fraction ->
                val percent = (fraction * 100).toInt()
                if (percent / 5 != last) {
                    last = percent / 5
                    status("Import $percent%")
                }
            }.first
            status("Imported=${report.conversations}; already=${report.alreadyPresent}; failed=${report.failed}; messages=${report.messages}; expected=${report.expected}")
            val reportCopy = File(ctx.filesDir, "stress-import-report.json")
            repo.lastImportReportFile()!!.copyTo(reportCopy, overwrite = true)
            check(report.failed == 0) { "See local stress-import-report.json" }
            check(db.conversationDao().count() == 2768)
            var messageCount = 0
            val audit = File(ctx.filesDir, "stress-actual.jsonl")
            audit.bufferedWriter().use { out ->
                for (conversation in db.conversationDao().getAllRaw()) {
                    for (id in db.messageDao().idsFor(conversation.id)) {
                        val header = db.messageDao().exportHeader(id)!!
                        val message = restoreExportMessage(header, 16384) { start -> db.messageDao().exportTextChunk(id, start, 16384)!! }
                        out.appendLine(JSONObject().put("conversation", conversation.id).put("id", message.id)
                            .put("content", hash(message.content)).put("reasoning", hash(message.reasoning.orEmpty())).toString())
                        messageCount++
                    }
                    if (db.messageDao().countFor(conversation.id) == 0) out.appendLine(JSONObject().put("conversation", conversation.id).put("empty", true).toString())
                }
            }
            check(messageCount == 71472) { "Expected 71472, got $messageCount" }
            status("All 2768 windows and 71472 messages present; per-message hashes written locally")
            val attachmentAudit = File(ctx.filesDir, "stress-attachments.jsonl")
            attachmentAudit.bufferedWriter().use { out ->
                val paths = db.attachmentRefDao().allPaths()
                for (path in paths) {
                    val file = File(ctx.filesDir, "attachments/$path")
                    check(file.isFile)
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    }
                    out.appendLine(JSONObject().put("size", file.length()).put("hash", digest.digest().joinToString("") { "%02x".format(it) }).toString())
                }
                status("Attachment paths=${paths.size}")
            }
            if (restoring) return
            val exported = File(ctx.cacheDir, "real-stress-export.zip")
            last = -1
            repo.exportArchive(exported) { fraction ->
                val percent = (fraction * 100).toInt()
                if (percent / 10 != last) { last = percent / 10; status("Export $percent%") }
            }
            check(exported.length() > 0)
            status("Full ZIP exported: ${exported.length()} bytes")
        } finally { db.close() }
    }
    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
