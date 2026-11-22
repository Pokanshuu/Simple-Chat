package com.simplechat.app.data

import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTtsClientTest {
    private fun InputStream.readHttpLine(): String = buildString {
        while (true) {
            val ch = read()
            if (ch < 0 || ch == 10) break
            if (ch != 13) append(ch.toChar())
        }
    }
    private fun config(port: Int) = VoiceApiConfig(
        enabled = true,
        baseUrl = "http://127.0.0.1:$port",
        adapter = VoiceApiConfig.AdapterCustomOpenAi,
        model = "test-tts",
        customVoices = listOf("test-voice"),
    )

    @Test fun `openai adapter posts escaped text with bearer key and returns binary audio`() = runBlocking {
        val audio = byteArrayOf(82, 73, 70, 70, 1, 2, 3, 4)
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            val response = executor.submit<Triple<String, String, ByteArray>> {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = socket.getInputStream().buffered()
                    val requestLine = input.readHttpLine()
                    var contentLength = 0
                    var authorization = ""
                    while (true) {
                        val line = input.readHttpLine()
                        if (line.isEmpty()) break
                        if (line.startsWith("content-length:", true)) contentLength = line.substringAfter(':').trim().toInt()
                        if (line.startsWith("authorization:", true)) authorization = line.substringAfter(':').trim()
                    }
                    val body = input.readNBytes(contentLength)
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${audio.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(header.toByteArray()); write(audio); flush() }
                    Triple(requestLine, authorization, body)
                }
            }
            try {
                val result = withTimeout(5_000) { VoiceTtsClient.synthesize(
                    config(server.localPort), chatKey = "test-key", chatBaseUrl = "",
                    text = "hello \"world\"\nnext", voiceName = "test-voice",
                ) }
                val request = response.get(3, TimeUnit.SECONDS)
                assertTrue(request.first.startsWith("POST /v1/audio/speech HTTP/1.1"))
                assertEquals("Bearer test-key", request.second)
                val json = request.third.decodeToString()
                assertTrue(json.contains("\\\"world\\\""))
                assertTrue(json.contains("\\nnext"))
                assertTrue(json.contains("test-tts"))
                assertTrue(json.contains("test-voice"))
                assertArrayEquals(audio, (result as VoiceTtsClient.Result.Ok).bytes)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `cancelling synthesis closes the pending response socket`() = runBlocking<Unit> {
        val receivedRequest = CompletableDeferred<Unit>()
        val peerObservedClose = CompletableDeferred<Boolean>()
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            val serving = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = socket.getInputStream().buffered()
                    input.readHttpLine()
                    var contentLength = 0
                    while (true) {
                        val line = input.readHttpLine()
                        if (line.isEmpty()) break
                        if (line.startsWith("content-length:", true)) contentLength = line.substringAfter(':').trim().toInt()
                    }
                    input.readNBytes(contentLength)
                    receivedRequest.complete(Unit)
                    peerObservedClose.complete(input.read() == -1)
                }
            }
            try {
                val request = async(Dispatchers.IO) {
                    VoiceTtsClient.synthesize(
                        config(server.localPort), chatKey = "test-key", chatBaseUrl = "",
                        text = "cancel me", voiceName = "test-voice",
                    )
                }
                withTimeout(2_000) { receivedRequest.await() }
                request.cancel()
                withTimeout(2_000) { request.join() }
                assertTrue(withTimeout(2_000) { peerObservedClose.await() })
                serving.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
