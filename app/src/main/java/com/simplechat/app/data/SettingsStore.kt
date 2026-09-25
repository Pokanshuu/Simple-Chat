package com.simplechat.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "simplechat_settings",
)

/** 语言档位（[AppLanguage] 的枚举名）。抽到文件级，供 [readStoredLanguageSync] 共用。 */
private val LANGUAGE_KEY = stringPreferencesKey("language_id")

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

) {
    val hasKey: Boolean get() = apiKey.isNotBlank()
}

/**
 * 配置存储（DataStore Preferences）。
 *
 * **API Key 不落明文**：加解密走 [SecretBox]（Android Keystore 里的
 * 不可导出 AES-256 密钥 + AES-GCM），DataStore 里只存密文。
 * 密钥随应用卸载 / 清除数据一起消失，届时解不开只会当"未设置"处理，
 * 不会崩，也不会把密文当 Key 发出去。
 */
class SettingsStore(private val context: Context) {

    val flow: Flow<StoredSettings> = context.settingsDataStore.data.map { prefs ->
        StoredSettings(
            providerId = prefs[Keys.PROVIDER] ?: StoredSettings().providerId,
            baseUrl = prefs[Keys.BASE_URL] ?: StoredSettings().baseUrl,
            modelId = prefs[Keys.MODEL] ?: StoredSettings().modelId,
            apiKey = readApiKey(prefs),
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

        )
    }

    suspend fun setProvider(providerId: String, baseUrl: String, modelId: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PROVIDER] = providerId
            prefs[Keys.BASE_URL] = baseUrl
            prefs[Keys.MODEL] = modelId
        }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        context.settingsDataStore.edit { it[Keys.BASE_URL] = baseUrl.trim() }
    }

    suspend fun setModel(modelId: String) {
        context.settingsDataStore.edit { it[Keys.MODEL] = modelId }
    }

    suspend fun setApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        context.settingsDataStore.edit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(Keys.API_KEY_ENC)
            } else {
                SecretBox.encrypt(trimmed)?.let { prefs[Keys.API_KEY_ENC] = it }
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
        context.settingsDataStore.edit { prefs ->
            val legacy = prefs[Keys.API_KEY]
            if (!legacy.isNullOrBlank() && prefs[Keys.API_KEY_ENC] == null) {
                SecretBox.encrypt(legacy)?.let { prefs[Keys.API_KEY_ENC] = it }
            }
            prefs.remove(Keys.API_KEY)
        }
    }

    suspend fun setReasoningLevel(levelId: String) {
        context.settingsDataStore.edit { it[Keys.REASONING] = levelId }
    }

    suspend fun setTemperature(value: Float) {
        context.settingsDataStore.edit { it[Keys.TEMPERATURE] = value }
    }

    suspend fun setTopP(value: Float) {
        context.settingsDataStore.edit { it[Keys.TOP_P] = value }
    }

    suspend fun setMaxTokens(value: Int) {
        context.settingsDataStore.edit { it[Keys.MAX_TOKENS] = value }
    }

    suspend fun setThemeMode(modeId: String) {
        context.settingsDataStore.edit { it[Keys.THEME_MODE] = modeId }
    }

    suspend fun setAccentColor(colorId: String) {
        context.settingsDataStore.edit { it[Keys.ACCENT_COLOR] = colorId }
    }

    suspend fun setFontScale(scale: Float) {
        context.settingsDataStore.edit { it[Keys.FONT_SCALE] = scale }
    }

    suspend fun setFollowSystemFont(follow: Boolean) {
        context.settingsDataStore.edit { it[Keys.FOLLOW_SYSTEM_FONT] = follow }
    }

    suspend fun setAutoCollapseThinking(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_COLLAPSE_THINKING] = enabled }
    }

    suspend fun setDefaultMode(modeId: String) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_MODE] = modeId }
    }

    suspend fun setLanguage(languageId: String) {
        context.settingsDataStore.edit { it[LANGUAGE_KEY] = languageId }
    }



    /** 清空全部配置（「删除所有数据」时一并调用）。 */
    suspend fun clear() {
        context.settingsDataStore.edit { it.clear() }
    }

    /** 读 API Key：优先密文，其次兼容老版本的明文。 */
    private fun readApiKey(prefs: Preferences): String {
        prefs[Keys.API_KEY_ENC]?.takeIf { it.isNotBlank() }?.let { return SecretBox.decrypt(it) }
        return prefs[Keys.API_KEY].orEmpty()
    }

    private object Keys {
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

    }
}
