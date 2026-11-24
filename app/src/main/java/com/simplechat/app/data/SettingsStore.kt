package com.simplechat.app.data

import android.content.Context
import com.simplechat.app.net.DEEPSEEK_FLASH
import com.simplechat.app.net.effectiveModelId
import com.simplechat.app.net.isOfficialDeepSeekEndpoint
import com.simplechat.app.net.ApiStyle
import com.simplechat.app.net.ModelCapabilityOverride
import com.simplechat.app.net.RemoteModelMetadata
import com.simplechat.app.net.ProviderKind

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

internal const val MaxPanelAccentOpacity = 1f
internal const val MaxWallpaperBlur = 160f

@kotlinx.serialization.Serializable
internal data class WallpaperAppearance(
    val opacity: Float,
    val blur: Float,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val dim: Float,
    val flipH: Boolean,
)

enum class InterfaceControl(val id: String) {
    INPUT("input"), INPUT_PANEL("input_panel"), SIDEBAR("sidebar"), MENU("menu"), SCROLL_BUTTON("scroll_button"),
    USER_BUBBLE("user_bubble"), MESSAGE_INDEX("message_index"),
}

/** Store exclusions so older profiles and newly introduced controls inherit the global effects. */
data class ControlEffects(
    val acrylicExcluded: Set<String> = emptySet(),
    val accentExcluded: Set<String> = emptySet(),
    val opacityExcluded: Set<String> = emptySet(),
) {
    fun usesOpacity(control: InterfaceControl) = control.id !in opacityExcluded

    fun withOpacity(control: InterfaceControl, apply: Boolean) = copy(
        opacityExcluded = if (apply) opacityExcluded - control.id else opacityExcluded + control.id,
    )

    fun usesAcrylic(control: InterfaceControl) = control.id !in acrylicExcluded
    fun usesAccent(control: InterfaceControl) = control.id !in accentExcluded

    fun withAcrylic(control: InterfaceControl, apply: Boolean) = copy(
        acrylicExcluded = if (apply) acrylicExcluded - control.id else acrylicExcluded + control.id,
    )

    fun withAccent(control: InterfaceControl, apply: Boolean) = copy(
        accentExcluded = if (apply) accentExcluded - control.id else accentExcluded + control.id,
    )
}

@kotlinx.serialization.Serializable
internal data class ConversationWallpaper(val fileName: String, val appearance: WallpaperAppearance) {
    fun encode(): String = kotlinx.serialization.json.Json.encodeToString(this)

    companion object {
        fun decode(raw: String?): ConversationWallpaper? = raw?.let {
            runCatching { kotlinx.serialization.json.Json.decodeFromString<ConversationWallpaper>(it) }.getOrNull()
        }
    }
}

internal data class PanelAppearance(
    val floatingGlass: Float,
    val acrylicEnabled: Boolean,
    val opacity: Float,
    val accentEnabled: Boolean,
    val accentOpacity: Float,
    val controlEffects: ControlEffects,
)

/** 持久化的全局配置。 */
data class StoredSettings(
    val providerId: String = "DEEPSEEK",
    val baseUrl: String = "https://api.deepseek.com",
    val modelId: String = "deepseek-flash",
    val apiKey: String = "",
    val apiProfiles: List<ApiProfile> = emptyList(),
    val activeApiProfileId: String = "legacy",
    val searchApi: ServiceApiConfig = ServiceApiConfig(),
    val filesApi: ServiceApiConfig = ServiceApiConfig(),
    val imageGenApi: ImageGenConfig = ImageGenConfig(),
    /** 第三方声音模型（朗读）配置。 */
    val voiceApi: VoiceApiConfig = VoiceApiConfig(),
    val autoReadAloud: Boolean = false,
    val showImageSkipNotice: Boolean = true,
    val showRetrySkipNotice: Boolean = true,
    /** Include messages from inactive conversation branches in global search. */
    val searchInactiveBranches: Boolean = false,
    val searchReasoning: Boolean = false,
    val readAloudEnabled: Boolean = true,
    val reasoningLevelId: String = "OFF",
    val temperature: Float = 1.0f,
    val topP: Float = 1.0f,
    val maxTokens: Int = 0,
    /** 外观：SYSTEM / LIGHT / DARK。 */
    val themeModeId: String = "SYSTEM",
    val useSimpleCatIcon: Boolean = true,
    /** 主题色：AccentColor.name，见 ui/theme/Theme.kt。 */
    val accentColorId: String = "BLUE",
    /** 字号倍率。 */
    val fontScale: Float = 1.0f,
    /** 字号是否跟随系统辅助功能设置。 */
    val followSystemFont: Boolean = true,
    /** 思考内容是否自动折叠（关闭则默认展开，需手动收起）。 */
    val autoCollapseThinking: Boolean = true,
    /** 长按输入框开始语音输入（关闭后长按不再触发）。 */
    val holdToTalk: Boolean = true,
    /**
     * 跨对话记忆：发消息时从**其它会话**里检索相关片段，附在当前用户消息前。
     *
     * 只在这条消息明显"想回忆"（提到「之前/上次/记得」等）或实词足够多时才注入，
     * 免得每句「你好」都拖一段历史。
     */
    val crossChatMemory: Boolean = true,
    /** 一次最多参考几个历史会话（1..10）。 */
    val crossChatMemoryLimit: Int = 4,
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
    /** 用户导入的字体（元数据；字节在 filesDir/fonts）。 */
    val fonts: List<ImportedFont> = emptyList(),
    /** 软件 UI 字体层选中的字体 id；null = 系统字体。 */
    val uiFontId: String? = null,
    /** 用户发言层选中的字体 id；null = 系统字体。 */
    val userFontId: String? = null,
    /** 模型回复层选中的字体 id；null = 系统字体。 */
    val assistantFontId: String? = null,
    /** Per-category type size and color overrides; absent in legacy settings means all inherit defaults. */
    val typography: TypographySettings = TypographySettings(),
    /** 聊天背景壁纸：`filesDir/wallpaper/` 下的文件名；null = 不使用。 */
    val wallpaperPath: String? = null,
    /** 壁纸叠在背景色上的不透明度（0..1）。太实会盖住气泡对比度，默认偏淡。 */
    val wallpaperOpacity: Float = 0.35f,
    /**
     * 悬浮控件（输入栏 / 输入面板）的玻璃质感 0..1：一个值同时驱动混色与实时模糊。
     * 0 = 实色，与旧版完全一致，且不创建任何离屏图层（docs/32 §32.3.3）。
     */
    val floatingGlass: Float = 0f,
    val acrylicEnabled: Boolean = false,
    /** 壁纸模糊半径（dp）；0 = 不模糊。 */
    val wallpaperBlur: Float = 0f,
    /** 亮度倍率（1 = 原样）。 */
    val wallpaperBrightness: Float = 1f,
    /** 对比度倍率（1 = 原样）。 */
    val wallpaperContrast: Float = 1f,
    /** 饱和度倍率（1 = 原样，0 = 黑白）。 */
    val wallpaperSaturation: Float = 1f,
    /** 画面横向/纵向位置（0..1，0.5 = 居中）。 */
    val wallpaperOffsetX: Float = 0.5f,
    val wallpaperOffsetY: Float = 0.5f,
    /** 缩放倍率（1 = 适配后原尺寸）。 */
    val wallpaperScale: Float = 1f,
    /** 额外压暗（0..1，叠一层黑色）。 */
    val wallpaperDim: Float = 0f,
    /** 适配方式：COVER / FILL / CENTER / STRETCH。 */
    val wallpaperFit: String = "COVER",
    /** 水平翻转。 */
    val wallpaperFlipH: Boolean = false,
    /** 自定义截取位置：开启后可在预览上拖动选择显示区域。 */
    val wallpaperCustomCrop: Boolean = false,
    /** 壁纸设置页的毛玻璃面板圆角（dp）。 */
    val wallpaperPanelRadius: Float = 18f,
    /** 壁纸设置页的毛玻璃面板不透明度（0..1，越大越实）。 */
    val wallpaperPanelAlpha: Float = 0.72f,
    val controlEffects: ControlEffects = ControlEffects(),
    val panelAccentEnabled: Boolean = false,
    val panelAccentOpacity: Float = 0.12f,
    /** 壁纸设置页是否用透明玻璃面板；关掉回到普通卡片。 */
    /**
     * 智能搜索（联网搜索）开关。
     *
     * 默认**关**：搜索会让这一轮变慢、变贵，而长文创作绝大多数轮次用不上。
     * 与官方 Chat Demo 的默认值一致。
     */
    val webSearch: Boolean = false,
    val webSearchWriteId: String? = null,
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
    val modelContextWindows: Map<String, Int> = emptyMap(),
    val modelMetadata: Map<String, RemoteModelMetadata> = emptyMap(),
    val modelOverrides: Map<String, ModelCapabilityOverride> = emptyMap(),
    val onboardingComplete: Boolean = false,
    val loadingWords: List<String> = emptyList(),
    val loadingWordsUseThemeColor: Boolean = true,

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
    val officialFlash: Boolean get() = isOfficialDeepSeekEndpoint(baseUrl) && effectiveModelId(baseUrl, modelId) == DEEPSEEK_FLASH
    val hasConfiguredSearchApi: Boolean get() = searchApi.enabled &&
        (if (searchApi.reuseChatKey) apiKey.isNotBlank() else searchApi.apiKeyEncrypted.isNotBlank()) &&
        runCatching { searchApi.endpoint(baseUrl, searchApi.searchPath) }.isSuccess
    val supportsWebSearch: Boolean get() = hasConfiguredSearchApi || officialFlash ||
        com.simplechat.app.net.usesAutomaticOpenAiSearch(modelId, resolvedApiStyle) ||
        com.simplechat.app.net.supportsOpenAiSearchTool(baseUrl, modelId, resolvedApiStyle)
    val supportsFileReferences: Boolean get() = !isOfficialDeepSeekEndpoint(baseUrl) || officialFlash
    val useRemoteFiles: Boolean get() = filesApi.enabled && supportsFileReferences
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
            modelId = effectiveModelId(prefs[Keys.BASE_URL] ?: ProviderKind.DEEPSEEK.defaultBaseUrl, prefs[Keys.MODEL] ?: DEEPSEEK_FLASH),
            apiKey = readApiKey(prefs),
            apiProfiles = profileRows(prefs).map { it.copy(modelId = effectiveModelId(it.baseUrl, it.modelId)) },
            activeApiProfileId = prefs[Keys.ACTIVE_PROFILE] ?: "legacy",
            searchApi = readService(prefs, Keys.SEARCH_API),
            filesApi = readService(prefs, Keys.FILES_API),
            imageGenApi = readImageGen(prefs),
            voiceApi = prefs[Keys.VOICE_API]?.let { runCatching { apiProfileJson.decodeFromString<VoiceApiConfig>(it) }.getOrNull() } ?: VoiceApiConfig(),
            autoReadAloud = prefs[Keys.AUTO_READ_ALOUD] ?: false,
            showRetrySkipNotice = prefs[Keys.RETRY_SKIP_NOTICE] ?: true,
            showImageSkipNotice = prefs[Keys.IMAGE_SKIP_NOTICE] ?: true,
            searchInactiveBranches = prefs[Keys.SEARCH_INACTIVE_BRANCHES]
                ?: StoredSettings().searchInactiveBranches,
            reasoningLevelId = prefs[Keys.REASONING] ?: StoredSettings().reasoningLevelId,
            temperature = prefs[Keys.TEMPERATURE] ?: StoredSettings().temperature,
            topP = prefs[Keys.TOP_P] ?: StoredSettings().topP,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: StoredSettings().maxTokens,
            themeModeId = prefs[Keys.THEME_MODE] ?: StoredSettings().themeModeId,
            useSimpleCatIcon = prefs[Keys.USE_SIMPLE_CAT_ICON] ?: StoredSettings().useSimpleCatIcon,
            accentColorId = prefs[Keys.ACCENT_COLOR] ?: StoredSettings().accentColorId,
            fontScale = prefs[Keys.FONT_SCALE] ?: StoredSettings().fontScale,
            followSystemFont = prefs[Keys.FOLLOW_SYSTEM_FONT]
                ?: StoredSettings().followSystemFont,
            autoCollapseThinking = prefs[Keys.AUTO_COLLAPSE_THINKING]
                ?: StoredSettings().autoCollapseThinking,
            holdToTalk = prefs[Keys.HOLD_TO_TALK] ?: StoredSettings().holdToTalk,
            crossChatMemory = prefs[Keys.CROSS_CHAT_MEMORY] ?: StoredSettings().crossChatMemory,
            crossChatMemoryLimit = prefs[Keys.CROSS_CHAT_MEMORY_LIMIT]
                ?: StoredSettings().crossChatMemoryLimit,
            defaultModeId = prefs[Keys.DEFAULT_MODE] ?: StoredSettings().defaultModeId,
            languageId = prefs[LANGUAGE_KEY] ?: StoredSettings().languageId,
            apiStyleId = prefs[Keys.API_STYLE] ?: StoredSettings().apiStyleId,
            fonts = readImportedFonts(prefs),
            uiFontId = prefs[Keys.UI_FONT],
            userFontId = prefs[Keys.USER_FONT],
            assistantFontId = prefs[Keys.ASSISTANT_FONT],
            typography = prefs[Keys.TYPOGRAPHY]?.let { raw ->
                runCatching { apiProfileJson.decodeFromString<TypographySettings>(raw) }.getOrNull()
            } ?: TypographySettings(),
            wallpaperPath = prefs[Keys.WALLPAPER],
            wallpaperOpacity = prefs[Keys.WALLPAPER_OPACITY] ?: StoredSettings().wallpaperOpacity,
            acrylicEnabled = prefs[Keys.ACRYLIC_ENABLED] ?: false,
            floatingGlass = prefs[Keys.FLOATING_GLASS] ?: StoredSettings().floatingGlass,
            wallpaperBlur = (prefs[Keys.WALLPAPER_BLUR] ?: StoredSettings().wallpaperBlur).coerceIn(0f, MaxWallpaperBlur),
            wallpaperBrightness = prefs[Keys.WALLPAPER_BRIGHTNESS] ?: StoredSettings().wallpaperBrightness,
            wallpaperContrast = prefs[Keys.WALLPAPER_CONTRAST] ?: StoredSettings().wallpaperContrast,
            wallpaperSaturation = prefs[Keys.WALLPAPER_SATURATION] ?: StoredSettings().wallpaperSaturation,
            wallpaperOffsetX = prefs[Keys.WALLPAPER_OFFSET_X] ?: StoredSettings().wallpaperOffsetX,
            wallpaperOffsetY = prefs[Keys.WALLPAPER_OFFSET_Y] ?: StoredSettings().wallpaperOffsetY,
            wallpaperScale = prefs[Keys.WALLPAPER_SCALE] ?: StoredSettings().wallpaperScale,
            wallpaperDim = prefs[Keys.WALLPAPER_DIM] ?: StoredSettings().wallpaperDim,
            wallpaperFit = prefs[Keys.WALLPAPER_FIT] ?: StoredSettings().wallpaperFit,
            wallpaperFlipH = prefs[Keys.WALLPAPER_FLIP_H] ?: StoredSettings().wallpaperFlipH,
            wallpaperCustomCrop = prefs[Keys.WALLPAPER_CUSTOM_CROP] ?: StoredSettings().wallpaperCustomCrop,
            wallpaperPanelRadius = prefs[Keys.WALLPAPER_PANEL_RADIUS] ?: StoredSettings().wallpaperPanelRadius,
            // 旧开关关闭时实际使用实色。读取旧配置时折算成不透明度 100%。
            wallpaperPanelAlpha = if (prefs[Keys.WALLPAPER_GLASS_PANEL] == false) 1f else
                (prefs[Keys.WALLPAPER_PANEL_ALPHA] ?: StoredSettings().wallpaperPanelAlpha).coerceIn(0f, 1f),
            controlEffects = ControlEffects(
                prefs[Keys.ACRYLIC_EXCLUDED].orEmpty(), prefs[Keys.ACCENT_EXCLUDED].orEmpty(),
                prefs[Keys.OPACITY_EXCLUDED].orEmpty(),
            ),
            panelAccentEnabled = prefs[Keys.PANEL_ACCENT_ENABLED] ?: false,
            panelAccentOpacity = prefs[Keys.PANEL_ACCENT_OPACITY] ?: StoredSettings().panelAccentOpacity,
            webSearch = prefs[Keys.WEB_SEARCH] ?: StoredSettings().webSearch,
            webSearchWriteId = prefs[Keys.WEB_SEARCH_WRITE_ID],
            locationRegion = prefs[Keys.LOCATION] ?: StoredSettings().locationRegion,
            searchReasoning = prefs[Keys.SEARCH_REASONING] ?: false,
            readAloudEnabled = prefs[Keys.READ_ALOUD_ENABLED] ?: true,
            ttsVoiceName = prefs[Keys.TTS_VOICE] ?: StoredSettings().ttsVoiceName,
            ttsVoiceLabel = prefs[Keys.TTS_VOICE_LABEL] ?: StoredSettings().ttsVoiceLabel,
            modelIds = ModelIdList.decode(prefs[Keys.MODEL_IDS]),
            modelContextWindows = readModelContextWindows(prefs),
            modelMetadata = readModelMap<RemoteModelMetadata>(prefs, Keys.MODEL_METADATA),
            modelOverrides = readModelMap<ModelCapabilityOverride>(prefs, Keys.MODEL_OVERRIDES),
            onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] == true && prefs.hasSettingsBeyondProfileSeed(),
            loadingWords = readLoadingWords(prefs),
            loadingWordsUseThemeColor = prefs[Keys.LOADING_WORDS_USE_THEME_COLOR] ?: true,

        )
    }

    suspend fun setProvider(providerId: String, baseUrl: String, modelId: String) {
        editApi { prefs ->
            // 换了端点，旧端点拉到的模型列表就不再成立
            val endpointChanged = prefs[Keys.PROVIDER] != providerId ||
                prefs[Keys.BASE_URL] != baseUrl.trim()
            if (endpointChanged) {
                clearModelCatalog(prefs)
                prefs.remove(Keys.MODEL_OVERRIDES)
            }
            prefs[Keys.PROVIDER] = providerId
            prefs[Keys.BASE_URL] = baseUrl.trim()
            prefs[Keys.MODEL] = modelId
        }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        val trimmed = baseUrl.trim()
        editApi { prefs ->
            // 只改大小写 / 前后空格不算换端点；真的改了才丢掉旧列表
            if (prefs[Keys.BASE_URL].orEmpty().trim() != trimmed) {
                clearModelCatalog(prefs)
                prefs.remove(Keys.MODEL_OVERRIDES)
            }
            prefs[Keys.BASE_URL] = trimmed
        }
    }

    suspend fun setModel(modelId: String, expectedProfileId: String? = null) {
        editApi(expectedProfileId) { it[Keys.MODEL] = modelId }
    }

    private fun readModelContextWindows(prefs: Preferences): Map<String, Int> =
        prefs[Keys.MODEL_CONTEXT_WINDOWS]?.let { raw ->
            runCatching { apiProfileJson.decodeFromString<Map<String, Int>>(raw) }.getOrNull()
        }.orEmpty()

    private inline fun <reified T> readModelMap(prefs: Preferences, key: Preferences.Key<String>): Map<String, T> =
        prefs[key]?.let { raw ->
            runCatching { apiProfileJson.decodeFromString<Map<String, T>>(raw) }.getOrNull()
        }.orEmpty()

    private fun clearModelCatalog(prefs: MutablePreferences) {
        prefs.remove(Keys.MODEL_IDS)
        prefs.remove(Keys.MODEL_CONTEXT_WINDOWS)
        prefs.remove(Keys.MODEL_METADATA)
    }

    suspend fun setApiKey(apiKey: String) {
        // 清洗在**入口**做一次：粘贴带进来的换行/空格是 OkHttp 报
        // `Unexpected char 0x0a in Authorization value` 的头号原因
        // （见 ApiKey 的说明）。非 ASCII 不在这里静默丢弃，交给界面拦。
        val trimmed = ApiKey.normalize(apiKey)
        editApi { prefs ->
            // Different credentials at the same URL can expose a different model catalog.
            clearModelCatalog(prefs)
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

    suspend fun setUseSimpleCatIcon(enabled: Boolean) {
        preferences.edit { it[Keys.USE_SIMPLE_CAT_ICON] = enabled }
    }

    suspend fun setAccentColor(colorId: String) {
        preferences.edit { it[Keys.ACCENT_COLOR] = colorId }
    }

    suspend fun setOnboardingComplete() {
        preferences.edit { it[Keys.ONBOARDING_COMPLETE] = true }
    }

    suspend fun setFontScale(scale: Float) {
        preferences.edit { it[Keys.FONT_SCALE] = scale }
    }

    suspend fun setFollowSystemFont(follow: Boolean) {
        preferences.edit { it[Keys.FOLLOW_SYSTEM_FONT] = follow }
    }

    private fun readImportedFonts(prefs: Preferences): List<ImportedFont> =
        prefs[Keys.FONTS]?.let { raw ->
            runCatching { apiProfileJson.decodeFromString<List<ImportedFont>>(raw) }.getOrNull()
        }.orEmpty()

    private fun readLoadingWords(prefs: Preferences): List<String> =
        prefs[Keys.LOADING_WORDS]?.let { raw ->
            runCatching { apiProfileJson.decodeFromString<List<String>>(raw) }.getOrNull()
        }.orEmpty().filter(String::isNotBlank).distinct()

    internal suspend fun editLoadingWords(update: (List<String>) -> List<String>) {
        preferences.edit { prefs ->
            val words = update(readLoadingWords(prefs))
            if (words.isEmpty()) prefs.remove(Keys.LOADING_WORDS)
            else prefs[Keys.LOADING_WORDS] = apiProfileJson.encodeToString(words)
        }
    }

    internal suspend fun setLoadingWordsUseThemeColor(enabled: Boolean) {
        preferences.edit { it[Keys.LOADING_WORDS_USE_THEME_COLOR] = enabled }
    }

    suspend fun setAutoCollapseThinking(enabled: Boolean) {
        preferences.edit { it[Keys.AUTO_COLLAPSE_THINKING] = enabled }
    }

    suspend fun setHoldToTalk(enabled: Boolean) {
        preferences.edit { it[Keys.HOLD_TO_TALK] = enabled }
    }

    suspend fun setCrossChatMemory(enabled: Boolean) {
        preferences.edit { it[Keys.CROSS_CHAT_MEMORY] = enabled }
    }

    suspend fun setCrossChatMemoryLimit(limit: Int) {
        preferences.edit { it[Keys.CROSS_CHAT_MEMORY_LIMIT] = limit.coerceIn(1, 10) }
    }

    suspend fun setDefaultMode(modeId: String) {
        preferences.edit { it[Keys.DEFAULT_MODE] = modeId }
    }

    suspend fun setLanguage(languageId: String) {
        preferences.edit { it[LANGUAGE_KEY] = languageId }
    }

    suspend fun setApiStyle(styleId: String) {
        editApi { prefs ->
            if (prefs[Keys.API_STYLE] != styleId) clearModelCatalog(prefs)
            prefs[Keys.API_STYLE] = styleId
        }
    }

    suspend fun setWebSearch(enabled: Boolean, profileId: String, writeId: String) {
        editApi(profileId) {
            it[Keys.WEB_SEARCH] = enabled
            it[Keys.WEB_SEARCH_WRITE_ID] = writeId
        }
    }

    private fun readService(prefs: Preferences, key: Preferences.Key<String>): ServiceApiConfig =
        prefs[key]?.let { apiProfileJson.decodeFromString<ServiceApiConfig>(it) } ?: ServiceApiConfig()

    private fun readImageGen(prefs: Preferences): ImageGenConfig =
        prefs[Keys.IMAGE_GEN_API]?.let { apiProfileJson.decodeFromString<ImageGenConfig>(it) } ?: ImageGenConfig()

    suspend fun setImageGenApi(config: ImageGenConfig) {
        require(config.adapter in setOf("OPENAI_IMAGES", "GEMINI", "DASHSCOPE"))
        if (config.enabled && config.modelId.isBlank()) throw AppTextException(com.simplechat.app.R.string.error_no_model)
        require(config.size.matches(Regex("[0-9]{2,5}x[0-9]{2,5}")))
        require(config.aspectRatio in setOf("1:1", "3:2", "2:3", "4:3", "3:4", "16:9", "9:16", "21:9"))
        require(config.geminiImageSize in setOf("1K", "2K", "4K"))
        preferences.edit { it[Keys.IMAGE_GEN_API] = apiProfileJson.encodeToString(config) }
    }

    /** 第三方声音模型配置。 */
    suspend fun setVoiceApi(config: VoiceApiConfig) {
        require(config.adapter in setOf("OPENAI_TTS", "CUSTOM_OPENAI", "DASHSCOPE_TTS"))
        preferences.edit { it[Keys.VOICE_API] = apiProfileJson.encodeToString(config) }
    }

    /** 保存导入的字体列表（只存元数据，字节在 filesDir/fonts）。 */
    suspend fun setFonts(fonts: List<ImportedFont>) {
        preferences.edit { it[Keys.FONTS] = apiProfileJson.encodeToString(fonts.distinctBy(ImportedFont::id)) }
    }

    /** Append an imported font without racing another editor operation. */
    suspend fun addImportedFont(font: ImportedFont) {
        preferences.edit { prefs ->
            val fonts = readImportedFonts(prefs).filterNot { it.id == font.id } + font
            prefs[Keys.FONTS] = apiProfileJson.encodeToString(fonts)
        }
    }

    /** Remove the registry entry and clear selections that refer to it atomically. */
    suspend fun removeImportedFont(id: String) {
        if (id.isBlank()) return
        preferences.edit { prefs ->
            val fonts = readImportedFonts(prefs).filterNot { it.id == id }
            prefs[Keys.FONTS] = apiProfileJson.encodeToString(fonts)
            listOf(Keys.UI_FONT, Keys.USER_FONT, Keys.ASSISTANT_FONT).forEach { key ->
                if (prefs[key] == id) prefs.remove(key)
            }
        }
    }

    /** 设置某一层用哪个字体；`null` 表示回到系统字体。 */
    suspend fun setFontSlot(slot: FontSlot, fontId: String?) {
        val key = when (slot) {
            FontSlot.UI -> Keys.UI_FONT
            FontSlot.USER -> Keys.USER_FONT
            FontSlot.ASSISTANT -> Keys.ASSISTANT_FONT
        }
        preferences.edit {
            if (fontId == null) it.remove(key) else it[key] = fontId
        }
    }

    suspend fun setServiceApi(files: Boolean, config: ServiceApiConfig) {
        config.endpoint("https://api.deepseek.com", if (files) config.filesPath else config.searchPath)
        if (!files) config.endpoint("https://api.deepseek.com", config.fetchPath)
        require(config.count in 1..50)
        require(config.longTextChars in 4_000..1_000_000)
        require(config.purpose in listOf("user_data", "text_recognition", "rich_layout_recognition"))
        preferences.edit { it[if (files) Keys.FILES_API else Keys.SEARCH_API] = apiProfileJson.encodeToString(config) }
    }

    fun encryptServiceKey(key: String): String {
        val normalized = ApiKey.normalize(key)
        if (!ApiKey.isHeaderSafe(normalized)) throw AppTextException(com.simplechat.app.R.string.error_api_key_chars)
        return SecretBox.encrypt(normalized)
            ?: throw AppTextException(com.simplechat.app.R.string.api_profile_save_failed)
    }

    suspend fun setLocationRegion(region: String) {
        // 存**洗过**的值：逗号会把时间地点提醒的五个字段挤成六段（见 normalizeRegion）
        preferences.edit { it[Keys.LOCATION] = normalizeRegion(region) }
    }

    /** 提交同一次主题编辑，避免聊天页看到新壁纸和旧面板参数的混合状态。 */
    internal suspend fun saveCustomTheme(
        wallpaperPath: String?,
        wallpaper: WallpaperAppearance,
        panels: PanelAppearance,
        resetWallpaperPlacement: Boolean,
        typographyDraft: TypographyDraft? = null,
    ) {
        preferences.edit { prefs ->
            if (wallpaperPath == null) prefs.remove(Keys.WALLPAPER) else prefs[Keys.WALLPAPER] = wallpaperPath
            prefs[Keys.WALLPAPER_OPACITY] = wallpaper.opacity.coerceIn(0f, 1f)
            prefs[Keys.WALLPAPER_BLUR] = wallpaper.blur.coerceIn(0f, MaxWallpaperBlur)
            prefs[Keys.WALLPAPER_BRIGHTNESS] = wallpaper.brightness.coerceIn(0.2f, 2f)
            prefs[Keys.WALLPAPER_CONTRAST] = wallpaper.contrast.coerceIn(0.2f, 2f)
            prefs[Keys.WALLPAPER_SATURATION] = wallpaper.saturation.coerceIn(0f, 2f)
            prefs[Keys.WALLPAPER_DIM] = wallpaper.dim.coerceIn(0f, 1f)
            prefs[Keys.WALLPAPER_FLIP_H] = wallpaper.flipH
            if (resetWallpaperPlacement) {
                prefs[Keys.WALLPAPER_OFFSET_X] = 0.5f
                prefs[Keys.WALLPAPER_OFFSET_Y] = 0.5f
                prefs[Keys.WALLPAPER_SCALE] = 1f
                prefs[Keys.WALLPAPER_FIT] = "COVER"
            }
            prefs[Keys.ACRYLIC_ENABLED] = panels.acrylicEnabled
            prefs[Keys.FLOATING_GLASS] = panels.floatingGlass.coerceIn(0f, 1f)
            prefs.remove(Keys.WALLPAPER_GLASS_PANEL)
            prefs[Keys.WALLPAPER_PANEL_ALPHA] = panels.opacity.coerceIn(0f, 1f)
            prefs[Keys.ACRYLIC_EXCLUDED] = panels.controlEffects.acrylicExcluded
            prefs[Keys.ACCENT_EXCLUDED] = panels.controlEffects.accentExcluded
            prefs[Keys.OPACITY_EXCLUDED] = panels.controlEffects.opacityExcluded
            prefs[Keys.PANEL_ACCENT_ENABLED] = panels.accentEnabled
            prefs[Keys.PANEL_ACCENT_OPACITY] = panels.accentOpacity.coerceIn(0f, MaxPanelAccentOpacity)
            typographyDraft?.let { prefs.writeTypographySettings(it) }
        }
    }

    /** Save the global font preferences, font selections and text overrides in one update. */
    suspend fun saveTypographySettings(draft: TypographyDraft) {
        preferences.edit { it.writeTypographySettings(draft) }
    }

    private fun MutablePreferences.writeTypographySettings(draft: TypographyDraft) {
        require(draft.fontScale.isFinite() && draft.fontScale > 0f)
        require(FontSlot.entries.all { slot ->
            draft.typography[slot].fontScale?.let { it.isFinite() && it > 0f } ?: true
        })
        this[Keys.FONT_SCALE] = draft.fontScale
        this[Keys.FOLLOW_SYSTEM_FONT] = draft.followSystemFont
        writeFontId(Keys.UI_FONT, draft.uiFontId)
        writeFontId(Keys.USER_FONT, draft.userFontId)
        writeFontId(Keys.ASSISTANT_FONT, draft.assistantFontId)
        this[Keys.TYPOGRAPHY] = apiProfileJson.encodeToString(draft.typography)
    }

    private fun MutablePreferences.writeFontId(key: Preferences.Key<String>, fontId: String?) {
        if (fontId == null) remove(key) else this[key] = fontId
    }

    /** 自定义截取位置开关。 */
    suspend fun setWallpaperCustomCrop(enabled: Boolean) {
        preferences.edit { it[Keys.WALLPAPER_CUSTOM_CROP] = enabled }
    }

    /** 拖动预览时更新截取位置（0..1）。 */
    suspend fun setWallpaperOffset(x: Float, y: Float) {
        preferences.edit {
            it[Keys.WALLPAPER_OFFSET_X] = x.coerceIn(0f, 1f)
            it[Keys.WALLPAPER_OFFSET_Y] = y.coerceIn(0f, 1f)
        }
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



    /** Seed the registry without mistaking the automatic default profile for a legacy setup. */
    suspend fun migrateApiProfiles() {
        preferences.edit { prefs ->
            val hasExistingSettings = prefs.hasSettingsBeyondProfileSeed()
            val onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE]
            // Build 36 could mark a seed-only install complete on its second launch.
            prefs[Keys.ONBOARDING_COMPLETE] = (onboardingComplete ?: true) && hasExistingSettings
            ensureProfiles(prefs)
        }
    }

    private fun Preferences.hasSettingsBeyondProfileSeed(): Boolean = asMap().keys.any {
        it != Keys.PROFILES && it != Keys.ACTIVE_PROFILE && it != Keys.ONBOARDING_COMPLETE
    }

    suspend fun muteRetrySkipNotice() {
        preferences.edit { it[Keys.RETRY_SKIP_NOTICE] = false }
    }

    suspend fun setShowImageSkipNotice(enabled: Boolean) {
        preferences.edit { it[Keys.IMAGE_SKIP_NOTICE] = enabled }
    }

    suspend fun setSearchInactiveBranches(enabled: Boolean) {
        preferences.edit { it[Keys.SEARCH_INACTIVE_BRANCHES] = enabled }
    }

    suspend fun setSearchReasoning(enabled: Boolean) {
        preferences.edit { it[Keys.SEARCH_REASONING] = enabled }
    }

    suspend fun setReadAloudEnabled(enabled: Boolean) {
        preferences.edit { it[Keys.READ_ALOUD_ENABLED] = enabled }
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
        modelId = effectiveModelId(prefs[Keys.BASE_URL] ?: ProviderKind.DEEPSEEK.defaultBaseUrl, prefs[Keys.MODEL] ?: DEEPSEEK_FLASH),
        apiKeyEncrypted = prefs[Keys.API_KEY_ENC].orEmpty(),
        apiStyleId = prefs[Keys.API_STYLE] ?: "AUTO",
        modelIds = ModelIdList.decode(prefs[Keys.MODEL_IDS]),
        modelContextWindows = readModelContextWindows(prefs),
        modelMetadata = readModelMap<RemoteModelMetadata>(prefs, Keys.MODEL_METADATA),
        modelOverrides = readModelMap<ModelCapabilityOverride>(prefs, Keys.MODEL_OVERRIDES),
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
        if (profile.modelContextWindows.isEmpty()) prefs.remove(Keys.MODEL_CONTEXT_WINDOWS)
        else prefs[Keys.MODEL_CONTEXT_WINDOWS] = apiProfileJson.encodeToString(profile.modelContextWindows)
        if (profile.modelMetadata.isEmpty()) prefs.remove(Keys.MODEL_METADATA)
        else prefs[Keys.MODEL_METADATA] = apiProfileJson.encodeToString(profile.modelMetadata)
        if (profile.modelOverrides.isEmpty()) prefs.remove(Keys.MODEL_OVERRIDES)
        else prefs[Keys.MODEL_OVERRIDES] = apiProfileJson.encodeToString(profile.modelOverrides)
        prefs[Keys.REASONING] = profile.reasoningLevelId
        prefs[Keys.TEMPERATURE] = profile.temperature
        prefs[Keys.TOP_P] = profile.topP
        prefs[Keys.MAX_TOKENS] = profile.maxTokens
        prefs[Keys.WEB_SEARCH] = profile.webSearch
    }

    /** Update a profile without selecting it or changing its recency. */
    internal suspend fun updateApiProfile(id: String, transform: (ApiProfile) -> ApiProfile) {
        preferences.edit { prefs ->
            if (prefs[Keys.PROFILES] == null && id != "legacy") return@edit
            val profiles = ensureProfiles(prefs)
            val current = profiles.firstOrNull { it.id == id } ?: return@edit
            val updated = transform(current)
            require(updated.id == id) { "Cannot change a profile ID" }
            if (prefs[Keys.ACTIVE_PROFILE] == id) activate(prefs, updated)
            prefs[Keys.PROFILES] = apiProfileJson.encodeToString(profiles.map { if (it.id == id) updated else it })
        }
    }

    internal suspend fun readApiKeyForProfile(id: String): String {
        val prefs = preferences.data.first()
        if (prefs[Keys.PROFILES] == null) return if (id == "legacy") readApiKey(prefs) else ""
        return profileRows(prefs).firstOrNull { it.id == id }?.apiKeyEncrypted
            ?.takeIf { it.isNotBlank() }?.let(SecretBox::decrypt).orEmpty()
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
        val SEARCH_API = stringPreferencesKey("search_api_config")
        val FILES_API = stringPreferencesKey("files_api_config")
        val VOICE_API = stringPreferencesKey("voice_api_config")
        val IMAGE_GEN_API = stringPreferencesKey("image_gen_api_config")
        val ACTIVE_PROFILE = stringPreferencesKey("active_api_profile")
        val RETRY_SKIP_NOTICE = booleanPreferencesKey("show_retry_skip_notice")
        val IMAGE_SKIP_NOTICE = booleanPreferencesKey("show_image_skip_notice")
        val LOADING_WORDS = stringPreferencesKey("loading_words")
        val LOADING_WORDS_USE_THEME_COLOR = booleanPreferencesKey("loading_words_use_theme_color")
        val SEARCH_REASONING = booleanPreferencesKey("search_reasoning")
        val READ_ALOUD_ENABLED = booleanPreferencesKey("read_aloud_enabled")
        val SEARCH_INACTIVE_BRANCHES = booleanPreferencesKey("search_inactive_branches")
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
        val USE_SIMPLE_CAT_ICON = booleanPreferencesKey("use_simple_cat_icon")
        val ACCENT_COLOR = stringPreferencesKey("accent_color")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val FOLLOW_SYSTEM_FONT = booleanPreferencesKey("follow_system_font")
        val AUTO_COLLAPSE_THINKING = booleanPreferencesKey("auto_collapse_thinking")
        val HOLD_TO_TALK = booleanPreferencesKey("hold_to_talk")
        val CROSS_CHAT_MEMORY = booleanPreferencesKey("cross_chat_memory")
        val CROSS_CHAT_MEMORY_LIMIT = intPreferencesKey("cross_chat_memory_limit")
        val DEFAULT_MODE = stringPreferencesKey("default_mode")
        val API_STYLE = stringPreferencesKey("api_style")
        val WEB_SEARCH = booleanPreferencesKey("web_search")
        val WEB_SEARCH_WRITE_ID = stringPreferencesKey("web_search_write_id")
        val LOCATION = stringPreferencesKey("location_region")

        /** 朗读音色：机器标识；空 = 系统默认。 */
        val TTS_VOICE = stringPreferencesKey("tts_voice_name")

        /** 朗读音色的人类可读名字（仅用于显示）。 */
        val TTS_VOICE_LABEL = stringPreferencesKey("tts_voice_label")

        /** 当前端点 `/models` 拉回来的 id，换行分隔（[ModelIdList]）。 */
        val MODEL_IDS = stringPreferencesKey("model_ids")

        /** 导入的字体列表（JSON，见 [ImportedFont]）。 */
        val FONTS = stringPreferencesKey("fonts")

        /** 三层各自选中的字体 id；空 = 该层用系统字体。 */
        val UI_FONT = stringPreferencesKey("ui_font")
        val USER_FONT = stringPreferencesKey("user_font")
        val ASSISTANT_FONT = stringPreferencesKey("assistant_font")
        /** Version-tolerant typography overrides; missing keys inherit existing global/theme settings. */
        val TYPOGRAPHY = stringPreferencesKey("typography_settings")

        /** 壁纸文件名（filesDir/wallpaper 下）；空 = 不使用。 */
        val WALLPAPER = stringPreferencesKey("wallpaper")
        val WALLPAPER_OPACITY = floatPreferencesKey("wallpaper_opacity")
        val ACRYLIC_ENABLED = booleanPreferencesKey("interface_acrylic_enabled")
        val FLOATING_GLASS = floatPreferencesKey("floating_glass")
        val WALLPAPER_BLUR = floatPreferencesKey("wallpaper_blur")
        val WALLPAPER_BRIGHTNESS = floatPreferencesKey("wallpaper_brightness")
        val WALLPAPER_CONTRAST = floatPreferencesKey("wallpaper_contrast")
        val WALLPAPER_SATURATION = floatPreferencesKey("wallpaper_saturation")
        val WALLPAPER_OFFSET_X = floatPreferencesKey("wallpaper_offset_x")
        val WALLPAPER_OFFSET_Y = floatPreferencesKey("wallpaper_offset_y")
        val WALLPAPER_SCALE = floatPreferencesKey("wallpaper_scale")
        val WALLPAPER_DIM = floatPreferencesKey("wallpaper_dim")
        val WALLPAPER_PANEL_RADIUS = floatPreferencesKey("wallpaper_panel_radius")
        val WALLPAPER_PANEL_ALPHA = floatPreferencesKey("wallpaper_panel_alpha")
        val ACRYLIC_EXCLUDED = stringSetPreferencesKey("interface_acrylic_excluded")
        val ACCENT_EXCLUDED = stringSetPreferencesKey("interface_accent_excluded")
        val OPACITY_EXCLUDED = stringSetPreferencesKey("interface_opacity_excluded")
        val PANEL_ACCENT_ENABLED = booleanPreferencesKey("panel_accent_enabled")
        val PANEL_ACCENT_OPACITY = floatPreferencesKey("panel_accent_opacity")
        val WALLPAPER_GLASS_PANEL = booleanPreferencesKey("wallpaper_glass_panel")
        val WALLPAPER_CUSTOM_CROP = booleanPreferencesKey("wallpaper_custom_crop")
        val WALLPAPER_FIT = stringPreferencesKey("wallpaper_fit")
        val WALLPAPER_FLIP_H = booleanPreferencesKey("wallpaper_flip_h")
        val MODEL_CONTEXT_WINDOWS = stringPreferencesKey("model_context_windows")
        val MODEL_METADATA = stringPreferencesKey("model_metadata")
        val MODEL_OVERRIDES = stringPreferencesKey("model_overrides")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")

    }
}
