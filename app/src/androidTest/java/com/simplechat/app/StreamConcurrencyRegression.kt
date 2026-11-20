package com.simplechat.app

import android.app.Instrumentation
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.ReplyBlock
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.ChatViewModel
import com.simplechat.app.ui.chat.ConversationMode
import com.simplechat.app.ui.chat.MessageRole
import com.simplechat.app.ui.chat.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Verifies duplicate stream-start actions are rejected while their first operation owns sendMutex. */
internal object StreamConcurrencyRegression {
    suspend fun run(instrumentation: Instrumentation, passed: (String) -> Unit) {
        val root = File(instrumentation.targetContext.cacheDir, "stream-concurrency-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = root
        }
        val settingsScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(context, PreferenceDataStoreFactory.create(
            scope = settingsScope,
            produceFile = { File(root, "settings.preferences_pb") },
        ))
        store.setProvider("OPENCODE_GO", "https://concurrency.test.invalid", "deepseek-flash")
        store.setApiStyle("ANTHROPIC")

        val requestCount = AtomicInteger()
        val currentGate = AtomicReference(CountDownLatch(1))
        val currentRelease = AtomicReference(CountDownLatch(1))
        val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
            requestCount.incrementAndGet()
            currentGate.get().countDown()
            check(currentRelease.get().await(5, TimeUnit.SECONDS)) { "Timed out waiting to release mocked response" }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("data: {\"type\":\"message_stop\"}\n\n".toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val json = ChatApi.defaultJson()
        val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = ChatRepository(db, context)
        val vm = withContext(Dispatchers.Main) { ChatViewModel(api, store, repository, "stream-concurrency") }

        suspend fun awaitIdle() = withTimeout(5_000) {
            while (withContext(Dispatchers.Main) { vm.switching }) delay(10)
        }
        suspend fun awaitStreamStart(expectedCount: Int) = withTimeout(5_000) {
            while (requestCount.get() < expectedCount) delay(10)
            check(withContext(Dispatchers.IO) { currentGate.get().await(5, TimeUnit.SECONDS) })
        }
        suspend fun awaitFinished() = withTimeout(5_000) {
            while (withContext(Dispatchers.Main) { vm.isStreaming }) delay(10)
        }

        try {
            delay(100)
            val continueConversation = repository.createConversation(
                ConversationMode.CREATIVE, "OPENCODE_GO", "deepseek-flash", title = "Continue concurrency",
            )
            val user = repository.appendUserMessage(continueConversation, "continue this")
            val continuedId = repository.beginAssistantMessage(continueConversation, user.id, "deepseek-flash")
            repository.finishMessage(
                continuedId, "saved prefix", null, MessageStatus.STOPPED,
                timeline = listOf(ReplyBlock.Text("saved prefix")),
            )

            withContext(Dispatchers.Main) { vm.openConversation(continueConversation) }
            awaitIdle()
            val continued = withContext(Dispatchers.Main) { vm.messages.last() }
            withContext(Dispatchers.Main) {
                vm.continueMessage(continued)
                vm.continueMessage(continued)
            }
            awaitStreamStart(1)
            delay(100)
            check(requestCount.get() == 1) { "Duplicate continuation started ${requestCount.get()} requests" }
            currentRelease.get().countDown()
            awaitFinished()
            val continuedPath = repository.loadActivePath(continueConversation)
            check(continuedPath.size == 2 && continuedPath.last().id == continuedId) { "Continuation changed the message path" }
            check(continuedPath.last().status == MessageStatus.DONE && continuedPath.last().content == "saved prefix") {
                "Continuation did not preserve and finish the original prefix"
            }
            passed("duplicate continue calls produce one request and preserve the original reply")

            val regenerateConversation = repository.createConversation(
                ConversationMode.CREATIVE, "OPENCODE_GO", "deepseek-flash", title = "Regenerate concurrency",
            )
            val regenerateUser = repository.appendUserMessage(regenerateConversation, "regenerate this")
            val oldReply = repository.beginAssistantMessage(regenerateConversation, regenerateUser.id, "deepseek-flash")
            repository.finishMessage(oldReply, "old answer", null, MessageStatus.DONE)

            withContext(Dispatchers.Main) { vm.openConversation(regenerateConversation) }
            awaitIdle()
            val target = withContext(Dispatchers.Main) { vm.messages.last() }
            val previousCount = requestCount.get()
            currentGate.set(CountDownLatch(1))
            currentRelease.set(CountDownLatch(1))
            withContext(Dispatchers.Main) {
                vm.regenerate(target)
                vm.regenerate(target)
            }
            awaitStreamStart(previousCount + 1)
            delay(100)
            check(requestCount.get() == previousCount + 1) {
                "Duplicate regenerate started ${requestCount.get() - previousCount} requests"
            }
            currentRelease.get().countDown()
            awaitFinished()
            val path = repository.loadActivePath(regenerateConversation)
            check(path.count { it.role == MessageRole.ASSISTANT } == 1) { "Regenerate created duplicate active replies" }
            check(db.messageDao().countFor(regenerateConversation) == 3) { "Regenerate created duplicate reply rows" }
            passed("duplicate regenerate calls create one variant and one API request")
        } finally {
            currentRelease.get().countDown()
            withContext(Dispatchers.Main) { vm.stop() }
            db.close()
            settingsScope.cancel()
            root.deleteRecursively()
        }
    }
}
