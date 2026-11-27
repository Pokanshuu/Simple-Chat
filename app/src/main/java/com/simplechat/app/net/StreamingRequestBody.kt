package com.simplechat.app.net

import java.io.File
import java.io.OutputStream
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * Streams a JSON request without keeping a second UTF-16 copy of it in memory.
 *
 * [contentLength] must be known before the request starts (several gateways rely on
 * `Content-Length`, and [LargeRequestBytes] drives the upload timeout), while [writeTo] needs
 * the same bytes again. Serializing twice cost a full kotlinx.serialization pass over the whole
 * conversation — with images that is tens of MB, and agent rounds repeat it per round.
 *
 * With [stageDir] the payload is serialized once into a cache file: [contentLength] reports the
 * file size and [writeTo] copies it to the socket. Without a directory (JVM tests, or when the
 * cache is unavailable) it falls back to the previous count-then-encode behaviour.
 */
internal open class StreamingRequestBody<T>(
    private val json: Json,
    private val serializer: SerializationStrategy<T>,
    private val value: T,
    /** Directory for the one-shot staged payload; `null` keeps the two-pass fallback. */
    private val stageDir: File? = null,
) : RequestBody() {

    private var staged: File? = null
    private var stagedLength = -1L

    @Volatile private var writeStarted = false

    // Once upload starts, the server may have accepted the request even if no response arrived.
    internal fun canReplayOnDisconnect(): Boolean = !writeStarted

    override fun contentType() = "application/json; charset=utf-8".toMediaType()
    override fun isOneShot() = true

    override fun contentLength(): Long {
        stagedFile()?.let { return stagedLength }
        val counter = object : OutputStream() {
            var count = 0L
            override fun write(b: Int) { count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { count += len }
        }
        json.encodeToStream(serializer, value, counter)
        return counter.count
    }

    override fun writeTo(sink: BufferedSink) {
        writeStarted = true
        val file = stagedFile()
        if (file != null) {
            // 关掉 source 再返回：Windows 上仍打开的文件无法删除，`dispose()` 会静默失败，
            // 请求缓存文件就留在临时目录里（单测也会卡在“dispose 后目录为空”）。
            file.source().use { source -> sink.writeAll(source) }
            return
        }
        json.encodeToStream(serializer, value, sink.outputStream())
    }

    /** Release the staged copy. Safe to call at any time; failures are ignored (cache GC cleans up). */
    @Synchronized
    internal fun dispose() {
        val file = staged ?: return
        staged = null
        stagedLength = -1L
        runCatching { file.delete() }
    }

    /**
     * Serialize once into a cache file. Returns null when staging is unavailable (no directory,
     * or the cache is full) so callers fall back to the streaming path instead of failing.
     */
    @Synchronized
    private fun stagedFile(): File? {
        staged?.let { return it }
        val dir = stageDir ?: return null
        var file: File? = null
        return try {
            file = File.createTempFile("simplechat-request-", ".json", dir)
            file.outputStream().use { stream -> json.encodeToStream(serializer, value, stream) }
            stagedLength = file.length()
            staged = file
            file
        } catch (_: Exception) {
            file?.let { runCatching { it.delete() } }
            null
        }
    }
}

internal const val LargeRequestBytes = 1_048_576L
internal const val LargeRequestWriteTimeoutSeconds = 120
