package com.simplechat.app

import android.app.Application
import android.content.Context
import com.simplechat.app.data.ChatRepository
import com.simplechat.app.data.LocaleHelper
import com.simplechat.app.data.Res
import com.simplechat.app.data.SettingsStore
import com.simplechat.app.db.AppDatabase
import com.simplechat.app.net.ChatApi
import com.simplechat.app.net.SseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(newBase: Context) {
        // 先按语言设置包一层：数据层 / ViewModel 取词（Res）都走这个 context。
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        Res.init(this)
        container = AppContainer(this)

        // 把 v1.7 之前留在 DataStore 里的**明文** API Key 就地加密。
        // 一次性动作，放在进程启动时做，不与任何界面竞争。
        CoroutineScope(Dispatchers.IO).launch {
            container.settingsStore.migrateLegacyApiKey()
        }
    }
}

/**
 * 手写依赖容器。不引入 Hilt / Koin —— 依赖少到这程度，框架是负担。
 * 各 Store 与 Repository 随里程碑逐步填充。
 */
class AppContainer(private val app: Application) {

    val json: Json by lazy { ChatApi.defaultJson() }

    val httpClient: OkHttpClient by lazy { ChatApi.defaultClient() }

    private val sseClient: SseClient by lazy { SseClient(httpClient, json) }

    val chatApi: ChatApi by lazy { ChatApi(httpClient, sseClient, json) }

    val settingsStore: SettingsStore by lazy { SettingsStore(app) }

    val database: AppDatabase by lazy { AppDatabase.build(app) }

    val chatRepository: ChatRepository by lazy { ChatRepository(database) }

    /**
     * OpenCode Go 的路由标识（`x-opencode-session`），安装级稳定即可。
     * 后续会持久化到 DataStore，避免每次启动变化。
     */
    val openCodeSessionId: String by lazy { java.util.UUID.randomUUID().toString() }
}
