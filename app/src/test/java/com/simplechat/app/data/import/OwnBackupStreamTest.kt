package com.simplechat.app.data.import

import com.simplechat.app.data.*
import java.io.File
import java.io.StringReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OwnBackupStreamTest {
    @Test fun `rollback only removes new files including those created before message decode fails`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("own-rollback-").toFile()
        val removed = mutableListOf<String>()
        val naming = ExternalBackup.ImportNaming(storeImageStream = { input, _ -> input.readBytes(); "new.png" },
            discardImage = { removed += it })
        val raw = """{"format":"simplechat-backup","version":2,"conversations":[
            {"id":"c","title":"x","mode":"CREATIVE","createdAt":1,"updatedAt":1,"messages":[
                {"id":"a","role":"user","content":"x","createdAt":1,"attachments":[
                    {"kind":"IMAGE","name":"old","data":"","path":"existing.png"}]},
                {"id":"b","createdAt":2,"attachments":[
                    {"kind":"IMAGE","name":"new","mime":"image/png","data":"AQID"}]}
            ]}]}"""
        try {
            val stream = OwnBackupStream.open({ StringReader(raw) }, dir, naming) { error("unexpected scan failure") }
            stream.forEachSession { session ->
                assertTrue(runCatching { session.forEachMessage { } }.isFailure)
                session.discard()
            }
            assertEquals(listOf("new.png"), removed)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { dir.delete() }
    }
    @Test
    fun `file streamed backup restores all branches presets empty sessions and attachment bytes`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("own-stream-").toFile()
        val file = File(dir, "backup.json")
        val headers = listOf(
            BackupConversation("c", "Title", "CREATIVE", createdAt = 1, updatedAt = 2, enabledPresetIds = listOf("p")),
            BackupConversation("empty", "Empty", "CREATIVE", createdAt = 1, updatedAt = 2),
        )
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val image = Attachment(Attachment.Kind.IMAGE, "image", "image/png", "", path = "source-file")
        val message = BackupMessage("m", role = "user", content = "中文😀\u0000", createdAt = 3, attachments = listOf(image))
        val presets = listOf(BackupPreset("p", "Preset", "Body", 0, 1))
        try {
            file.writer().buffered().use { writer ->
                writeBackupStream(writer, 5, headers, presets, attachmentSource = { bytes.inputStream() }) { id, sink ->
                    if (id == "c") sink(message)
                }
            }
            var restoredBytes = ByteArray(0)
            val naming = ExternalBackup.ImportNaming(storeImageStream = { input, _ -> restoredBytes = input.readBytes(); "restored-file" })
            val failures = mutableListOf<ImportFailure>()
            val stream = OwnBackupStream.open({ file.reader() }, dir, naming) { failures += it }
            assertEquals(2, stream.expected)
            assertEquals(presets, stream.presets)
            val received = mutableListOf<BackupMessage>()
            var windows = 0
            stream.forEachSession { session ->
                windows++
                session.forEachMessage { received += it }
            }
            assertEquals(2, windows)
            assertEquals(message.copy(attachments = listOf(image.copy(path = "restored-file"))), received.single())
            assertArrayEquals(bytes, restoredBytes)
            assertTrue(failures.isEmpty())
            assertEquals(listOf("backup.json"), dir.listFiles()!!.map { it.name })
        } finally { dir.listFiles()?.forEach { it.delete() }; dir.delete() }
    }

    @Test
    fun `wrong backup version fails instead of importing an empty history`() {
        val raw = """{"format":"simplechat-backup","version":1,"conversations":[],"presets":[]}"""
        assertTrue(runCatching { OwnBackupStream.open({ StringReader(raw) }, File(System.getProperty("java.io.tmpdir"))) { } }.isFailure)
    }
}
