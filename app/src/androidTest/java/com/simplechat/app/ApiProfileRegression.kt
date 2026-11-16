package com.simplechat.app

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.simplechat.app.data.*
import com.simplechat.app.net.ProviderKind
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

internal object ApiProfileRegression {
    suspend fun run(context: Context) {
        val root = File(context.cacheDir, "profile-regression-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getApplicationContext() = this
        }
        val file = File(root, "datastore/simplechat_settings.preferences_pb")
        val legacyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val legacyStore = PreferenceDataStoreFactory.create(scope = legacyScope, produceFile = { file })
        legacyStore.edit { prefs ->
            prefs[stringPreferencesKey("provider_id")] = "DEEPSEEK"
            prefs[stringPreferencesKey("base_url")] = "https://api.deepseek.com"
            prefs[stringPreferencesKey("model_id")] = "legacy-model"
            prefs[stringPreferencesKey("api_key")] = "fixture-legacy-key"
        }
        legacyScope.coroutineContext[Job]!!.cancelAndJoin()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val preferences = PreferenceDataStoreFactory.create(scope = testScope, produceFile = { file })
        val store = SettingsStore(ctx, preferences)
        store.migrateApiProfiles()
        var stored = store.flow.first()
        check(stored.apiKey == "fixture-legacy-key" && stored.modelId == "legacy-model")
        val a = stored.activeApiProfileId
        check(stored.apiProfiles.size == 1 && stored.apiProfiles.single().apiKeyEncrypted.isNotBlank())
        store.setApiStyle("ANTHROPIC")
        store.setModelIds(listOf("a1", "a2"))
        store.setTemperature(0.4f)
        store.setAutoReadAloud(true)
        store.createApiProfile("Server B")
        stored = store.flow.first()
        val b = stored.activeApiProfileId
        check(stored.apiKey.isEmpty() && stored.baseUrl.isEmpty() && stored.modelId.isEmpty())
        store.setBaseUrl("https://b.invalid/v1")
        store.setModel("b-model")
        store.setApiKey("fixture-key-b")
        store.setModelIds(listOf("b1"))
        store.setApiStyle("OPENAI")
        store.selectApiProfile(a)
        stored = store.flow.first()
        check(stored.apiKey == "fixture-legacy-key" && stored.modelId == "legacy-model" && stored.apiStyleId == "ANTHROPIC")
        check(stored.modelIds == listOf("a1", "a2") && stored.temperature == 0.4f && stored.autoReadAloud)
        store.setModelIds(listOf("stale-b-result"), b, "https://b.invalid/v1")
        check(store.flow.first().modelIds == listOf("a1", "a2"))
        store.selectApiProfile(b)
        check(store.flow.first().apiKey == "fixture-key-b")
        check(store.flow.first().modelIds == listOf("b1"))
        store.createApiProfile("B copy", copyCurrent = true)
        val copyId = store.flow.first().activeApiProfileId
        check(store.flow.first().apiKey == "fixture-key-b")
        store.setApiKey("fixture-key-copy")
        store.selectApiProfile(b)
        check(store.flow.first().apiKey == "fixture-key-b")
        store.renameApiProfile(copyId, "Renamed copy")
        store.deleteApiProfile(copyId)
        check(store.flow.first().apiProfiles.none { it.id == copyId })
        store.selectProvider(ProviderKind.DEEPSEEK)
        check(store.flow.first().apiKey == "fixture-legacy-key")
        store.selectProvider(ProviderKind.CUSTOM)
        check(store.flow.first().apiKey == "fixture-key-b")
        val recreated = SettingsStore(ctx, preferences).flow.first()
        check(recreated.activeApiProfileId == b && recreated.apiKey == "fixture-key-b" && recreated.autoReadAloud)
        val diskText = file.readBytes().toString(Charsets.ISO_8859_1)
        check(listOf("fixture-legacy-key", "fixture-key-b", "fixture-key-copy").none { diskText.contains(it) })
        store.deleteApiProfile(b)
        check(store.flow.first().activeApiProfileId == a)
        check(runCatching { store.deleteApiProfile(a) }.isFailure)
        store.setAutoReadAloud(false)
        check(!store.flow.first().autoReadAloud)
        testScope.coroutineContext[Job]!!.cancelAndJoin()
        root.deleteRecursively()
    }
}
