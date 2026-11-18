package com.simplechat.app

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.simplechat.app.data.*
import com.simplechat.app.net.*
import com.simplechat.app.ui.chat.userContent
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

internal object ServiceApiRegression {
    suspend fun run(context: Context, status: (String) -> Unit) {
        val root = File(context.cacheDir, "service-regression-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getApplicationContext() = this
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "prefs.preferences_pb") })
            val store = SettingsStore(ctx, preferences)
            store.migrateApiProfiles()
            store.setApiKey("chat-fixture")
            val a = store.flow.first().activeApiProfileId
            val config = ServiceApiConfig(enabled = true, baseUrl = "https://api.deepseek.com", reuseChatKey = false,
                apiKeyEncrypted = store.encryptServiceKey("file-fixture"))
            store.setServiceApi(true, config, a)
            store.setServiceApi(false, config.copy(count = 7), a)
            check(store.flow.first().filesApi.key("unused") == "file-fixture")
            store.createApiProfile("B")
            val b = store.flow.first().activeApiProfileId
            check(!store.flow.first().filesApi.enabled && !store.flow.first().searchApi.enabled)
            store.setServiceApi(true, config, a)
            check(!store.flow.first().filesApi.enabled)
            store.selectApiProfile(a)
            check(store.flow.first().filesApi == config && store.flow.first().searchApi.count == 7)
            store.createApiProfile("copy", true)
            check(store.flow.first().filesApi == config)
            store.selectApiProfile(b); store.selectApiProfile(a)
            check(SettingsStore(ctx, preferences).flow.first().filesApi.key("unused") == "file-fixture")
            val disk = File(root, "prefs.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
            check(!disk.contains("chat-fixture") && !disk.contains("file-fixture"))
            status("independent tool config: encryption, restart, profile switching/copy and stale-save guard")

            val uploads = AtomicInteger()
            val client = ChatApi.defaultClient().newBuilder().addInterceptor { chain ->
                val request = chain.request()
                check(request.header("Authorization") == "Bearer file-fixture")
                val buffer = Buffer(); request.body!!.writeTo(buffer)
                val body = buffer.readUtf8()
                check(body.indexOf("name=\"purpose\"") < body.indexOf("name=\"file\""))
                check(body.contains("user_data") && body.contains("首段") && body.contains("尾段"))
                val number = uploads.incrementAndGet()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{\"id\":\"file-api-$number\"}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            val api = ServiceApi(client)
            val settings = StoredSettings(apiKey = "chat-fixture", filesApi = config)
            val longText = "首段" + "这是百万字符的长上下文。".repeat(100_000) + "尾段"
            val start = System.nanoTime()
            val first = FileApiTextCache(ctx, api).prepare(longText, emptyList(), settings)
            val second = FileApiTextCache(ctx, api).prepare(longText, emptyList(), settings)
            check(uploads.get() == 1 && first == second)
            val outgoing = userContent(second.first, second.second).toString()
            check(outgoing.length < 1024 && !outgoing.contains("这是百万"))
            check(longText.endsWith("尾段"))
            FileApiTextCache(ctx, api).prepare(longText + "编辑", emptyList(), settings)
            check(uploads.get() == 2)
            val countBefore = uploads.get()
            val small = FileApiTextCache(ctx, api).prepare("短文本", emptyList(), settings)
            check(small.first == "短文本" && small.second.isEmpty() && uploads.get() == countBefore)
            status("long text: ${longText.length} chars → ${outgoing.length} chars; upload once across cache recreation; edit invalidation; ${(System.nanoTime()-start)/1_000_000} ms")
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); root.deleteRecursively() }
    }
}
