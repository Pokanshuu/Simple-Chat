package com.simplechat.app

import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.simplechat.app.data.*
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.db.CompactionEntity
import com.simplechat.app.net.*
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.*
import com.simplechat.app.ui.theme.SimpleChatTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Production VM, HTTP/SSE clients, Room and Compose; all provider responses stay on device. */
internal object CompactionRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val root = File(instrumentation.targetContext.cacheDir, "compaction-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(ctx, PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(root, "test.preferences_pb") }))
        store.setProvider("DEEPSEEK", "https://api.deepseek.com", "deepseek-flash")
        store.setApiKey("sk-local-test-key")
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val repository = ChatRepository(db, ctx)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val live = mutableListOf<ChatViewModel>()
        val blocked = AtomicBoolean(false)
        suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(20_000) {
            while (!condition()) delay(30)
        }
        fun nodes(): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo) {
                result += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            instrumentation.uiAutomation.rootInActiveWindow?.let { it.refresh(); visit(it) }
            return result
        }
        fun render(vm: ChatViewModel) = instrumentation.runOnMainSync {
            activity.setContent { SimpleChatTheme {
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
                    CompactionSection(vm.compaction, vm.compacting, vm::compactContext,
                        vm::cancelCompaction, vm::clearCompaction, vm::updateCompactionSummary, vm.compactionStatus)
                }
            } }
        }
        try {
            for (style in listOf("OPENAI", "ANTHROPIC")) {
                store.setApiStyle(style)
                val calls = AtomicInteger()
                val rejectLongHistory = AtomicBoolean(false)
                val accepted = Collections.synchronizedList(mutableListOf<String>())
                val payloads = Collections.synchronizedList(mutableListOf<JsonObject>())
                val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
                    val payload = Json.parseToJsonElement(okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
                    payloads += payload
                    val number = calls.incrementAndGet()
                    check(payload["model"]!!.jsonPrimitive.content == "deepseek-flash")
                    check(payload["tools"] == null || payload["tools"]!!.jsonArray.isEmpty())
                    check(payload["title"] == null)
                    val content = payload["messages"]!!.jsonArray.last().jsonObject["content"]!!
                    val input = if (content is JsonPrimitive) content.content else content.jsonArray.joinToString("") {
                        it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
                    }
                    val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    if (rejectLongHistory.get() && input == "continue-WITH-270K-HISTORY" &&
                        payload.toString().contains("中".repeat(100))
                    ) {
                        response.code(400).message("Bad Request").body(
                            """{"error":{"message":"user input too long, 270000 tokens exceeds max 150000 tokens"}}"""
                                .toResponseBody("application/json".toMediaType())).build()
                    } else if (number == 1) {
                        response.code(400).message("Bad Request").body(
                            """{"error":{"message":"user input too long, 32942 tokens exceeds max 32768 tokens"}}"""
                                .toResponseBody("application/json".toMediaType())).build()
                    } else {
                        while (number >= 3 && blocked.get() && !chain.call().isCanceled()) Thread.sleep(10)
                        if (chain.call().isCanceled()) throw java.io.IOException("Cancelled test request")
                        accepted += input
                        val text = "COMPLETE_SUMMARY_$number"
                        val body = if (style == "ANTHROPIC")
                            "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"$text\"}}\n\ndata: {\"type\":\"message_stop\"}\n\n"
                        else "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\ndata: [DONE]\n\n"
                        response.code(200).message("OK").body(body.toResponseBody("text/event-stream".toMediaType())).build()
                    }
                }.build()
                val json = ChatApi.defaultJson()
                val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
                suspend fun newVm(): ChatViewModel = withContext(Dispatchers.Main) {
                    ChatViewModel(api, store, repository, "compaction-test").also { live += it }
                }
                val id = repository.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "deepseek-flash", title = "Compaction test")
                repeat(6) { index ->
                    val user = repository.appendUserMessage(id,
                        if (index in 1..2) "第${index}段中文😀".repeat(20_000) else "question-$index")
                    val reply = repository.beginAssistantMessage(id, user.id)
                    repository.finishMessage(reply, "answer-$index", null, MessageStatus.DONE)
                }
                val original = repository.loadActivePath(id)
                val old = CompactionEntity("saved-$style", id, original[1].id, "ORIGINAL_SUMMARY", 1L, 1L)
                repository.saveCompaction(old)
                var vm = newVm()
                delay(250)
                withContext(Dispatchers.Main) { vm.openConversation(id) }
                waitFor { withContext(Dispatchers.Main) { !vm.switching && vm.messages.size == 12 } }
                render(vm)
                blocked.set(true)
                withContext(Dispatchers.Main) { vm.compactContext() }
                waitFor { calls.get() >= 3 }
                delay(300)
                check(withContext(Dispatchers.Main) { vm.compacting && vm.compaction == old })
                val savedFiles = File(ctx.noBackupFilesDir, "compaction").listFiles().orEmpty()
                check(savedFiles.size == 1 && savedFiles.single().readText().contains("COMPLETE_SUMMARY_2"))
                val screenshot = File(instrumentation.targetContext.filesDir, "regression/compaction-loading-$style.png")
                screenshot.parentFile!!.mkdirs()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    screenshot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                val runningTexts = nodes().mapNotNull { it.text?.toString() }
                val statusText = withContext(Dispatchers.Main) { checkNotNull(vm.compactionStatus) }
                check(runningTexts.any { it.contains(statusText) }) { "Missing loading state: $runningTexts" }
                check(runningTexts.none { it.contains("COMPLETE_SUMMARY") || it.contains("ORIGINAL_SUMMARY") })
                withContext(Dispatchers.Main) { vm.cancelCompaction(); vm.viewModelScope.cancel() }
                delay(200)
                check(repository.getCompactions(id).single() == old)
                check(accepted.size == 1)
                val firstInput = accepted.single()
                vm = newVm()
                delay(250)
                withContext(Dispatchers.Main) { vm.openConversation(id) }
                waitFor { withContext(Dispatchers.Main) { !vm.switching } }
                render(vm)
                blocked.set(false)
                withContext(Dispatchers.Main) { vm.compactContext() }
                waitFor { withContext(Dispatchers.Main) { !vm.compacting } }
                val final = withContext(Dispatchers.Main) {
                    check(vm.banner == null) { vm.banner.orEmpty() }
                    check(vm.settings.model.contextWindow == 1_000_000)
                    check(vm.compaction?.throughMessageId == original[5].id)
                    checkNotNull(vm.compaction)
                }
                check(accepted.drop(1).none { it == firstInput })
                check(final.summary.startsWith("COMPLETE_SUMMARY"))
                check(repository.loadActivePath(id).map { it.id to it.content } == original.map { it.id to it.content })
                check(File(ctx.noBackupFilesDir, "compaction").listFiles().orEmpty().isEmpty())
                delay(300)
                check(nodes().any { it.text?.toString()?.contains(final.summary) == true })
                // A fresh 1M conversation takes exactly one HTTP request for the full 228k-token input.
                val big = "abcd".repeat(228_000)
                check(TokenEstimate.of(big) == 228_000)
                val wholeId = repository.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "deepseek-flash", title = "Whole input")
                repeat(4) { index ->
                    val user = repository.appendUserMessage(wholeId, if (index == 0) big else "recent-$index")
                    val reply = repository.beginAssistantMessage(wholeId, user.id)
                    repository.finishMessage(reply, "answer", null, MessageStatus.DONE)
                }
                withContext(Dispatchers.Main) { vm.openConversation(wholeId) }
                waitFor { withContext(Dispatchers.Main) { !vm.switching && vm.messages.size == 8 } }
                val beforeSend = calls.get()
                val followUp = "continue-WITH-228K-HISTORY"
                withContext(Dispatchers.Main) { vm.send(followUp) }
                waitFor { withContext(Dispatchers.Main) { !vm.isStreaming && vm.messages.lastOrNull()?.content == "COMPLETE_SUMMARY_${beforeSend + 1}" } }
                val sentHistory = payloads.last()["messages"]!!.jsonArray.toString()
                check(calls.get() == beforeSend + 1 && sentHistory.contains(big) && sentHistory.contains(followUp))
                val beforeWhole = calls.get()
                blocked.set(true)
                withContext(Dispatchers.Main) { vm.compactContext() }
                waitFor { calls.get() > beforeWhole }
                check(withContext(Dispatchers.Main) { vm.compactionStatus == Res.get(com.simplechat.app.R.string.compact_whole_source) })
                blocked.set(false)
                waitFor { withContext(Dispatchers.Main) { !vm.compacting } }
                check(calls.get() == beforeWhole + 1 && accepted.last().contains(big))
                check(payloads.last()["max_tokens"]?.jsonPrimitive?.int == 65_536 && "safety_check" !in payloads.last())
                check(withContext(Dispatchers.Main) { vm.banner == null && vm.compaction?.summary == "COMPLETE_SUMMARY_${calls.get()}" })
                val longText = "中".repeat(405_000)
                check(TokenEstimate.of(longText) == 270_000)
                val recoveryId = repository.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "deepseek-flash", title = "Long recovery")
                val originalQuestion = repository.appendUserMessage(recoveryId, "start")
                val longReply = repository.beginAssistantMessage(recoveryId, originalQuestion.id)
                repository.finishMessage(longReply, longText, null, MessageStatus.DONE)
                withContext(Dispatchers.Main) { vm.openConversation(recoveryId) }
                waitFor { withContext(Dispatchers.Main) { !vm.switching && vm.messages.lastOrNull()?.id == longReply } }
                rejectLongHistory.set(true)
                val beforeRecovery = calls.get()
                val pendingPrompt = "continue-WITH-270K-HISTORY"
                withContext(Dispatchers.Main) { vm.send(pendingPrompt) }
                waitFor { withContext(Dispatchers.Main) { !vm.isStreaming && vm.messages.lastOrNull()?.role == MessageRole.ASSISTANT && calls.get() > beforeRecovery } }
                check(payloads[beforeRecovery]["messages"]!!.jsonArray.toString().contains(longText))
                val recoveredPayload = payloads.last().toString()
                check(recoveredPayload.contains(pendingPrompt) && recoveredPayload.contains("COMPLETE_SUMMARY") && !recoveredPayload.contains(longText))
                withContext(Dispatchers.Main) {
                    check(vm.messages.last().status == MessageStatus.DONE) { vm.messages.last().errorMessage.orEmpty() }
                    check(vm.compaction?.throughMessageId == longReply)
                }
                check(repository.loadActivePath(recoveryId).any { it.content == longText })
                withContext(Dispatchers.Main) { vm.viewModelScope.cancel() }
                status("$style: 270K request rejected at 150K, compacted and retried with the exact prompt in the same turn")
                status("$style: full 228K history sent in one request; 32K rejection automatically split compaction; checkpoint resumed after VM restart")
            }
        } finally {
            blocked.set(false)
            withContext(Dispatchers.Main) { live.forEach { it.cancelCompaction(); it.viewModelScope.cancel() }; activity.finish() }
            scope.cancel()
            delay(200)
            db.close()
            root.deleteRecursively()
        }
    }
}
