package com.simplechat.app.data

import android.content.Context
import com.simplechat.app.net.ApiStyle
import com.simplechat.app.net.ProviderKind
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "simplechat_settings",
)

/** 语言档位（[AppLanguage] 的枚举名）。抽到文件级，供 [readStoredLanguageSync] 共用。 */
private val LANGUAGE_KEY = stringPreferencesKey("language_id")

/**
 * 「这个端点有哪些模型」的持久化格式（换行分隔）。
 *
 * 为什么不存 JSON：这里只有一串 id，没有结构；换行在模型 id 里不可能出现
 * （OpenAI 兼容端点用 `[a-zA-Z0-9._:/-]`），于是编解码就是两行 `map`，
 * 而 JSON 要引解析器、还要处理坏数据。读取侧对任何异常输入都只说"没有列表"。
 */
internal object ModelIdList {

    /**
     * 上限。正常端点几十个，这里只为拦住"网关把整张目录表倒出来"的极端情况 ——
     * 几千个 id 会撑大 DataStore，而选择器里也没人翻得到底。
     */
    const val MAX_IDS = 500

    fun encode(ids: List<String>): String = ids
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_IDS)
        .joinToString("\n")

    fun decode(raw: String?): List<String> = raw
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.distinct()
        ?.take(MAX_IDS)
        ?.toList()
        .orEmpty()
}

/**
 * 同步读语言档位 —— 只为 `attachBaseContext`：那时一切异步入口都还没起来。
 * 值就一行，阻塞读在毫秒级；读不出来（IO 异常）按 [AppLanguage.SYSTEM] 处理，不拦启动。
 */
internal fun readStoredLanguageSync(context: Context): AppLanguage = runCatching {
    runBlocking {
        AppLanguage.fromId(context.settingsDataStore.data.first()[LANGUAGE_KEY])
    }
}.getOrDefault(AppLanguage.SYSTEM)

/** 持久化的全局配置。 */
data class StoredSettings(
    val providerId: String = "DEEPSEEK",
    val baseUrl: String = "https://api.deepseek.com",
    val modelId: String = "deepseek-flash",
    val apiKey: String = "",
    val apiProfiles: List<ApiProfile> = emptyList(),
    val activeApiProfileId: String = "legacy",
    val autoReadAloud: Boolean = false,
    val showImageSkipNotice: Boolean = true,
    val reasoningLevelId: String = "OFF",
    val temperature: Float = 1.0f,
    val topP: Float = 1.0f,
    val maxTokens: Int = 0,
    /** 外观：SYSTEM / LIGHT / DARK。 */
    val themeModeId: String = "SYSTEM",
    /** 主题色：AccentColor.name，见 ui/theme/Theme.kt。 */
    val accentColorId: String = "BLUE",
    /** 字号倍率。 */
    val fontScale: Float = 1.0f,
    /** 字号是否跟随系统辅助功能设置。 */
    val followSystemFont: Boolean = true,
    /** 思考内容是否自动折叠（关闭则默认展开，需手动收起）。 */
    val autoCollapseThinking: Boolean = true,
    /** 新对话默认模式：CREATIVE / ROLEPLAY。 */
    val defaultModeId: String = "CREATIVE",
    /** 界面语言：AppLanguage 的枚举名，见 data/LocaleHelper.kt。 */
    val languageId: String = "SYSTEM",
    /**
     * 协议通道：`AUTO` / `OPENAI` / `ANTHROPIC`。
     *
     * `AUTO` 按端点解析 —— DeepSeek 官方走扩展通道，其余走 OpenAI 兼容（见 `ApiStyle`）。
     */
    val apiStyleId: String = "AUTO",
    /**
     * 智能搜索（联网搜索）开关。
     *
     * 默认**关**：搜索会让这一轮变慢、变贵，而长文创作绝大多数轮次用不上。
     * 与官方 Chat Demo 的默认值一致。
     */
    val webSearch: Boolean = false,
    /**
     * 地区（用于「时间地点提醒」里的地点那一项），自由文本，留空则不填。
     *
     * 不做省/市二级联动：那套 13KB 的数据与三级选择器，收益远小于维护成本，
     * 而提醒里这一项本来就只是给模型一个「用户在哪儿」的粗略印象。
     */
    val locationRegion: String = "",
    /**
     * 朗读音色。
     *
     * - [ttsVoiceName]：系统 TTS 引擎里的 `Voice.name`（机器标识，用来精确选中）。
     *   空串 = 跟随系统默认（按界面语言挑一个本地音色）。
     * - [ttsVoiceLabel]：选中时给用户看的名字（地区 + 是否联网）。**单独存一份**，
     *   因为音色名是 `zh-cn-x-ccc-local` 这种机器 ID，界面不可能现算，而每次进设置页
     *   都去初始化一次 TTS 引擎只为显示一个名字也不划算。
     */
    val ttsVoiceName: String = "",
    val ttsVoiceLabel: String = "",

    /**
     * 当前端点 `/models` 拉回来的模型 id（[ModelIdList] 编解码）。
     *
     * 存下来而不是只放内存：拉取动作发生在「测试连接」或对话面板的刷新里，
     * 而用户随后进设置页选模型可能已经过了一次重启 —— 列表一丢，选择器就只剩
     * 内置能力表（自定义端点干脆是空的），于是"能拉到模型，却选不到模型"。
     * 端点（服务商 / Base URL）一换就清空，见 [SettingsStore.setProvider]。
     */
    val modelIds: List<String> = emptyList(),

) {
    val hasKey: Boolean get() = apiKey.isNotBlank()

    /**
     * 这一套配置**实际走的是不是扩展通道**（联网搜索等能力的唯一前提）。
     *
     * 判据与请求时用的是同一个 [`ApiStyle.resolve`]：配置档位 + 端点共同决定。
     * 用在设置页与输入面板上"要不要显示联网搜索" —— 只有扩展通道有
     * `deepseek_web_search` 这个工具，OpenAI 兼容通道没有对应入口，
     * 那就别把一个打开了也没用的开关摆在用户面前。
     */
    val resolvedApiStyle: ApiStyle
        get() = ApiStyle.resolve(
            kind = runCatching { ProviderKind.valueOf(providerId) }.getOrDefault(ProviderKind.DEEPSEEK),
            configuredId = apiStyleId,
            baseUrl = baseUrl,
        )

    /** 当前通道是否支持联网搜索。 */
    val supportsWebSearch: Boolean get() = resolvedApiStyle == ApiStyle.ANTHROPIC
}

/**
 * 配置存储（DataStore Preferences）。
 *
 * **API Key 不落明文**：加解密走 [SecretBox]（Android Keystore 里的
 * 不可导出 AES-256 密钥 + AES-GCM），DataStore 里只存密文。
 * 密钥随应用卸载 / 清除数据一起消失，届时解不开只会当"未设置"处理，
 * 不会崩，也不会把密文当 Key 发出去。
 */
class SettingsStore(
    private val context: Context,
    private val preferences: DataStore<Preferences> = context.settingsDataStore,
) {

    val flow: Flow<StoredSettings> = preferences.data.map { prefs ->
        StoredSettings(
            providerId = prefs[Keys.PROVIDER] ?: StoredSettings().providerId,
            baseUrl = prefs[Keys.BASE_URL] ?: StoredSettings().baseUrl,
            modelId = prefs[Keys.MODEL] ?: StoredSettings().modelId,
            apiKey = readApiKey(prefs),
            apiProfiles = profileRows(prefs),
            activeApiProfileId = prefs[Keys.ACTIVE_PROFILE] ?: "legacy",
            autoReadAloud = prefs[Keys.AUTO_READ_ALOUD] ?: false,
            showImageSkipNotice = prefs[Keys.IMAGE_SKIP_NOTICE] ?: true,
            reasoningLevelId = prefs[Keys.REASONING] ?: StoredSettings().reasoningLevelId,
            temperature = prefs[Keys.TEMPERATURE] ?: StoredSettings().temperature,
            topP = prefs[Keys.TOP_P] ?: StoredSettings().topP,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: StoredSettings().maxTokens,
            themeModeId = prefs[Keys.THEME_MODE] ?: StoredSettings().themeModeId,
            accentColorId = prefs[Keys.ACCENT_COLOR] ?: StoredSettings().accentColorId,
            fontScale = prefs[Keys.FONT_SCALE] ?: StoredSettings().fontScale,
            followSystemFont = prefs[Keys.FOLLOW_SYSTEM_FONT]
                ?: StoredSettings().followSystemFont,
            autoCollapseThinking = prefs[Keys.AUTO_COLLAPSE_THINKING]
                ?: StoredSettings().autoCollapseThinking,
            defaultModeId = prefs[Keys.DEFAULT_MODE] ?: StoredSettings().defaultModeId,
            languageId = prefs[LANGUAGE_KEY] ?: StoredSettings().languageId,
            apiStyleId = prefs[Keys.API_STYLE] ?: StoredSettings().apiStyleId,
            webSearch = prefs[Keys.WEB_SEARCH] ?: StoredSettings().webSearch,
            locationRegion = prefs[Keys.LOCATION] ?: StoredSettings().locationRegion,
            ttsVoiceName = prefs[Keys.TTS_VOICE] ?: StoredSettings().ttsVoiceName,
            ttsVoiceLabel = prefs[Keys.TTS_VOICE_LABEL] ?: StoredSettings().ttsVoiceLabel,
            modelIds = ModelIdList.decode(prefs[Keys.MODEL_IDS]),

        )
    }

    suspend fun setProvider(providerId: String, baseUrl: String, modelId: String) {
        editApi { prefs ->
            // 换了端点，旧端点拉到的模型列表就不再成立
            val endpointChanged = prefs[Keys.PROVIDER] != providerId ||
                prefs[Keys.BASE_URL] != baseUrl.trim()
            if (endpointChanged) prefs.remove(Keys.MODEL_IDS)
            prefs[Keys.PROVIDER] = providerId
            prefs[Keys.BASE_URL] = baseUrl.trim()
            prefs[Keys.MODEL] = modelId
        }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        val trimmed = baseUrl.trim()
        editApi { prefs ->
            // 只改大小写 / 前后空格不算换端点；真的改了才丢掉旧列表
            if (prefs[Keys.BASE_URL].orEmpty().trim() != trimmed) prefs.remove(Keys.MODEL_IDS)
            prefs[Keys.BASE_URL] = trimmed
        }
    }

    suspend fun setModel(modelId: String, expectedProfileId: String? = null) {
        editApi(expectedProfileId) { it[Keys.MODEL] = modelId }
    }

    /** 记下这个端点 `GET /models` 返回的 id（测试连接 / 对话面板刷新时调用）。 */
    suspend fun setModelIds(ids: List<String>, expectedProfileId: String? = null, expectedBaseUrl: String? = null) {
        editApi(expectedProfileId) { prefs ->
            if (expectedBaseUrl != null && prefs[Keys.BASE_URL] != expectedBaseUrl) return@editApi
            val encoded = ModelIdList.encode(ids)
            if (encoded.isEmpty()) {
                prefs.remove(Keys.MODEL_IDS)
            } else {
                prefs[Keys.MODEL_IDS] = encoded
            }
        }
    }

    suspend fun setApiKey(apiKey: String) {
        // 清洗在**入口**做一次：粘贴带进来的换行/空格是 OkHttp 报
        // `Unexpected char 0x0a in Authorization value` 的头号原因
        // （见 ApiKey 的说明）。非 ASCII 不在这里静默丢弃，交给界面拦。
        val trimmed = ApiKey.normalize(apiKey)
        editApi { prefs ->
            // Different credentials at the same URL can expose a different model catalog.
            prefs.remove(Keys.MODEL_IDS)
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.API_KEY_ENC)
            } else {
                prefs[Keys.API_KEY_ENC] = SecretBox.encrypt(trimmed)
                    ?: throw AppTextException(com.simplechat.app.R.string.api_profile_save_failed)
            }
            // 明文槽位彻底弃用，写入时顺手清掉历史遗留
            prefs.remove(Keys.API_KEY)
        }
    }

    /**
     * 把 v1.7 之前留下的**明文** Key 就地加密（一次性）。
     *
     * 启动时调用一次即可。老版本直接把 Key 写在 `api_key` 里，
     * 升级后那份明文必须尽快消失，否则"加密存储"只是新数据的待遇。
     */
    suspend fun migrateLegacyApiKey() {
        preferences.edit { prefs ->
            val legacy = prefs[Keys.API_KEY]
            if (!legacy.isNullOrBlank() && prefs[Keys.API_KEY_ENC] == null) {
                prefs[Keys.API_KEY_ENC] = SecretBox.encrypt(legacy)
                    ?: throw AppTextException(com.simplechat.app.R.string.api_profile_save_failed)
            }
            prefs.remove(Keys.API_KEY)
        }
    }

    suspend fun setReasoningLevel(levelId: String, expectedProfileId: String? = null) {
        editApi(expectedProfileId) { it[Keys.REASONING] = levelId }
    }

    suspend fun setTemperature(value: Float, expectedProfileId: String? = null) {
        editApi(expectedProfileId) { it[Keys.TEMPERATURE] = value }
    }

    suspend fun setTopP(value: Float, expectedProfileId: String? = null) {
        editApi(expectedProfileId) { it[Keys.TOP_P] = value }
    }

    suspend fun setMaxTokens(value: Int, expectedProfileId: String? = null) {
        editApi(expectedProfileId) { it[Keys.MAX_TOKENS] = value }
    }

    suspend fun setThemeMode(modeId: String) {
        preferences.edit { it[Keys.THEME_MODE] = modeId }
    }

    suspend fun setAccentColor(colorId: String) {
        preferences.edit { it[Keys.ACCENT_COLOR] = colorId }
    }

    suspend fun setFontScale(scale: Float) {
        preferences.edit { it[Keys.FONT_SCALE] = scale }
    }

    suspend fun setFollowSystemFont(follow: Boolean) {
        preferences.edit { it[Keys.FOLLOW_SYSTEM_FONT] = follow }
    }

    suspend fun setAutoCollapseThinking(enabled: Boolean) {
        preferences.edit { it[Keys.AUTO_COLLAPSE_THINKING] = enabled }
    }

    suspend fun setDefaultMode(modeId: String) {
        preferences.edit { it[Keys.DEFAULT_MODE] = modeId }
    }

    suspend fun setLanguage(languageId: String) {
        preferences.edit { it[LANGUAGE_KEY] = languageId }
    }

    suspend fun setApiStyle(styleId: String) {
        editApi { it[Keys.API_STYLE] = styleId }
    }

    suspend fun setWebSearch(enabled: Boolean) {
        editApi { it[Keys.WEB_SEARCH] = enabled }
    }

    suspend fun setLocationRegion(region: String) {
        // 存**洗过**的值：逗号会把时间地点提醒的五个字段挤成六段（见 normalizeRegion）
        preferences.edit { it[Keys.LOCATION] = normalizeRegion(region) }
    }

    /** 朗读音色：名字用来精确选中，标签用来在设置页显示（见 [StoredSettings.ttsVoiceName]）。 */
    suspend fun setTtsVoice(name: String, label: String) {
        preferences.edit { prefs ->
            if (name.isBlank()) {
                prefs.remove(Keys.TTS_VOICE)
                prefs.remove(Keys.TTS_VOICE_LABEL)
            } else {
                prefs[Keys.TTS_VOICE] = name
                prefs[Keys.TTS_VOICE_LABEL] = label
            }
        }
    }



    /** Seed the registry in one edit, preserving the existing active configuration and encrypted key. */
    suspend fun migrateApiProfiles() { preferences.edit { ensureProfiles(it) } }

    suspend fun setShowImageSkipNotice(enabled: Boolean) {
        preferences.edit { it[Keys.IMAGE_SKIP_NOTICE] = enabled }
    }

    suspend fun setAutoReadAloud(enabled: Boolean) {
        preferences.edit { it[Keys.AUTO_READ_ALOUD] = enabled }
    }

    private fun profileRows(prefs: Preferences): List<ApiProfile> =
        prefs[Keys.PROFILES]?.let(::decodeApiProfiles)
            ?: listOf(snapshotProfile(prefs, "legacy", com.simplechat.app.net.ProviderKind.entries
                .firstOrNull { it.name == prefs[Keys.PROVIDER] }?.displayName
                ?: com.simplechat.app.net.ProviderKind.DEEPSEEK.displayName))

    private fun snapshotProfile(prefs: Preferences, id: String, name: String, lastUsedAt: Long = 0): ApiProfile = ApiProfile(
        id = id, name = name,
        providerId = prefs[Keys.PROVIDER] ?: "DEEPSEEK",
        baseUrl = prefs[Keys.BASE_URL] ?: "https://api.deepseek.com",
        modelId = prefs[Keys.MODEL] ?: "deepseek-flash",
        apiKeyEncrypted = prefs[Keys.API_KEY_ENC].orEmpty(),
        apiStyleId = prefs[Keys.API_STYLE] ?: "AUTO",
        modelIds = ModelIdList.decode(prefs[Keys.MODEL_IDS]),
        reasoningLevelId = prefs[Keys.REASONING] ?: "OFF",
        temperature = prefs[Keys.TEMPERATURE] ?: 1f, topP = prefs[Keys.TOP_P] ?: 1f,
        maxTokens = prefs[Keys.MAX_TOKENS] ?: 0, webSearch = prefs[Keys.WEB_SEARCH] ?: false,
        lastUsedAt = lastUsedAt,
    )

    private fun ensureProfiles(prefs: MutablePreferences): List<ApiProfile> {
        prefs[Keys.PROFILES]?.let { return decodeApiProfiles(it) }
        val legacy = prefs[Keys.API_KEY]
        if (!legacy.isNullOrBlank() && prefs[Keys.API_KEY_ENC] == null) {
            prefs[Keys.API_KEY_ENC] = SecretBox.encrypt(legacy)
                ?: throw AppTextException(com.simplechat.app.R.string.api_profile_save_failed)
        }
        prefs.remove(Keys.API_KEY)
        val profiles = profileRows(prefs)
        prefs[Keys.PROFILES] = apiProfileJson.encodeToString(profiles)
        prefs[Keys.ACTIVE_PROFILE] = profiles.first().id
        return profiles
    }

    private suspend fun editApi(expectedProfileId: String? = null, block: (MutablePreferences) -> Unit) {
        preferences.edit { prefs ->
            val profiles = ensureProfiles(prefs)
            val activeId = checkNotNull(prefs[Keys.ACTIVE_PROFILE])
            if (expectedProfileId != null && expectedProfileId != activeId) return@edit
            block(prefs)
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(profiles.map {
                if (it.id == activeId) snapshotProfile(prefs, it.id, it.name, it.lastUsedAt) else it
            })
        }
    }

    private fun activate(prefs: MutablePreferences, profile: ApiProfile) {
        prefs[Keys.ACTIVE_PROFILE] = profile.id
        prefs[Keys.PROVIDER] = profile.providerId
        prefs[Keys.BASE_URL] = profile.baseUrl
        prefs[Keys.MODEL] = profile.modelId
        if (profile.apiKeyEncrypted.isEmpty()) prefs.remove(Keys.API_KEY_ENC)
        else prefs[Keys.API_KEY_ENC] = profile.apiKeyEncrypted
        prefs.remove(Keys.API_KEY)
        prefs[Keys.API_STYLE] = profile.apiStyleId
        prefs[Keys.MODEL_IDS] = ModelIdList.encode(profile.modelIds)
        prefs[Keys.REASONING] = profile.reasoningLevelId
        prefs[Keys.TEMPERATURE] = profile.temperature
        prefs[Keys.TOP_P] = profile.topP
        prefs[Keys.MAX_TOKENS] = profile.maxTokens
        prefs[Keys.WEB_SEARCH] = profile.webSearch
    }

    suspend fun selectApiProfile(id: String) {
        preferences.edit { prefs ->
            val profiles = ensureProfiles(prefs)
            val selected = profiles.firstOrNull { it.id == id } ?: return@edit
            val current = selected.copy(lastUsedAt = System.currentTimeMillis())
            activate(prefs, current)
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(profiles.map { if (it.id == id) current else it })
        }
    }

    /** Provider picker recalls the last used profile for that provider, including its own key. */
    suspend fun selectProvider(provider: ProviderKind) {
        preferences.edit { prefs ->
            val profiles = ensureProfiles(prefs)
            if (prefs[Keys.PROVIDER] == provider.name) return@edit
            val selected = profiles.filter { it.providerId == provider.name }.maxByOrNull { it.lastUsedAt }
                ?: ApiProfile(java.util.UUID.randomUUID().toString(), provider.displayName,
                    provider.name, provider.defaultBaseUrl, provider.defaultModel)
            val current = selected.copy(lastUsedAt = System.currentTimeMillis())
            activate(prefs, current)
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(profiles.filterNot { it.id == current.id } + current)
        }
    }

    suspend fun createApiProfile(name: String, copyCurrent: Boolean = false) {
        val title = name.trim().take(80)
        require(title.isNotEmpty())
        preferences.edit { prefs ->
            val profiles = ensureProfiles(prefs)
            val id = java.util.UUID.randomUUID().toString()
            val profile = if (copyCurrent) snapshotProfile(prefs, id, title, System.currentTimeMillis())
                else ApiProfile(id, title, "CUSTOM", "", "", lastUsedAt = System.currentTimeMillis())
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(profiles + profile)
            activate(prefs, profile)
        }
    }

    suspend fun renameApiProfile(id: String, name: String) {
        val title = name.trim().take(80)
        require(title.isNotEmpty())
        preferences.edit { prefs ->
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(ensureProfiles(prefs).map {
                if (it.id == id) it.copy(name = title) else it
            })
        }
    }

    suspend fun deleteApiProfile(id: String) {
        preferences.edit { prefs ->
            val profiles = ensureProfiles(prefs)
            require(profiles.size > 1)
            val remaining = profiles.filterNot { it.id == id }
            if (prefs[Keys.ACTIVE_PROFILE] == id) activate(prefs, remaining.maxBy { it.lastUsedAt })
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(remaining)
        }
    }

    /** 清空全部配置（「删除所有数据」时一并调用）。 */
    suspend fun clear() {
        preferences.edit { it.clear() }
    }

    /** 读 API Key：优先密文，其次兼容老版本的明文。 */
    private fun readApiKey(prefs: Preferences): String {
        prefs[Keys.API_KEY_ENC]?.takeIf { it.isNotBlank() }?.let { return SecretBox.decrypt(it) }
        return prefs[Keys.API_KEY].orEmpty()
    }

    private object Keys {
        val PROFILES = stringPreferencesKey("api_profiles_v1")
        val ACTIVE_PROFILE = stringPreferencesKey("active_api_profile")
        val IMAGE_SKIP_NOTICE = booleanPreferencesKey("show_image_skip_notice")
        val AUTO_READ_ALOUD = booleanPreferencesKey("auto_read_aloud")
        val PROVIDER = stringPreferencesKey("provider_id")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model_id")

        /** API Key 的**密文**（base64）。 */
        val API_KEY_ENC = stringPreferencesKey("api_key_enc")

        /** 历史遗留的明文槽位，只读一次用于迁移，之后不再写入。 */
        val API_KEY = stringPreferencesKey("api_key")
        val REASONING = stringPreferencesKey("reasoning_level")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_P = floatPreferencesKey("top_p")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val FOLLOW_SYSTEM_FONT = booleanPreferencesKey("follow_system_font")
        val AUTO_COLLAPSE_THINKING = booleanPreferencesKey("auto_collapse_thinking")
        val DEFAULT_MODE = stringPreferencesKey("default_mode")
        val API_STYLE = stringPreferencesKey("api_style")
        val WEB_SEARCH = booleanPreferencesKey("web_search")
        val LOCATION = stringPreferencesKey("location_region")

        /** 朗读音色：机器标识；空 = 系统默认。 */
        val TTS_VOICE = stringPreferencesKey("tts_voice_name")

        /** 朗读音色的人类可读名字（仅用于显示）。 */
        val TTS_VOICE_LABEL = stringPreferencesKey("tts_voice_label")

        /** 当前端点 `/models` 拉回来的 id，换行分隔（[ModelIdList]）。 */
        val MODEL_IDS = stringPreferencesKey("model_ids")

    }
}
