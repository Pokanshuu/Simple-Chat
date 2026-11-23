package com.simplechat.app

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import androidx.room.Room
import com.simplechat.app.data.*
import com.simplechat.app.db.*
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.SseClient
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import kotlinx.serialization.json.*

/** Dependency-free device regression runner: adb shell am instrument -w <test-package>/<runner>. */
class DeviceRegression : Instrumentation() {
    private var realArchive: String? = null
    private var uiOnly = false
    private var featureTests = false
    private var searchTests = false
    private var hotfixTests = false
    private var navigationTests = false
    private var selectionTests = false
    private var shareTests = false
    private var deletionTests = false
    private var nativeShareTests = false
    private var releaseUiTests = false
    private var compactionTests = false
    private var deepSeekLive = false
    private var serviceTests = false
    private var mathTests = false
    private var composerTests = false
    private var themeInteractionTests = false
    private var codeFoldTests = false
    private var historyScrollTests = false
    private var streamConcurrencyTests = false
    private var memoryTests = false
    override fun onCreate(arguments: Bundle?) {
        realArchive = arguments?.getString("source")
        uiOnly = arguments?.getString("ui") == "true"
        featureTests = arguments?.getString("features") == "true"
        searchTests = arguments?.getString("search") == "true"
        hotfixTests = arguments?.getString("hotfix") == "true"
        navigationTests = arguments?.getString("navigation") == "true"
        selectionTests = arguments?.getString("selection") == "true"
        shareTests = arguments?.getString("share") == "true"
        deletionTests = arguments?.getString("deletion") == "true"
        nativeShareTests = arguments?.getString("nativeShare") == "true"
        releaseUiTests = arguments?.getString("releaseUi") == "true"
        compactionTests = arguments?.getString("compaction") == "true"
        deepSeekLive = arguments?.getString("deepSeekLive") == "true"
        serviceTests = arguments?.getString("services") == "true"
        mathTests = arguments?.getString("math") == "true"
        composerTests = arguments?.getString("composer") == "true"
        themeInteractionTests = arguments?.getString("themeInteractions") == "true"
        codeFoldTests = arguments?.getString("codeFold") == "true"
        historyScrollTests = arguments?.getString("historyScroll") == "true"
        streamConcurrencyTests = arguments?.getString("streamConcurrency") == "true"
        memoryTests = arguments?.getString("crossChatMemory") == "true"
        super.onCreate(arguments)
        start()
    }
    override fun onStart() {
        val output = Bundle()
        try {
            runBlocking {
                val source = realArchive
                if (deletionTests) {
                    deletionReopen()
                    MessageShareRegression.run(this@DeviceRegression, ::passed, deletionOnly = true)
                }
                else if (memoryTests) CrossChatMemoryRegression.run(this@DeviceRegression, ::passed)
                else if (streamConcurrencyTests) StreamConcurrencyRegression.run(this@DeviceRegression, ::passed)
                else if (historyScrollTests) HistoryScrollRegression.run(this@DeviceRegression, ::passed)
                else if (composerTests) ComposerRegression.run(this@DeviceRegression, ::passed)
                else if (themeInteractionTests) ThemeInteractionRegression.run(this@DeviceRegression, ::passed)
                else if (codeFoldTests) CodeFoldRegression.run(this@DeviceRegression, ::passed)
                else if (mathTests) MathLayoutRegression.run(this@DeviceRegression, ::passed)
                else if (serviceTests) {
                    ServiceApiRegression.run(targetContext, ::passed)
                    ServiceConfigUiRegression.run(this@DeviceRegression, ::passed)
                    BackupCompatibilityRegression.run(targetContext, ::passed)
                }
                else if (deepSeekLive) DeepSeekLiveRegression.run(targetContext, ::passed)
                else if (compactionTests) CompactionRegression.run(this@DeviceRegression, ::passed)
                else if (releaseUiTests) ReleaseUiRegression.run(this@DeviceRegression, ::passed)
                else if (nativeShareTests) NativeShareRenderRegression.run(this@DeviceRegression, ::passed)
                else if (shareTests) MessageShareRegression.run(this@DeviceRegression, ::passed)
                else if (selectionTests) TextSelectionRegression.run(this@DeviceRegression, ::passed)
                else if (navigationTests) {
                    SearchNavigationRegression.run(this@DeviceRegression, ::passed, com.simplechat.app.ui.theme.ThemeMode.LIGHT)
                    SearchNavigationRegression.run(this@DeviceRegression, ::passed, com.simplechat.app.ui.theme.ThemeMode.DARK)
                }
                else if (hotfixTests) HotfixRegression.run(this@DeviceRegression, ::passed)
                else if (searchTests) SearchRenderingRegression.run(this@DeviceRegression, ::passed)
                else if (featureTests) {
                    ApiProfileRegression.run(targetContext)
                    passed("legacy API configuration migration, encrypted independent profiles, switch/copy/delete/restart and stale-model protection")
                    NewFeaturesUiRegression.run(this@DeviceRegression, ::passed)
                }
                else if (uiOnly) LargeMessageUiRegression.run(this@DeviceRegression, ::passed)
                else if (source == null) regression()
                else RealArchiveStress.run(targetContext, source, ::passed)
            }
            output.putString("stream", "\nALL DEVICE REGRESSIONS PASSED\n")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("stream", "\nDEVICE REGRESSION FAILED\n${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    private fun passed(text: String) {
        sendStatus(0, Bundle().apply { putString("stream", "\nPASS $text\n") })
    }

    private suspend fun deletionReopen() {
        val name = "delete-test-${java.util.UUID.randomUUID()}.db"
        var db = AppDatabase.build(targetContext, name)
        try {
            var repo = ChatRepository(db, targetContext)
            val fork = repo.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "model", title = "Three continuations")
            val branches = ('A'..'C').map { label ->
                val question = repo.addActiveVariant(fork, MessageEntity.ROOT_PARENT, MessageRole.USER, "Question $label")
                val reply = repo.addActiveVariant(fork, question.id, MessageRole.ASSISTANT, "Reply $label")
                val followup = repo.appendUserMessage(fork, "Follow-up $label")
                Triple(question.id, reply.id, followup.id)
            }
            check(repo.activateVariantAt(fork, MessageEntity.ROOT_PARENT, 1) == branches[1].first)
            repo.deleteMessageVersions(fork, setOf(branches[1].first))
            db.close()
            db = AppDatabase.build(targetContext, name)
            repo = ChatRepository(db, targetContext)
            check(repo.loadActivePath(fork).filterNot { it.deletedNavigation }.map { it.id } == listOf(branches[1].second, branches[1].third))
            val tree = db.messageDao().getLightFor(fork).associateBy { it.id }
            branches.forEachIndexed { index, (question, reply, followup) ->
                check(tree.getValue(question).trashedAt != null)
                check(tree.getValue(reply).parentId == question)
                check(repo.activateVariantAt(fork, MessageEntity.ROOT_PARENT, index) == question)
                check(repo.loadActivePath(fork).filterNot { it.deletedNavigation }.map { it.id } == listOf(reply, followup))
            }
            check(!repo.exportConversation(fork).orEmpty().contains("Question A"))
            val entry = repo.observeMessageTrash().first().single { it.conversationId == fork }
            check(entry.versions == 3)
            repo.changeMessageTrash(listOf(entry), restore = true)
            check(repo.loadActivePath(fork).map { it.id } == branches.last().toList())
            repo.deleteMessageVersions(fork, setOf(branches.last().first))
            repo.changeMessageTrash(repo.observeMessageTrash().first().filter { it.conversationId == fork }, restore = false)
            branches.forEach { (question, reply, _) ->
                check(db.messageDao().findById(question)?.let { it.isDeletedNode && it.content.isEmpty() } == true)
                check(db.messageDao().findById(reply)?.parentId == question)
            }
            passed("all-version deletion, restore and purge preserve original branches across reopening")
        } finally {
            db.close()
            targetContext.deleteDatabase(name)
        }
    }

    private suspend fun regression() {
        val root = File(targetContext.cacheDir, "isolated-regression-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : ContextWrapper(targetContext) {
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            override fun getApplicationContext() = this
        }
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val restoredDb = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val repository = ChatRepository(db, ctx)
        val restored = ChatRepository(restoredDb, ctx)
        val official = File(ctx.cacheDir, "device-official.zip")
        val exportedJson = File(ctx.cacheDir, "device-export.json")
        val exportedZip = File(ctx.cacheDir, "device-export.zip")
        try {
            ZipOutputStream(official.outputStream().buffered()).use { zip ->
                fun text(name: String, body: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
                }
                text("manifest.json", """{"export_format_version":3}""")
                text("chat_session_index.json", """[{"id":"old"},{"id":"empty"},{"id":"orphan"},{"id":"branches"},{"id":"long"},{"id":"image"}]""")
                text("chat_sessions/old.json", """{"id":"old","title":"Old short","inserted_at":"2023-01-01T00:00:00Z","updated_at":"2023-01-01T00:00:00Z","messages":[{"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"old question"}]}]}""")
                text("chat_sessions/empty.json", """{"id":"empty","title":"Empty window","messages":[]}""")
                text("chat_sessions/orphan.json", """{"id":"orphan","title":"Orphan","messages":[{"id":1,"parent_id":0,"role":"USER","fragments":[{"type":"REQUEST","content":"saved orphan"}]}]}""")
                text("chat_sessions/branches.json", """{"id":"branches","title":"Branch search","current_message_id":3,"messages":[{"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"question"}]},{"id":2,"parent_id":1,"role":"ASSISTANT","fragments":[{"type":"RESPONSE","content":"old branch FIND_DEVICE_KEYWORD"}]},{"id":3,"parent_id":1,"role":"ASSISTANT","fragments":[{"type":"RESPONSE","content":"new branch"}]}]}""")
                zip.putNextEntry(ZipEntry("chat_sessions/long.json"))
                zip.write("""{"id":"long","title":"Long session","messages":[""".toByteArray())
                val body = "长正文😀 ".repeat(1000)
                for (index in 1..1200) {
                    if (index > 1) zip.write(','.code)
                    val parent = if (index == 1) "null" else (index - 1).toString()
                    zip.write("""{"id":$index,"parent_id":$parent,"role":"USER","fragments":[{"type":"REQUEST","content":"$body"}]}""".toByteArray())
                }
                zip.write("]}".toByteArray()); zip.closeEntry()
                text("chat_sessions/image.json", """{"id":"image","title":"Image","messages":[{"id":1,"parent_id":null,"role":"USER","fragments":[{"type":"REQUEST","content":"look"},{"type":"FILE","files":[{"is_image":true,"export_path":"large.png"}]}]}]}""")
                zip.putNextEntry(ZipEntry("large.png"))
                val buffer = ByteArray(64 * 1024) { 42 }
                repeat(400) { zip.write(buffer) }
                zip.closeEntry()
            }
            val progress = mutableListOf<Float>()
            val report = repository.importExternal(official, ctx.cacheDir, ImportMode.MERGE) { progress += it }.first
            check(report.conversations == 6 && report.failed == 0 && report.messages == 1206) { report.toString() }
            check(db.conversationDao().count() == 6)
            check(db.messageDao().countFor("deepseek-long") == 1200)
            check(db.conversationDao().findById("deepseek-empty") != null)
            check(report.repairedMessages == 1)
            check(progress.zipWithNext().all { (a, b) -> a <= b })
            check(repository.loadActivePath("deepseek-orphan").single().content == "saved orphan")
            passed("fresh incremental official import: 6/6 windows, 1206/1206 messages, empty/old/orphan/large image preserved")

            val repeated = repository.importExternal(official, ctx.cacheDir, ImportMode.MERGE).first
            check(repeated.alreadyPresent == 6 && repeated.conversations == 0)
            passed("repeat incremental import is idempotent")

            val hits = repository.searchConversations("FIND_DEVICE_KEYWORD", includeInactiveBranches = true).first()
            val hit = hits.single()
            check(hit.messageId == "deepseek-branches-2")
            check(repository.searchConversations(
                "FIND_DEVICE_KEYWORD",
            ).first().isEmpty())
            check(repository.searchConversations(
                "new branch",
                includeInactiveBranches = false,
            ).first().single().messageId == "deepseek-branches-3")
            repository.revealSearchMessage(hit.conversation.id, hit.messageId!!)
            check(repository.loadActivePath(hit.conversation.id).last().id == hit.messageId)
            check(repository.searchConversations("%").first().isEmpty())
            passed("search returns exact message ID, restores inactive branch, treats percent literally")

            // Exact Android empty-BLOB behavior, including a row larger than CursorWindow.
            val id = repository.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "deepseek-flash", title = "Device empty fields")
            val huge = "正文\u0000😀".repeat(400_000)
            val user = repository.appendUserMessage(id, huge)
            check(user.content == huge)
            repository.beginAssistantMessage(id, user.id)
            repository.exportBackup(exportedJson)
            check(exportedJson.length() > 15_000_000)
            val roundTrip = restored.importExternal(exportedJson, ctx.cacheDir, ImportMode.MERGE).first
            check(roundTrip.failed == 0 && roundTrip.conversations == 7) { roundTrip.toString() }
            val path = restored.loadActivePath(id)
            check(path.first().content == huge)
            check(path.last().content.isEmpty())
            passed("Android null/empty BLOB export and >CursorWindow prompt round-trip preserve NUL/emoji")

            repository.exportArchive(exportedZip)
            ZipFile(exportedZip).use { zip ->
                check(zip.getEntry("backup.json").size > 15_000_000)
                check(zip.getEntry("manifest.json") != null)
            }
            passed("ZIP file export is nonempty and includes full backup")

            val shortId = "deepseek-old"
            val captured = java.util.Collections.synchronizedList(mutableListOf<JsonObject>())
            val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                val body = okio.Buffer()
                request.body!!.writeTo(body)
                val payload = Json.parseToJsonElement(body.readUtf8()).jsonObject
                captured += payload
                check(payload["messages"]!!.jsonArray.any { it.jsonObject["role"]?.jsonPrimitive?.content == "user" })
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("data: {\"choices\":[{\"delta\":{\"content\":\"mock response\"}}]}\n\ndata: [DONE]\n\n"
                        .toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            val json = ChatApi.defaultJson()
            val api = ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json)
            val store = SettingsStore(ctx)
            store.setProvider("OPENCODE_GO", "https://device-test.invalid", "deepseek-flash")
            store.setApiStyle("OPENAI")
            val vm = withContext(Dispatchers.Main) { ChatViewModel(api, store, repository, "device-test") }
            delay(300)
            withContext(Dispatchers.Main) { vm.openConversation(shortId) }
            withTimeout(20_000) { while (withContext(Dispatchers.Main) { vm.switching }) delay(20) }
            var persistedPrompts = 0
            repeat(20) {
                withContext(Dispatchers.Main) { vm.send("device prompt $it", onPersisted = { persistedPrompts++ }) }
                delay(300)
                withContext(Dispatchers.Main) { vm.stop() }
                delay(50)
            }
            check(db.messageDao().countFor(shortId) >= 21)
            check(persistedPrompts == 20)
            withContext(Dispatchers.Main) {
                repeat(30) { vm.openConversation(if (it % 2 == 0) "deepseek-branches" else "deepseek-old") }
            }
            withTimeout(20_000) { while (withContext(Dispatchers.Main) { vm.switching }) delay(20) }
            check(withContext(Dispatchers.Main) { vm.currentConversationId } == "deepseek-old")
            check(captured.size >= 20) { "Expected mock network sends, got ${captured.size}" }
            passed("20 sends/stops reach a local mock server and 30 rapid switches complete without exceptions")
            val beforeCompact = captured.size
            withContext(Dispatchers.Main) { vm.compactContext() }
            withTimeout(20_000) { while (withContext(Dispatchers.Main) { vm.compacting }) delay(30) }
            check(withContext(Dispatchers.Main) { vm.compaction != null }) { "Compaction failed" }
            withContext(Dispatchers.Main) { vm.send("AFTER_COMPACT_DEVICE") }
            withTimeout(20_000) { while (captured.size < beforeCompact + 2 || withContext(Dispatchers.Main) { vm.isStreaming }) delay(30) }
            val sentAfterCompact = captured.last()["messages"]!!.jsonArray
            check(sentAfterCompact.any { it.jsonObject["content"]?.toString()?.contains("AFTER_COMPACT_DEVICE") == true })
            passed("context compaction followed by send retains the new user prompt in the real serialized request")
            for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
                val branchId = repository.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "deepseek-flash", title = "Branch deletion")
                val parent = if (role == MessageRole.USER) MessageEntity.ROOT_PARENT
                    else repository.appendUserMessage(branchId, "parent").id
                val branches = (1..4).map { number ->
                    val branch = repository.addActiveVariant(branchId, parent, role, "branch-test $number")
                    val childRole = if (role == MessageRole.USER) MessageRole.ASSISTANT else MessageRole.USER
                    val child = repository.addActiveVariant(branchId, branch.id, childRole, "child $number")
                    branch.id to child.id
                }
                suspend fun awaitBranch(index: Int, position: Int, count: Int, child: Int = index) {
                    withTimeout(4_000) {
                        while (!withContext(Dispatchers.Main) {
                            val active = vm.messages.firstOrNull { it.id == branches[index].first }
                            active?.variantIndex == position && active.variantCount == count &&
                                vm.messages.lastOrNull()?.id == branches[child].second
                        }) delay(20)
                    }
                }
                suspend fun switchTo(position: Int) = withContext(Dispatchers.Main) {
                    vm.switchVariant(vm.messages.single { it.parentId == parent }, position)
                }
                withContext(Dispatchers.Main) { vm.openConversation(branchId) }
                awaitBranch(3, 3, 4)
                switchTo(2)
                awaitBranch(2, 2, 4)
                withContext(Dispatchers.Main) { vm.deleteMessage(vm.messages.single { it.parentId == parent }) }
                withTimeout(4_000) {
                    while (!withContext(Dispatchers.Main) { vm.messages.any { it.deletedNavigation } }) delay(20)
                }
                awaitBranch(2, 2, 4)
                switchTo(0)
                awaitBranch(0, 0, 4)
                switchTo(3)
                awaitBranch(3, 3, 4)
                val hits = db.conversationDao().search("branch-test", includeInactiveBranches = true).first()
                check(hits.none { it.conversation.id == branchId && it.messageId != null })
            }
            passed("hide all versions, retain each continuation and exclude recycled content from search")
            withContext(Dispatchers.Main) { vm.newConversation() }
            DataIntegrityRegression.run(ctx, context.assets, repository, db)
            passed("same-title imports, global ID collision rollback, preset links, shared fork images, huge edits and schema 8-to-9 migration")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_test_send BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END")
            try {
                var clearDraft = false
                withContext(Dispatchers.Main) { vm.send("keep this draft", onPersisted = { clearDraft = true }) }
                delay(500)
                check(!clearDraft && withContext(Dispatchers.Main) { vm.banner != null })
            } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_test_send") }
            passed("failed persistence does not acknowledge or clear the submitted draft")
            // Write a small UI fixture into the application's regular debug DB only if it is empty.
            val appRepo = (targetContext.applicationContext as App).container.chatRepository
            if (appRepo.conversationCount() == 0) {
                val fixtureId = appRepo.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "deepseek-flash", title = "DEVICE UI regression")
                val prompt = appRepo.appendUserMessage(fixtureId, "Search this phrase: DEVICE_UI_KEYWORD")
                val reply = appRepo.beginAssistantMessage(fixtureId, prompt.id)
                appRepo.finishMessage(reply, "Device regression ready. DEVICE_UI_KEYWORD", null, MessageStatus.DONE)
            }
        } finally {
            db.close(); restoredDb.close()
            official.delete(); exportedJson.delete(); exportedZip.delete()
            root.deleteRecursively()
        }
    }
}
