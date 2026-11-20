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
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.ChatViewModel
import com.simplechat.app.ui.chat.ConversationMode
import com.simplechat.app.ui.chat.MessageStatus
import com.simplechat.app.ui.chat.ReasoningLevel
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** Opt-in paid test. The host stages the key by stdin in the debug app's private cache. */
internal object DeepSeekLiveRegression {
    private const val roundCount = 6
    private const val lookupCode = "CEDAR-43-MOON"
    private const val docsUrl = "https://api-docs.deepseek.com/zh-cn/api/create-chat-completion"

    private data class WireRequest(
        val path: String,
        val model: String,
        val tools: Set<String>,
        val retainedInventoryTurns: Int,
        val historicalToolBlocks: Set<String>,
    )
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
        val prefix = "The ledger's lookup code is $lookupCode.\n"
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
        val inventory = batches.mapIndexed { index, batch -> if (index == 0) prefix + batch else batch }
        val calls = AtomicInteger()
        val requestChecks = Collections.synchronizedList(mutableListOf<WireRequest>())
        val json = ChatApi.defaultJson()
        val client = ChatApi.defaultClient().newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .addInterceptor { chain ->
                val request = chain.request()
                check(request.url.host == "api.deepseek.com" && request.url.isHttps && request.url.port == 443)
                val requestBody = checkNotNull(request.body)
                val payload = json.parseToJsonElement(okio.Buffer().also(requestBody::writeTo).readUtf8()).jsonObject
                val messages = payload.getValue("messages").jsonArray
                val historicalToolBlocks = messages.flatMap { message ->
                    (message.jsonObject["content"] as? JsonArray).orEmpty().mapNotNull { block ->
                        block.jsonObject["type"]?.jsonPrimitive?.content
                    }
                }.filter { it in setOf("server_tool_use", "web_search_tool_result", "web_fetch_tool_result") }.toSet()
                requestChecks += WireRequest(
                    path = request.url.encodedPath,
                    model = payload.getValue("model").jsonPrimitive.content,
                    tools = payload["tools"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("type").jsonPrimitive.content }.toSet(),
                    retainedInventoryTurns = messages.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                        .zip(inventory).count { (message, expected) ->
                            val content = message.jsonObject.getValue("content")
                            val text = when (content) {
                                is JsonPrimitive -> content.content
                                is JsonArray -> content.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
                                else -> ""
                            }
                            text.trimEnd() == expected.trimEnd()
                        },
                    historicalToolBlocks = historicalToolBlocks,
                )
                val number = calls.incrementAndGet()
                if (number > roundCount) throw java.io.IOException("Live test API request budget exhausted ($roundCount)")
                val bytes = requestBody.contentLength()
                val started = System.nanoTime()
                val response = chain.proceed(request)
                val id = response.header("x-request-id") ?: response.header("request-id") ?: "-"
                status("HTTP $number ${request.url.encodedPath} ${response.code} bytes=$bytes request=$id headersMs=${(System.nanoTime() - started) / 1_000_000}")
                response
            }.build()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(180_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(60)
        }
        try {
            store.setProvider("DEEPSEEK", "https://api.deepseek.com", DEEPSEEK_FLASH)
            store.setApiKey(key)
            store.setMaxTokens(4096)
            store.setApiStyle("OPENAI")
            store.setModel(DEEPSEEK_FLASH)
            store.setReasoningLevel(ReasoningLevel.HIGH.name)
            store.setWebSearch(true)
            val conversationId = repository.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", DEEPSEEK_FLASH, title = "Synthetic ledger")
            for ((index, batch) in inventory.withIndex()) {
                val user = repository.appendUserMessage(conversationId, batch)
                val assistant = repository.beginAssistantMessage(conversationId, user.id, DEEPSEEK_FLASH)
                repository.finishMessage(assistant, "Recorded batch ${index + 1}.", null, MessageStatus.DONE)
            }
            val vm = withContext(Dispatchers.Main) {
                ChatViewModel(api, store, repository, "deepseek-live").also { it.openConversation(conversationId) }
            }
            try {
                waitFor { !vm.switching && vm.messages.size == batches.size * 2 && vm.webSearch }
                val turns = listOf(
                    "OPENAI" to DEEPSEEK_FLASH,
                    "ANTHROPIC" to DEEPSEEK_FLASH,
                    "OPENAI" to DEEPSEEK_PRO,
                    "ANTHROPIC" to DEEPSEEK_FLASH,
                    "OPENAI" to DEEPSEEK_FLASH,
                    "ANTHROPIC" to DEEPSEEK_PRO,
                )
                val toolFailures = mutableListOf<String>()
                for ((index, turn) in turns.withIndex()) {
                    val (route, model) = turn
                    val effort = if (model == DEEPSEEK_PRO) ReasoningLevel.MAX else ReasoningLevel.HIGH
                    val profileId = store.flow.first().activeApiProfileId
                    store.updateApiProfile(profileId) { it.copy(apiStyleId = route, modelId = model, reasoningLevelId = effort.name) }
                    waitFor { vm.apiStyle.name == route && vm.settings.model.id == model && vm.settings.reasoningLevel == effort }
                    val before = calls.get()
                    val expected = batches.size * 2 + (index + 1) * 2
                    status("Sending $route model=$model thinking=${effort.name} turn=${index + 1}")
                    val round = index + 1
                    val task = when (round) {
                        1, 2, 5 -> "Use web search to find the official DeepSeek Chat Completions API reference. Include the source in your answer."
                        4 -> "Use the read-link tool to open $docsUrl and report the HTTP method shown on that page."
                        else -> "Continue from the existing conversation."
                    }
                    val prompt = "Round $round. $task Begin your answer with the exact lookup code from the first inventory message, copying all its letters, digits and hyphens verbatim."
                    withContext(Dispatchers.Main) { vm.send(prompt) }
                    waitFor { !vm.isStreaming && vm.messages.size == expected && vm.messages.last().status != MessageStatus.STREAMING }
                    withContext(Dispatchers.Main) {
                        val reply = vm.messages.last()
                        val usage = vm.lastUsage
                        val expectedPath = if (route == "OPENAI") "/chat/completions" else "/anthropic/v1/messages"
                        val wire = requestChecks.lastOrNull()
                        val searched = reply.timeline.filterIsInstance<ReplyBlock.SearchResult>().any { !it.failed && it.results.isNotEmpty() }
                        val fetched = reply.timeline.filterIsInstance<ReplyBlock.FetchResult>().any { !it.failed }
                        val signedFetch = reply.timeline.filterIsInstance<ReplyBlock.FetchResult>().any { !it.failed && !it.signature.isNullOrBlank() }
                        val lookupMatched = reply.content.contains(lookupCode, ignoreCase = true)
                        val citationCount = reply.timeline.filterIsInstance<ReplyBlock.Text>().sumOf { it.citations.size }
                        status("$route $model ${reply.status} input=${usage?.promptTokens} output=${usage?.completionTokens} " +
                            "cache=${usage?.cacheHitTokens} requests=${calls.get() - before} tools=${wire?.tools} " +
                            "searchResult=$searched fetchResult=$fetched signedFetch=$signedFetch citations=$citationCount lookupMatched=$lookupMatched inventory=${wire?.retainedInventoryTurns} historicalTools=${wire?.historicalToolBlocks}")
                        check(reply.status == MessageStatus.DONE) { reply.errorMessage.orEmpty().replace(key, "[redacted]") }
                        check(reply.model == model && calls.get() == before + 1 && vm.compaction == null)
                        check(wire != null && wire.path == expectedPath && wire.model == model && wire.retainedInventoryTurns == inventory.size)
                        val expectedTools = buildSet {
                            if (model == DEEPSEEK_FLASH) add("deepseek_web_search")
                            if (round == 4) add("deepseek_read_url")
                        }
                        check(wire.tools == expectedTools) { "Unexpected tools for round $round: ${wire.tools}" }
                        check(wire.historicalToolBlocks.isEmpty()) { "Unexpected replay of incompatible tool history in round $round" }
                        check((usage?.promptTokens ?: 0) >= 270_000) { "Unexpected input usage $usage" }
                        check(reply.content.isNotBlank()) { "Round $round completed with an empty reply" }
                        if (round in setOf(1, 5) && citationCount == 0) toolFailures += "OpenAI Flash search returned no structured citations in round $round"
                        if (round == 2 && !searched) toolFailures += "A Flash search did not return a successful result"
                        if (round == 4 && !fetched) toolFailures += "A Flash read-link did not return a successful result"
                    }
                    val persisted = repository.loadActivePath(conversationId)
                    check(persisted.size == expected && persisted.last().content.isNotBlank())
                }
                check(calls.get() == roundCount)
                check(toolFailures.isEmpty()) { toolFailures.joinToString("; ") }
            } finally {
                withContext(Dispatchers.Main) { vm.viewModelScope.cancel() }
            }
        } finally {
            scope.cancel()
            database.close()
            root.deleteRecursively()
        }
    }
}
