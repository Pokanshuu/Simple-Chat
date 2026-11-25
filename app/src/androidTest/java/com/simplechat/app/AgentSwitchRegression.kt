package com.simplechat.app

import android.app.Instrumentation
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.ServiceApiConfig
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.DEEPSEEK_FLASH
import com.simplechat.app.net.DEEPSEEK_PRO
import com.simplechat.app.net.IndependentSearchName
import com.simplechat.app.net.ModelCatalog
import com.simplechat.app.net.ProviderKind
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.ChatViewModel
import com.simplechat.app.ui.chat.ConversationMode
import com.simplechat.app.ui.chat.MessageStatus
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

internal object AgentSwitchRegression {
    private data class Request(val model: String, val tools: Set<String>)

    suspend fun run(instrumentation: Instrumentation, passed: (String) -> Unit) {
        val root = File(instrumentation.targetContext.cacheDir, "agent-switch-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = root
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(root, "settings.preferences_pb") }))
        store.setProvider("DEEPSEEK", "https://api.deepseek.com", DEEPSEEK_FLASH)
        store.setApiKey("mock-key")
        store.setApiStyle("ANTHROPIC")
        store.setServiceApi(files = false, ServiceApiConfig(enabled = true, baseUrl = "https://search.invalid"))
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = ChatRepository(database, context)
        val json = ChatApi.defaultJson()
        val requests = CopyOnWriteArrayList<Request>()
        val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            val payload = json.parseToJsonElement(Buffer().also(checkNotNull(request.body)::writeTo).readUtf8()).jsonObject
            requests += Request(payload.getValue("model").jsonPrimitive.content,
                payload["tools"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
            val stream = listOf(
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Test answer"}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                """{"type":"message_stop"}""",
            ).joinToString("") { "data: $it\n\n" }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(stream.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        val conversation = repository.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", DEEPSEEK_FLASH, title = "Switch matrix")
        val vm = withContext(Dispatchers.Main) {
            ChatViewModel(api, store, repository, "agent-switch").apply { openConversation(conversation) }
        }
        suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(10)
        }
        fun select(id: String) = vm.updateSettings(vm.settings.copy(model = ModelCatalog.find(ProviderKind.DEEPSEEK, id)))

        try {
            await { !vm.switching && vm.supportsWebSearch && vm.apiStyle.name == "ANTHROPIC" }
            val failures = mutableListOf<String>()
            val states = listOf(DEEPSEEK_FLASH to false, DEEPSEEK_FLASH to true, DEEPSEEK_PRO to false, DEEPSEEK_PRO to true)
            val cases = listOf(0, 3, 2, 1, 0, 0, 1, 1, 2, 0, 2, 2, 3, 1, 3, 3, 0).map(states::get)
            check(cases.zipWithNext().toSet().size == states.size * states.size)
            for ((index, case) in cases.withIndex()) {
                val (model, search) = case
                val previousFailures = failures.size
                val count = requests.size
                val messageCount = withContext(Dispatchers.Main) { vm.messages.size }
                withContext(Dispatchers.Main) {
                    if (vm.settings.model.id != model) select(model)
                    if (vm.webSearch != search) vm.toggleWebSearch()
                }
                // Settle between turns, just as when selecting a model before composing a message.
                withTimeout(5_000) { store.flow.first { it.modelId == model && it.webSearch == search } }
                await { vm.settings.model.id == model && vm.webSearch == search }
                withContext(Dispatchers.Main) { vm.send("Switch matrix case ${index + 1}: What is 2 + 2?") }
                await { !vm.isStreaming && vm.messages.size == messageCount + 2 }
                val wire = requests.drop(count)
                val reply = withContext(Dispatchers.Main) { vm.messages.last() }
                val expectedTool = when {
                    !search -> null
                    model == DEEPSEEK_FLASH -> "deepseek_web_search"
                    else -> IndependentSearchName
                }
                val captured = wire.singleOrNull()
                val searchTools = captured?.tools.orEmpty().intersect(setOf("deepseek_web_search", IndependentSearchName))
                if (captured?.model != model || searchTools != setOfNotNull(expectedTool) ||
                    reply.model != model || reply.status != MessageStatus.DONE || reply.content != "Test answer") {
                    failures += "case=$index wanted=$model/$search wire=$wire reply=${reply.model}/${reply.status}/${reply.content}"
                }
                val settled = store.flow.first()
                val visible = withContext(Dispatchers.Main) { vm.settings.model.id to vm.webSearch }
                if (settled.modelId != model || settled.webSearch != search || visible != case) {
                    failures += "case=$index wanted=$model/$search visible=$visible persisted=${settled.modelId}/${settled.webSearch}"
                }
                if (failures.size == previousFailures) passed("case=$index model=$model search=$search requests=${wire.size} wire=$captured")
            }
            check(failures.isEmpty()) { failures.joinToString("\n") }
            passed("17 sequential turns cover all 16 ordered transitions across the four model/search states")
        } finally {
            withContext(Dispatchers.Main) { vm.viewModelScope.cancel() }
            database.close()
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
