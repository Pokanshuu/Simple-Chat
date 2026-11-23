package com.simplechat.app

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.os.SystemClock
import android.view.Choreographer
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.db.ConversationEntity
import com.simplechat.app.db.MessageEntity
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.ChatViewModel
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

internal object CrossChatMemoryRegression {
    suspend fun run(instrumentation: Instrumentation, passed: (String) -> Unit) {
        val context = instrumentation.targetContext
        val name = "memory-regression-${UUID.randomUUID()}"
        val prefs = File(context.cacheDir, "$name.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) { prefs })
        store.setProvider("OPENCODE_GO", "https://memory.test.invalid", "deepseek-flash")
        store.setApiKey("local-regression")
        store.setCrossChatMemory(true)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        val requests = LinkedBlockingQueue<Pair<Long, Boolean>>()
        val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
            val body = Buffer().apply { chain.request().body?.writeTo(this) }.readUtf8()
            check("activebranchsecret" in body && "abandonedbranchsecret" !in body)
            requests.add(SystemClock.elapsedRealtime() to ("retrievalanswer" in body && "[1]" in body && body.indexOf("currentwindowsecret") == body.lastIndexOf("currentwindowsecret")))
            val events = if (chain.request().url.encodedPath.endsWith("/messages")) {
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"Fixture reply\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
            } else "data: {\"choices\":[{\"delta\":{\"content\":\"Fixture reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(events.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val json = ChatApi.defaultJson()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        val repository = ChatRepository(db, context)
        var vm: ChatViewModel? = null
        try {
            val body = "长篇正文背景说明。章节内容和技术记录。".repeat(230)
            val legacyAttachment = "X".repeat(3 * 1024 * 1024)
            for (window in 0..51) {
                val session = "session-$window"
                db.conversationDao().upsert(ConversationEntity(session, "Window $window", "CREATIVE",
                    "OPENCODE_GO", "deepseek-flash", createdAt = 0, updatedAt = window.toLong()))
                val rows = if (window == 0) 64 else if (window == 51) 650 else 100
                for (batch in (0 until rows).chunked(16)) {
                    db.messageDao().upsertAll(batch.map { index ->
                        val marker = when {
                            window == 0 && index == 0 -> "currentwindowsecret "
                            window == 51 && index == rows - 1 -> "activebranchsecret retrievalanswer "
                            else -> ""
                        }
                        MessageEntity("$session-$index", session, if (index == 0) "" else "$session-${index - 1}",
                            0, true, if (index % 2 == 0) "user" else "assistant",
                            marker + if (window == 0) body.take(1600) else body,
                            attachments = legacyAttachment.takeIf { window == 51 && index == rows - 1 },
                            createdAt = index.toLong())
                    })
                }
            }
            db.messageDao().upsertAll(listOf(
                MessageEntity("inactive-root", "session-51", "", 1, false, "user", "activebranchsecret", createdAt = 900),
                MessageEntity("inactive-child", "session-51", "inactive-root", 0, true, "assistant",
                    "activebranchsecret abandonedbranchsecret", createdAt = 901),
            ))
            val sampled = db.messageDao().memoryMessages("session-51", 4096, 80, 1200)
            check(sampled.size == 80 && sampled.all { it.content.length <= 1200 })
            check(sampled.last().content.startsWith("activebranchsecret retrievalanswer"))
            check(sampled.none { "abandonedbranchsecret" in it.content })
            check(db.conversationDao().memorySessions("session-0", 50, 120).size == 50)
            repository.setConversationMemoryEnabled("session-51", false)
            check(repository.memorySessions("session-0", 50, 120).none { it.id == "session-51" })
            check(repository.memoryMessages("session-51", 1000, 100, 120).isEmpty())
            repository.setConversationMemoryEnabled("session-51", true)
            repository.setConversationMemoryEnabled("session-0", false)
            check(repository.memorySessions("session-0", 50, 120).isEmpty())
            repository.setConversationMemoryEnabled("session-0", true)
            passed("memory opt-out excludes both the current conversation and reference sources before reading their messages")
            val heap = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
            val gaps = mutableListOf<Long>()
            val callback = object : Choreographer.FrameCallback {
                var previous = 0L
                override fun doFrame(frameTimeNanos: Long) {
                    val now = System.nanoTime()
                    if (previous > 0) gaps += (now - previous) / 1_000_000
                    previous = now
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            val model = withContext(Dispatchers.Main) { ChatViewModel(api, store, repository, name) }
            vm = model
            delay(150)
            val timings = mapOf(true to mutableListOf<Long>(), false to mutableListOf<Long>())
            val memoryTimes = mutableListOf<Long>()
            val frameMaxima = mapOf(true to mutableListOf<Long>(), false to mutableListOf<Long>())
            val trials = List(3) { listOf(false to "OPENAI", true to "OPENAI", false to "ANTHROPIC", true to "ANTHROPIC") }.flatten()
            for ((enabled, style) in trials) {
                store.setCrossChatMemory(enabled)
                store.setApiStyle(style)
                withTimeout(8_000) {
                    while (!withContext(Dispatchers.Main) { model.apiStyle.name == style && model.attachmentSettings.crossChatMemory == enabled }) delay(20)
                }
                withContext(Dispatchers.Main) { model.openConversation("session-0") }
                withTimeout(8_000) {
                    while (withContext(Dispatchers.Main) { model.switching || model.messages.size < 64 }) delay(20)
                }
                val start = withContext(Dispatchers.Main) {
                    gaps.clear()
                    callback.previous = 0
                    Choreographer.getInstance().postFrameCallback(callback)
                    SystemClock.elapsedRealtime().also { model.send("activebranchsecret retrievalanswer recall") }
                }
                try {
                    val (received, recalled) = withTimeout(8_000) {
                        var request = requests.poll()
                        while (request == null) { delay(5); request = requests.poll() }
                        checkNotNull(request)
                    }
                    check(recalled == enabled) { "Unexpected cross-window recall state in $style, enabled=$enabled; " +
                        com.simplechat.app.data.Diagnostics.snapshot().filter { "[MEM]" in it }.takeLast(4).joinToString(" | ") }
                    timings.getValue(enabled) += received - start
                    if (enabled) {
                        val entry = com.simplechat.app.data.Diagnostics.snapshot().last { "[MEM]" in it }
                        memoryTimes += Regex(" ms=([0-9]+)").find(entry)!!.groupValues[1].toLong()
                    }
                } finally {
                    frameMaxima.getValue(enabled) += withContext(Dispatchers.Main) {
                        Choreographer.getInstance().removeFrameCallback(callback)
                        gaps.maxOrNull() ?: 0
                    }
                }
                withTimeout(8_000) {
                    while (withContext(Dispatchers.Main) { model.isStreaming }) delay(20)
                }
            }
            passed("52 windows, ~24M stored chars, heap=${heap}MiB, 6 requests with memory and 6 baseline requests")
            passed("memory retrieval ms=$memoryTimes")
            for (enabled in listOf(false, true)) {
                passed("memory=$enabled send-to-request ms=${timings.getValue(enabled)}, max main frame gap ms=${frameMaxima.getValue(enabled)}")
            }
        } finally {
            withContext(Dispatchers.Main) { vm?.stop() }
            scope.cancel()
            db.close()
            context.deleteDatabase(name)
            prefs.delete()
        }
    }
}
