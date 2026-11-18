package com.simplechat.app.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val MaxCompactionSummaryChars = 262_144
internal const val MaxCompactionChunkChars = 1_500_000

/** Work in progress is separate from the summary used for chat requests. */
@Serializable
internal data class CompactionCheckpoint(
    val fingerprint: String = "",
    val level: Int = 0,
    val offset: Long = 0,
    val sources: List<String> = emptyList(),
    val summaries: List<String> = emptyList(),
    val chunkChars: Int = MaxCompactionChunkChars,
    val requestWindow: Int = Int.MAX_VALUE,
    val triedHarnessBudget: Boolean = false,
    val outputExhausted: Boolean = false,
    val moreOutput: Boolean = false,
    val inputSizeRejected: Boolean = false,
    val completedBatches: Int = 0,
)

/** Hash incrementally, including segment boundaries; never copy an entire conversation. */
internal fun compactionFingerprint(parts: Sequence<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (part in parts) {
        digest.update(part.length.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0.toByte())
        var start = 0
        while (start < part.length) {
            var end = minOf(start + 8192, part.length)
            if (end < part.length && Character.isHighSurrogate(part[end - 1])) end--
            digest.update(part.substring(start, end).toByteArray(Charsets.UTF_8))
            start = end
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Private, disposable checkpoints. Corruption or a changed source starts a fresh operation. */
internal class CompactionCheckpointStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(id: String) = File(directory, compactionFingerprint(sequenceOf(id)) + ".json")

    fun load(id: String, fingerprint: String): CompactionCheckpoint? = runCatching {
        val file = file(id)
        if (!file.isFile || file.length() > MaxBytes) return null
        json.decodeFromString<CompactionCheckpoint>(file.readText()).takeIf {
            it.fingerprint == fingerprint && it.level in 0..12 && it.offset >= 0 &&
                it.chunkChars in 2..MaxCompactionChunkChars && it.requestWindow > 0 && it.completedBatches >= 0 &&
                (it.level > 0 || it.sources.isEmpty()) &&
                (it.sources + it.summaries).all { text -> text.isNotBlank() && text.length <= MaxCompactionSummaryChars }
        }
    }.getOrNull()

    fun save(id: String, state: CompactionCheckpoint) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create compaction checkpoint directory" }
        val bytes = json.encodeToString(CompactionCheckpoint.serializer(), state).toByteArray(Charsets.UTF_8)
        check(bytes.size <= MaxBytes) { "Compaction checkpoint is too large" }
        val target = file(id)
        val temporary = File.createTempFile("checkpoint-", ".tmp", directory)
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    fun delete(id: String) { file(id).delete() }

    fun clear() { directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() } }

    companion object { private const val MaxBytes = 8 * 1024 * 1024 }
}
