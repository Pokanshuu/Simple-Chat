package com.simplechat.app

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.DEEPSEEK_FLASH
import com.simplechat.app.net.DEEPSEEK_PRO
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.ChatViewModel
import com.simplechat.app.ui.chat.ConversationMode
import com.simplechat.app.ui.chat.MessageStatus
import com.simplechat.app.ui.chat.ReasoningLevel
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Opt-in paid test. The host stages the key by stdin in the debug app's private cache. */
internal object DeepSeekLiveRegression {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val credential = File(context.cacheDir, "deepseek-live-key")
        val key = try { credential.readText().trim().also { check(it.isNotEmpty()) } }
        finally { credential.delete() }
        val root = File(context.cacheDir, "deepseek-live-${UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : ContextWrapper(context) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(ctx, PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(root, "live.preferences_pb") }))
        val database = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val repository = ChatRepository(database, ctx)
        val calls = AtomicInteger()
        val client = ChatApi.defaultClient().newBuilder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                check(request.url.host == "api.deepseek.com" && request.url.isHttps && request.url.port == 443)
                val number = calls.incrementAndGet()
                val bytes = request.body?.contentLength()
                val started = System.nanoTime()
                val response = chain.proceed(request)
                val id = response.header("x-request-id") ?: response.header("request-id") ?: "-"
                status("HTTP $number ${request.url.encodedPath} ${response.code} bytes=$bytes request=$id headersMs=${(System.nanoTime() - started) / 1_000_000}")
                response
            }.build()
        val json = ChatApi.defaultJson()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        val prefix = "The ledger's lookup code is CEDAR-43-MOON.\n"
        val random = Random(5794)
        val colors = listOf("amber", "silver", "coral", "green", "navy", "white", "teal", "violet")
        val places = listOf("atrium", "workshop", "orchard", "studio", "library", "garden", "gallery", "depot")
        val batches = List(20) {
            buildString {
                repeat(405) {
                    val id = UUID(random.nextLong(), random.nextLong())
                    appendLine("Inventory $id records ${random.nextInt(20, 1000)} ${colors.random(random)} labels in the ${places.random(random)}.")
                }
            }
        }
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(180_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(60)
        }
        try {
            store.setProvider("DEEPSEEK", "https://api.deepseek.com", DEEPSEEK_FLASH)
            store.setApiKey(key)
            store.setMaxTokens(4096)
            for (style in listOf("OPENAI", "ANTHROPIC")) {
                store.setApiStyle(style)
                store.setModel(DEEPSEEK_FLASH)
                store.setReasoningLevel(ReasoningLevel.HIGH.name)
                val conversationId = repository.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", DEEPSEEK_FLASH, title = "Synthetic ledger")
                for ((index, batch) in batches.withIndex()) {
                    val user = repository.appendUserMessage(conversationId, (if (index == 0) prefix else "") + batch)
                    val assistant = repository.beginAssistantMessage(conversationId, user.id, DEEPSEEK_FLASH)
                    repository.finishMessage(assistant, "Recorded batch ${index + 1}.", null, MessageStatus.DONE)
                }
                val vm = withContext(Dispatchers.Main) {
                    ChatViewModel(api, store, repository, "deepseek-live").also { it.openConversation(conversationId) }
                }
                try {
                    waitFor { !vm.switching && vm.messages.size == batches.size * 2 }
                    val turns = listOf(style to DEEPSEEK_FLASH, style to DEEPSEEK_PRO, style to DEEPSEEK_FLASH) +
                        if (style == "ANTHROPIC") listOf("OPENAI" to DEEPSEEK_PRO) else emptyList()
                    for ((index, turn) in turns.withIndex()) {
                        val (route, model) = turn
                        if (route != style) store.setApiStyle(route)
                        val effort = if (model == DEEPSEEK_PRO) ReasoningLevel.MAX else ReasoningLevel.HIGH
                        withContext(Dispatchers.Main) {
                            val selected = vm.models.first { it.id == model }
                            vm.updateSettings(vm.settings.copy(model = selected, reasoningLevel = effort))
                        }
                        withTimeout(10_000) { store.flow.first { it.modelId == model && it.reasoningLevelId == effort.name && it.apiStyleId == route } }
                        waitFor { vm.apiStyle.name == route && vm.settings.model.id == model && vm.settings.reasoningLevel == effort }
                        val before = calls.get()
                        val expected = batches.size * 2 + (index + 1) * 2
                        status("Sending $route model=$model thinking=${effort.name} turn=${index + 1}")
                        withContext(Dispatchers.Main) {
                            vm.send("Ledger lookup ${index + 1}. Return only the lookup code from the first inventory message.")
                        }
                        waitFor { !vm.isStreaming && vm.messages.size == expected && vm.messages.last().status != MessageStatus.STREAMING }
                        withContext(Dispatchers.Main) {
                            val reply = vm.messages.last()
                            val usage = vm.lastUsage
                            status("$route $model ${reply.status} input=${usage?.promptTokens} output=${usage?.completionTokens} cache=${usage?.cacheHitTokens} stop=${reply.stopReason} requests=${calls.get() - before}")
                            check(reply.status == MessageStatus.DONE) { reply.errorMessage.orEmpty().replace(key, "[redacted]") }
                            check(reply.model == model && calls.get() == before + 1 && vm.compaction == null)
                            check((usage?.promptTokens ?: 0) in 270_000..285_000) { "Unexpected input usage $usage" }
                            check(reply.content.contains("CEDAR-43-MOON")) { "The response did not retrieve the first turn's lookup code" }
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) { vm.viewModelScope.cancel() }
                }
            }
        } finally {
            scope.cancel()
            database.close()
            root.deleteRecursively()
        }
    }
}
