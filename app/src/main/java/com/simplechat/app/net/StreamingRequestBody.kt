package com.simplechat.app.net

import java.io.OutputStream
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** Count UTF-8 bytes without retaining another copy of a potentially huge conversation. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal open class StreamingRequestBody<T>(
    private val json: Json,
    private val serializer: SerializationStrategy<T>,
    private val value: T,
) : RequestBody() {
    private val byteCount by lazy {
        val counter = object : OutputStream() {
            var count = 0L
            override fun write(b: Int) { count++ }
            override fun write(b: ByteArray, off: Int, len: Int) { count += len }
        }
        json.encodeToStream(serializer, value, counter)
        counter.count
    }

    @Volatile private var writeStarted = false

    // Once upload starts, the server may have accepted the request even if no response arrived.
    internal fun canReplayOnDisconnect(): Boolean = !writeStarted

    override fun contentType() = "application/json; charset=utf-8".toMediaType()
    override fun contentLength(): Long = byteCount
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        writeStarted = true
        json.encodeToStream(serializer, value, sink.outputStream())
    }
}

internal const val LargeRequestBytes = 1_048_576L
internal const val LargeRequestWriteTimeoutSeconds = 120
