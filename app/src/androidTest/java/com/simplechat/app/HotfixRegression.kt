package com.simplechat.app

import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.simplechat.app.data.*
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.*
import com.simplechat.app.net.anthropic.AnthropicApi
import com.simplechat.app.ui.chat.*
import com.simplechat.app.ui.history.*
import com.simplechat.app.ui.theme.SimpleChatTheme
import com.simplechat.app.ui.theme.ThemeMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Collections

/** Actual Android codecs, Room query, serialized protocol payloads and production Compose screens. */
internal object HotfixRegression {
    suspend fun run(instrumentation: Instrumentation, status: (String) -> Unit) {
        val root = File(instrumentation.targetContext.cacheDir, "hotfix-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext() = this
            override fun getFilesDir() = root
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "hotfix.preferences_pb") })
        val store = SettingsStore(ctx, prefs)
        store.setProvider("OPENCODE_GO", "https://mock.invalid", "kimi-k3")
        store.setApiStyle("OPENAI")
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val repo = ChatRepository(db, ctx)
        val payloads = Collections.synchronizedList(mutableListOf<JsonObject>())
        val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
            val buffer = okio.Buffer().also { chain.request().body!!.writeTo(it) }
            val payload = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            payloads += payload
            // Reject any outgoing image that cannot be decoded, has the wrong MIME or isn't normalized.
            fun verify(node: JsonElement) {
                when (node) {
                    is JsonArray -> node.forEach(::verify)
                    is JsonObject -> {
                        if (node["type"]?.jsonPrimitive?.content == "image_url") {
                            val url = node["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                            check(url.startsWith("data:image/jpeg;base64,"))
                            val bytes = Base64.getDecoder().decode(url.substringAfter(','))
                            check(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { it.recycle() } != null)
                        }
                        if (node["type"]?.jsonPrimitive?.content == "image") {
                            val source = node["source"]!!.jsonObject
                            check(source["media_type"]!!.jsonPrimitive.content == "image/jpeg")
                            val bytes = Base64.getDecoder().decode(source["data"]!!.jsonPrimitive.content)
                            check(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { it.recycle() } != null)
                        }
                        node.values.forEach(::verify)
                    }
                    else -> Unit
                }
            }
            verify(payload)
            val anthropic = chain.request().url.encodedPath.contains("messages")
            val body = if (anthropic) "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"mock reply\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                else "data: {\"choices\":[{\"delta\":{\"content\":\"mock reply\"}}]}\n\ndata: [DONE]\n\n"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val json = ChatApi.defaultJson()
        val vm = withContext(Dispatchers.Main) { ChatViewModel(ChatApi(client, SseClient(client, json), AnthropicApi(client, json), json), store, repo, "hotfix") }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val automation = instrumentation.uiAutomation
        suspend fun nodes(): List<AccessibilityNodeInfo> {
            runCatching { automation.clearCache() }
            val all = mutableListOf<AccessibilityNodeInfo>()
            fun walk(n: AccessibilityNodeInfo) { all += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
            automation.rootInActiveWindow?.let(::walk)
            return all
        }
        suspend fun tap(label: String) {
            val n = nodes().firstOrNull { it.text?.toString() == label } ?: error("Missing UI: $label")
            var action: AccessibilityNodeInfo? = n
            while (action != null && !action.isClickable) action = action.parent
            check(action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            delay(250)
        }
        suspend fun open(id: String) {
            withContext(Dispatchers.Main) { vm.openConversation(id) }
            withTimeout(10_000) { while (withContext(Dispatchers.Main) { vm.switching }) delay(30) }
        }
        suspend fun send(text: String) {
            val count = payloads.size
            withContext(Dispatchers.Main) { vm.send(text) }
            withTimeout(10_000) { while (payloads.size == count || withContext(Dispatchers.Main) { vm.isStreaming }) delay(30) }
            check(withContext(Dispatchers.Main) { vm.messages.last().status == MessageStatus.DONE })
        }
        try {
            delay(300)
            val png = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
            }
            val bad = png.copyOf(33) // Valid signature and IHDR dimensions, missing all pixel data.
            check(ApiImage.prepare(bad) == null)
            check(ApiImage.prepare("not an image".toByteArray()) == null)
            val normalized = checkNotNull(ApiImage.prepare(png))
            check(normalized.mediaType == "image/jpeg" && normalized.transcoded)
            val decodedBytes = Base64.getDecoder().decode(normalized.base64)
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size))
            check(decoded.getPixel(0, 0) == android.graphics.Color.WHITE); decoded.recycle()
            val oversized = Bitmap.createBitmap(4800, 8, Bitmap.Config.ARGB_8888).let { bitmap ->
                ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
            }
            val resized = Base64.getDecoder().decode(checkNotNull(ApiImage.prepare(oversized)).base64)
            val sized = checkNotNull(BitmapFactory.decodeByteArray(resized, 0, resized.size))
            check(sized.width <= ApiImage.ApiImageMaxSide); sized.recycle()
            val cmyk = instrumentation.context.assets.open("synthetic-cmyk.jpg").use { it.readBytes() }
            check(ApiImage.prepare(cmyk)?.mediaType == "image/jpeg")
            val webp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).let { bitmap ->
                ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out); bitmap.recycle(); out.toByteArray() }
            }
            check(ApiImage.prepare(webp)?.mediaType == "image/jpeg")
            status("actual Android decode: header-only PNG rejected; transparent PNG, CMYK JPEG and WebP normalized; oversized dimensions sampled")

            val path = checkNotNull(AttachmentFiles.save(ctx, png, "image/png"))
            val badPath = checkNotNull(AttachmentFiles.save(ctx, bad, "image/png"))
            val id = repo.createConversation(ConversationMode.CREATIVE, "OPENCODE_GO", "kimi-k3", title = "Image regression")
            repo.appendUserMessage(id, "", listOf(Attachment(Attachment.Kind.IMAGE, "good", "image/unknown", "", path = path)))
            repo.appendUserMessage(id, "bad historical image", listOf(Attachment(Attachment.Kind.IMAGE, "broken", "image/png", "", path = badPath)))
            for (style in listOf("OPENAI", "ANTHROPIC")) {
                store.setApiStyle(style); delay(200); open(id); send("continue $style")
                val serialized = payloads.last().toString()
                check(serialized.contains(if (style == "OPENAI") "image_url" else "\"type\":\"image\""))
                check(withContext(Dispatchers.Main) { vm.imageSkipNotice } != null)
            }
            check(AttachmentFiles.read(ctx, path)!!.contentEquals(png))
            check(AttachmentFiles.read(ctx, badPath)!!.contentEquals(bad))
            status("OpenAI and Anthropic real requests: stored image-only history retained, malformed historical image skipped, originals preserved")
            instrumentation.runOnMainSync { activity.setContent { SimpleChatTheme { ChatScreen(vm, {}, {}) } } }
            instrumentation.waitForIdleSync(); delay(300)
            val never = Res.get(R.string.images_never_remind)
            val neverNode = nodes().first { it.text?.toString() == never }
            val closeNode = nodes().first { it.contentDescription?.toString() == Res.get(R.string.chat_banner_close) }
            val nr = Rect().also { neverNode.getBoundsInScreen(it) }; val cr = Rect().also { closeNode.getBoundsInScreen(it) }
            check(nr.right <= cr.left)
            tap(never)
            withTimeout(5000) { while (store.flow.first().showImageSkipNotice) delay(30) }
            send("muted next send")
            check(withContext(Dispatchers.Main) { vm.imageSkipNotice } == null)
            check(!SettingsStore(ctx, prefs).flow.first().showImageSkipNotice)
            store.setShowImageSkipNotice(true); delay(150)
            store.setModel("deepseek-flash"); delay(150); send("non vision send")
            check(!payloads.last().toString().contains("\"type\":\"image\""))
            check(withContext(Dispatchers.Main) { vm.imageSkipNotice } != null)
            status("production banner: never-remind appears left of X, persists and suppresses next send; non-vision model skips all images; notices can be restored")

            withContext(Dispatchers.Main) { vm.newConversation() }
            val cid = repo.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "deepseek-flash", title = "Search window")
            val u = repo.appendUserMessage(cid, "needle 用户问题")
            val a = repo.beginAssistantMessage(cid, u.id)
            repo.finishMessage(a, "needle AI answer", null, MessageStatus.DONE)
            // An inactive assistant sibling must also be individually searchable.
            val entity = db.messageDao().findById(a)!!
            db.messageDao().upsert(entity.copy(id = "search-inactive", variantIndex = 1, active = false, content = "needle other variant", createdAt = entity.createdAt + 1))
            val title = repo.createConversation(ConversationMode.CREATIVE, "DEEPSEEK", "deepseek-flash", title = "needle title only")
            val hits = repo.searchConversations("needle").first()
            check(hits.size == 4 && hits.filter { it.conversation.id == cid }.size == 3)
            check(hits.single { it.messageId == u.id }.messageRole == "user")
            check(hits.single { it.messageId == a }.messageRole == "assistant")
            check(hits.single { it.messageId == "search-inactive" }.variantCount == 2)
            check(hits.single { it.conversation.id == title }.messageId == null)
            check(hits.filter { it.messageId != null }.all { it.messageCreatedAt != null && it.snippet!!.length < 200 })
            check(repo.searchConversations("needle", 2).first().size == 2)
            check(repo.searchConversations("").first().isEmpty())
            var clicked: String? = null
            open(cid)
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
                val selection = mutableStateOf<String?>(null)
                instrumentation.runOnMainSync { activity.setContent { SimpleChatTheme(themeMode = theme) {
                    ConversationSearchPage(hits, "needle", {}, {}, {
                        clicked = it.messageId; selection.value = it.searchResultKey
                    }, {}, {}, {}, selectedResultKey = selection.value)
                } } }
                instrumentation.waitForIdleSync(); delay(250)
                val bitmap = checkNotNull(automation.takeScreenshot())
                val rows = nodes().filter { it.text?.toString() == "Search window" && it.isVisibleToUser }
                check(rows.size == 3)
                rows.forEach { titleNode ->
                    var row: AccessibilityNodeInfo? = titleNode
                    while (row != null && !row.isClickable) row = row.parent
                    val bounds = Rect().also { checkNotNull(row).getBoundsInScreen(it) }
                    // No pointer/touch has entered the list: all result backgrounds match the page.
                    val outside = bitmap.getPixel(2, bounds.centerY())
                    val inside = bitmap.getPixel(bounds.right - 24, bounds.centerY())
                    check(outside == inside) { "Idle search result has selection background: $theme" }
                }
                File(instrumentation.targetContext.cacheDir, "search-idle-${theme.name.lowercase()}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
                val selectedHit = hits.first { it.messageId == a }
                instrumentation.runOnMainSync { selection.value = selectedHit.searchResultKey }
                instrumentation.waitForIdleSync(); delay(200)
                val selectedImage = checkNotNull(automation.takeScreenshot())
                var colored = 0
                nodes().filter { it.text?.toString() == "Search window" && it.isVisibleToUser }.forEach { titleNode ->
                    var row: AccessibilityNodeInfo? = titleNode
                    while (row != null && !row.isClickable) row = row.parent
                    val bounds = Rect().also { checkNotNull(row).getBoundsInScreen(it) }
                    if (selectedImage.getPixel(2, bounds.centerY()) != selectedImage.getPixel(bounds.right - 24, bounds.centerY())) colored++
                }
                check(colored == 1) { "Selected $colored search rows, expected exactly one: $theme" }
                File(instrumentation.targetContext.cacheDir, "search-selected-${theme.name.lowercase()}.png").outputStream().use {
                    selectedImage.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                selectedImage.recycle()
            }
            status("search row backgrounds in light/dark: initially none selected; exactly one message selected among three hits from the same conversation")
            instrumentation.waitForIdleSync(); delay(300)
            val texts = nodes().mapNotNull { it.text?.toString() }
            check(texts.any { it.contains(Res.get(R.string.search_role_user)) })
            check(texts.any { it.contains(Res.get(R.string.search_role_assistant)) })
            val expectedDate = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(entity.createdAt))
            check(texts.any { it == expectedDate })
            tap("Search window")
            check(clicked != null)
            repo.revealSearchMessage(cid, "search-inactive")
            check(repo.loadActivePath(cid).last().id == "search-inactive")
            status("Room and search UI: all messages in one window, user/AI roles, message dates, inactive variants, title-only result, bounded previews, per-row jump")
            repeat(105) { repo.appendUserMessage(cid, "needle paging $it") }
            val history = withContext(Dispatchers.Main) { HistoryViewModel(repo).also { it.updateQuery("needle") } }
            val firstPage = withTimeout(10_000) { history.searchPage.first { it.hits.size == 100 } }
            check(firstPage.hasMore)
            withContext(Dispatchers.Main) { history.loadMoreSearchResults() }
            val all = withTimeout(10_000) { history.searchPage.first { it.hits.size == 109 } }
            check(!all.hasMore && all.hits.map { it.messageId ?: it.conversation.id }.distinct().size == 109)
            withContext(Dispatchers.Main) {
                history.updateQuery("not-present-keyword")
                check(history.searchPage.value.isLoading) { "A new query must enter loading immediately" }
                check(history.searchPage.value.hits.isEmpty()) { "Stale results must not appear for a new query" }
            }
            withTimeout(10_000) { history.searchPage.first { !it.isLoading && it.hits.isEmpty() } }
            status("search shows loading before the empty result, clears stale hits, and loads all 109 hits without duplicates")
        } finally {
            withContext(Dispatchers.Main) { vm.stop(); activity.finish() }
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close(); root.deleteRecursively()
        }
    }
}
